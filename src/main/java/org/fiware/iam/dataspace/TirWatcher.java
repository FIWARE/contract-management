package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TirEnumerationException;
import org.fiware.iam.logging.DownstreamError;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Derives participant.removed events from the trust anchor registry by diffing complete enumerations against the last
 * snapshot, guarded by confirmations, a circuit breaker and an empty-registry alert. State is in memory only.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)
public class TirWatcher {

    // type and data as in §8 of the data space eventing architecture; the CM-3 (#103) handler matches both
    public static final String PARTICIPANT_REMOVED_TYPE = "org.fiware.dataspace.participant.removed";
    static final String PARTICIPANT_ID_KEY = "participantId";

    static final String CYCLES_METRIC = "dataspace.tir.cycles";
    static final String REMOVALS_METRIC = "dataspace.tir.removals";
    static final String OUTCOME_TAG = "outcome";

    private static final int MIN_ANNOUNCED_SUBSCRIPTIONS = 1;

    /**
     * How a cycle ended; the lower-case name is the metric tag value.
     */
    public enum Outcome {
        INITIAL, COMPLETE, INCOMPLETE, EMPTY, BREAKER;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Immutable view of the watcher, readable without waiting for a running cycle. {@code alert} is null while none is
     * active; {@code lastOutcome} and {@code firstCycleDue} are null until a cycle ran or was scheduled.
     */
    public record Status(@Nullable Outcome lastOutcome, @Nullable Instant lastSuccessfulCycle,
                         @Nullable Instant firstCycleDue, int snapshotSize, int pendingSize, int undeliveredSize,
                         @Nullable FailureReason alert) {

        public static final Status NOT_SCHEDULED = new Status(null, null, null, 0, 0, 0, null);

        public boolean alertActive() {
            return alert != null;
        }
    }

    private final TrustedIssuersRegistryReader reader;
    private final OwnEventPublisher publisher;
    private final SubscriptionRegistry subscriptionRegistry;
    private final DataSpaceEventsProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    // guarded by this
    @Nullable
    private Set<String> snapshot;
    private final Map<String, Integer> pending = new HashMap<>();
    private final Map<String, Removal> undelivered = new LinkedHashMap<>();
    @Nullable
    private FailureReason alert;
    private boolean noSource;

    private volatile Status status = Status.NOT_SCHEDULED;

    /**
     * A confirmed removal awaiting announcement; the event id is fixed at confirmation, so every retry is the same
     * event to the receivers' deduplication.
     */
    private record Removal(String eventId, Set<RetryReason> warnedFor) {

        Removal withWarning(RetryReason reason) {
            EnumSet<RetryReason> reasons = EnumSet.of(reason);
            reasons.addAll(warnedFor);
            return new Removal(eventId, Set.copyOf(reasons));
        }
    }

    /**
     * Why a confirmed removal is kept for retry; each reason is logged at WARN once per removal, so a failing delivery
     * path is not hidden behind an earlier benign WARN, and alternating reasons do not WARN every cycle.
     */
    private enum RetryReason {
        NOT_SUBSCRIBED,
        PUBLISH_FAILED
    }

    public TirWatcher(TrustedIssuersRegistryReader reader, OwnEventPublisher publisher,
                      SubscriptionRegistry subscriptionRegistry, DataSpaceEventsProperties properties, Clock clock,
                      Optional<MeterRegistry> meterRegistry) {
        this.reader = reader;
        this.publisher = publisher;
        this.subscriptionRegistry = subscriptionRegistry;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry.orElseGet(CompositeMeterRegistry::new);
    }

    /**
     * Never null and never blocks on a running cycle.
     */
    public Status status() {
        return status;
    }

    /**
     * Records when the first cycle is due, the health baseline until a cycle succeeds. Called once, before that cycle.
     */
    synchronized void scheduled(Instant firstCycleDue) {
        Status previous = status;
        status = new Status(previous.lastOutcome(), previous.lastSuccessfulCycle(), firstCycleDue,
                previous.snapshotSize(), previous.pendingSize(), previous.undeliveredSize(), previous.alert());
    }

    /**
     * Runs one cycle; cycles never overlap. Blocks for the whole enumeration of the registry.
     */
    public synchronized Outcome cycle() {
        TirWatcherSettings settings = properties.getTirWatcher();
        Outcome outcome = run(settings.effectiveConfirmations(), settings.effectiveMaxRemovalsPerCycle());
        meterRegistry.counter(CYCLES_METRIC, OUTCOME_TAG, outcome.tagValue()).increment();
        Status previous = status;
        boolean successful = outcome == Outcome.INITIAL || outcome == Outcome.COMPLETE;
        status = new Status(outcome, successful ? clock.instant() : previous.lastSuccessfulCycle(),
                previous.firstCycleDue(), snapshot == null ? 0 : snapshot.size(), pending.size(), undelivered.size(),
                alert);
        return outcome;
    }

    private Outcome run(int confirmations, int maxRemovalsPerCycle) {
        Set<String> current;
        try {
            current = reader.enumerate();
        } catch (TirEnumerationException e) {
            log.warn("Trust anchor registry not enumerated completely, nothing changed: {}", DownstreamError.reason(e), e);
            return Outcome.INCOMPLETE;
        }

        if (current.isEmpty() && snapshot != null && !snapshot.isEmpty()) {
            raise(FailureReason.TIR_EMPTY, "The trust anchor registry turned empty, %s participants were listed before; nothing is announced."
                    .formatted(snapshot.size()));
            return Outcome.EMPTY;
        }

        if (snapshot == null) {
            snapshot = new HashSet<>(current);
            if (current.isEmpty()) {
                log.warn("The trust anchor registry is empty; nothing to watch yet.");
            } else {
                log.info("Initial snapshot of the trust anchor registry taken: {} participants.", current.size());
            }
            clearAlert();
            return Outcome.INITIAL;
        }

        Set<String> missing = new HashSet<>(snapshot);
        missing.removeAll(current);
        if (missing.size() > maxRemovalsPerCycle) {
            raise(FailureReason.TIR_MASS_REMOVAL, "%s of %s participants are missing from the trust anchor registry, more than the %s allowed; nothing is announced."
                    .formatted(missing.size(), snapshot.size(), maxRemovalsPerCycle));
            return Outcome.BREAKER;
        }

        pending.keySet().removeAll(current);
        dropReappeared(current);
        for (String did : missing) {
            int cycles = pending.merge(did, 1, Integer::sum);
            if (cycles >= confirmations) {
                snapshot.remove(did);
                pending.remove(did);
                undelivered.put(did, new Removal(UUID.randomUUID().toString(), Set.of()));
            }
        }
        announceUndelivered();
        snapshot.addAll(current);
        clearAlert();
        return Outcome.COMPLETE;
    }

    private void announceUndelivered() {
        // checked on every complete cycle, so a restored source clears the flag even when nothing is published
        if (noSource && publisher.hasSource()) {
            reportSourceRestored();
        }
        for (String did : List.copyOf(undelivered.keySet())) {
            try {
                if (!announce(did)) {
                    return;
                }
            } catch (RuntimeException e) {
                // keeps the rest of the cycle (snapshot, alert, metric, status) intact
                keepForRetry(did, RetryReason.PUBLISH_FAILED, "publishing failed: " + DownstreamError.reason(e), e);
            }
        }
    }

    /**
     * Returns false when no source is configured, so no further removal can be published in this cycle.
     */
    private boolean announce(String did) {
        boolean othersSubscribed = subscriptionRegistry.active(PARTICIPANT_REMOVED_TYPE).stream()
                .anyMatch(subscription -> !did.equals(subscription.subscriberId()));
        if (!othersSubscribed) {
            keepForRetry(did, RetryReason.NOT_SUBSCRIBED, "no other participant subscribed", null);
            return true;
        }
        Optional<OwnEventPublisher.PublishedEvent> published =
                publisher.publish(PARTICIPANT_REMOVED_TYPE, did, Map.of(PARTICIPANT_ID_KEY, did),
                        undelivered.get(did).eventId());
        if (published.isEmpty()) {
            reportNoSource();
            return false;
        }
        int subscriptions = published.get().subscriptions();
        if (subscriptions < MIN_ANNOUNCED_SUBSCRIPTIONS) {
            keepForRetry(did, RetryReason.NOT_SUBSCRIBED, "it matched no subscription", null);
            return true;
        }
        meterRegistry.counter(REMOVALS_METRIC).increment();
        log.info("Participant {} removed from the trust anchor registry, published to {} matching subscriptions.",
                did, subscriptions);
        // last, so a failure above still finds the removal for keepForRetry; a retry reuses the event id
        undelivered.remove(did);
        return true;
    }

    private void dropReappeared(Set<String> current) {
        for (String did : List.copyOf(undelivered.keySet())) {
            if (current.contains(did)) {
                undelivered.remove(did);
                log.info("Participant {} is listed again; its confirmed removal is no longer announced.", did);
            }
        }
    }

    private void keepForRetry(String did, RetryReason reason, String why, @Nullable Throwable cause) {
        Removal removal = undelivered.get(did);
        undelivered.put(did, removal.withWarning(reason));
        if (removal.warnedFor().contains(reason)) {
            log.debug("Removal of participant {} still not announced: {}.", did, why, cause);
        } else {
            log.warn("Removal of participant {} not announced yet: {}; retried on the next complete cycle.", did, why,
                    cause);
        }
    }

    private void reportNoSource() {
        if (noSource) {
            log.debug("Confirmed removals still not announced: no own participant id is set.");
            return;
        }
        noSource = true;
        log.error("Cannot announce {} confirmed removals: neither dataspace.events.own-participant-id nor general.did is set; retried on the next complete cycle.",
                undelivered.size());
    }

    private void reportSourceRestored() {
        noSource = false;
        log.info("An own participant id is set again; confirmed removals are announced.");
    }

    private void raise(FailureReason reason, String message) {
        if (alert == reason) {
            log.debug("{}", reason.format(message));
            return;
        }
        alert = reason;
        log.error("{}", reason.format(message));
    }

    private void clearAlert() {
        if (alert != null) {
            log.info("Trust anchor registry alert {} cleared.", alert.getCode());
            alert = null;
        }
    }
}
