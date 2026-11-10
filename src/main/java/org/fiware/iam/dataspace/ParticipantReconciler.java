package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.AdoptionPolicy;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.EffectiveRemoval;
import org.fiware.iam.configuration.DataSpaceEventsProperties.OnParticipantRemoved;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.AdoptionIncompleteException;
import org.fiware.iam.logging.DownstreamError;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The reconciliation sweep of participant.removed: per publisher, the identifiers still holding its membership grant
 * although its registry no longer lists them are handled with its policy, unless they trip its breaker. While adoption is
 * allowed, listed entries of nothing but unattributed credentials are adopted into the publisher's data space.
 * <p>
 * {@link #run()} is not reentrant (one scheduler thread); {@link #trippedBreakers()} may be read concurrently. Never
 * removes unattributed entries, except, while adoption is allowed, what looks like a copy of a candidate's only grant,
 * P's membership (a heuristic, see {@link RemovalPlan}); makes no registry lookup: the listing is fresh for the run.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class ParticipantReconciler {

    /**
     * Unapplied candidates read (and logged, counted) per publisher and run at most; the rest follow in later runs.
     */
    static final int MAX_NEW_CANDIDATES_PER_RUN = 200;
    static final String HELD_METRIC_NAME = ParticipantRemoval.METRIC_NAME + ".held";
    // a wrongly truncated registry trips the breaker with thousands of DIDs; the full set stays in trippedBreakers()
    static final int BREAKER_LOG_SAMPLE_SIZE = 20;
    /**
     * How long an entry found not adoptable (it holds grants) is not read again; it may lose its grants later.
     */
    static final Duration NOT_ADOPTABLE_RECHECK_INTERVAL = Duration.ofHours(24);
    private static final String NO_REGISTRY_URL_CONSEQUENCE =
            "without registry-url a failed or dropped removal event is never completed";

    private final TrustedIssuersListAdapter adapter;
    private final TilCapabilities capabilities;
    private final TrustedIssuersRegistryReader registryReader;
    private final ParticipantRemoval removal;
    private final ParticipantAdoption adoption;
    private final RemovedParticipantsReport report;
    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;
    private final ConfigurationRefreshLock refreshLock;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // publisher -> DID -> mode tag of the unapplied candidates already counted; only touched by run()
    private final Map<String, Map<String, String>> seen = new HashMap<>();
    // publisher -> policy for which the missing registry-url was already logged; only touched by run()
    private final Map<String, String> loggedMissingRegistry = new HashMap<>();
    // publisher -> DID -> when it was found not adoptable; only touched by run()
    private final Map<String, Map<String, Instant>> notAdoptable = new HashMap<>();
    // publisher -> DID -> credentials copied by an adoption that failed from its grant on; only touched by run().
    // Not persisted: after a restart the duplicate stays until a removal of the participant revokes it with its membership
    private final Map<String, Map<String, AdoptionIncompleteException>> incompleteAdoptions = new HashMap<>();
    // publisher -> reason its issuers could last not be listed for adoption; only touched by run()
    private final Map<String, String> listingFailures = new HashMap<>();
    // the refused adoption configuration last warned about, null while adoption is not refused; only touched by run()
    @Nullable
    private AdoptionRefusal loggedAdoptionRefusal;
    private final Map<String, AtomicInteger> heldGauges = new ConcurrentHashMap<>();
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
    // reason of the last skipped run, null while runs are not skipped; only touched by run()
    @Nullable
    private String skipReason;

    public ParticipantReconciler(TrustedIssuersListAdapter adapter, TilCapabilities capabilities,
                                 TrustedIssuersRegistryReader registryReader, ParticipantRemoval removal,
                                 ParticipantAdoption adoption, RemovedParticipantsReport report,
                                 DataSpaceEventsProperties properties, GeneralProperties generalProperties,
                                 ConfigurationRefreshLock refreshLock, Optional<MeterRegistry> meterRegistry, Clock clock) {
        this.adapter = adapter;
        this.capabilities = capabilities;
        this.registryReader = registryReader;
        this.removal = removal;
        this.adoption = adoption;
        this.report = report;
        this.properties = properties;
        this.generalProperties = generalProperties;
        this.refreshLock = refreshLock;
        this.meterRegistry = meterRegistry.orElseGet(CompositeMeterRegistry::new);
        this.clock = clock;
    }

    /**
     * A tripped breaker: the write candidates held back and the cap they exceed.
     */
    public record Breaker(Set<String> writeCandidates, int maxRemovalsPerRun) {
    }

    /**
     * The publishers whose breaker is tripped.
     */
    public Map<String, Breaker> trippedBreakers() {
        return Map.copyOf(breakers);
    }

    // what decides a refusal and its WARN, so unrelated policy edits (keep, overrides, caps) do not warn again
    private record AdoptionRefusal(AdoptionPolicy.RefusalReason reason, @Nullable String dataspaceId,
                                   List<String> removalPublishers, Map<String, Boolean> askingWithoutRegistryUrl) {
    }

    // the run's decisions, taken from one configuration state; kept candidates have no removal, kept DIDs are never adopted
    private record Split(@Nullable String registryUrl, @Nullable String ownId, int cap, RemovalMode publisherMode,
                         Set<String> kept,
                         Map<String, EffectiveRemoval> report, Map<String, EffectiveRemoval> write,
                         Map<String, EffectiveRemoval> adopt, int adoptionCap,
                         Map<String, EffectiveRemoval> completable) {
    }

    /**
     * One sweep over all removal publishers; blocks until done. Skipped while a required TIL capability is not supported.
     */
    public void run() {
        warnIfAdoptionRefused();
        try {
            capabilities.requireSupported().block();
        } catch (RuntimeException e) {
            String reason = DownstreamError.reason(e);
            if (!reason.equals(skipReason)) {
                log.warn("Participant reconciliation skipped until the trusted-issuers-list is usable: {}", reason);
            }
            skipReason = reason;
            return;
        }
        if (skipReason != null) {
            log.info("Participant reconciliation resumed: the trusted-issuers-list supports what it needs.");
            skipReason = null;
        }

        Set<String> publisherIds = new HashSet<>();
        for (Publisher publisher : properties.removalPublishers()) {
            publisherIds.add(publisher.getId());
            try {
                reconcile(publisher);
            } catch (RuntimeException e) {
                log.warn("Participant reconciliation of publisher {} failed; the other publishers are not affected: {}",
                        publisher.getId(), DownstreamError.reason(e), e);
                removal.count(publisher.getId(), publisher.getOnParticipantRemoved().effectiveMode().tagValue(),
                        RemovalAction.FAILED);
            }
        }
        forgetAllBut(publisherIds);
    }

    private void reconcile(Publisher publisher) {
        String publisherId = publisher.getId();
        OnParticipantRemoved policy = publisher.getOnParticipantRemoved();
        String registryUrl = policy.getRegistryUrl();
        if (registryUrl == null || registryUrl.isBlank()) {
            skipWithoutRegistry(publisherId, policy);
            return;
        }
        loggedMissingRegistry.remove(publisherId);

        Set<String> local = adapter.issuersGrantedBy(publisherId, TrustedIssuersListAdapter.DATASPACE_MEMBERSHIP_SCOPE_TYPE)
                .blockOptional()
                .orElse(Set.of());
        boolean adopting = properties.adoption().isAllowed();
        Set<String> listed = local.isEmpty() && !adopting ? Set.of() : registryReader.enumerate(registryUrl);
        // sorted, so the order of writes and log lines is stable across runs
        Set<String> candidates = new TreeSet<>(local);
        candidates.removeAll(listed);
        Set<String> unadopted = new TreeSet<>(listed);
        unadopted.removeAll(local);
        Map<String, AdoptionIncompleteException> incomplete = incompleteAdoptions.computeIfAbsent(publisherId, id -> new TreeMap<>());

        // decided under the lock and passed on to every apply, so a refresh during the run cannot write past the breaker
        Split split = refreshLock.exclusively(() -> split(publisherId, candidates, adopting ? unadopted : Set.of(),
                incomplete.keySet()));
        if (!registryUrl.equals(split.registryUrl())) {
            // the candidates were listed from a registry the current configuration no longer names
            log.info("Participant reconciliation of publisher {} skipped: its registry-url changed or it stopped "
                    + "announcing removals while it was listed; reconciled in the next run.", publisherId);
            if (split.registryUrl() == null) {
                // not reconciled any more; the run's forgetAllBut still counts it as configured
                Optional.ofNullable(heldGauges.get(publisherId)).ifPresent(gauge -> gauge.set(0));
                breakers.remove(publisherId);
                report.retainStandingDrift(publisherId, Set.of());
            }
            return;
        }
        // before the removals: a delisted entry still holding both copies would otherwise keep its unattributed originals
        completeAdoptions(publisherId, registryUrl, incomplete, split.completable(), candidates);
        Map<String, String> seenOfPublisher = seen.computeIfAbsent(publisherId, id -> new HashMap<>());
        seenOfPublisher.keySet().retainAll(candidates);
        // re-listed or released otherwise since: the report must not show it as current drift
        report.retainStandingDrift(publisherId, candidates);
        for (String did : split.kept()) {
            if (RemovalMode.KEEP_TAG_VALUE.equals(seenOfPublisher.put(did, RemovalMode.KEEP_TAG_VALUE))) {
                report.touch(publisherId, did);
            } else {
                removal.apply(publisherId, did, RemovalTrigger.RECONCILIATION, null).block();
            }
        }

        Map<String, EffectiveRemoval> unapplied = new HashMap<>(split.report());
        String withheldReason = null;
        int cap = split.cap();
        Map<String, EffectiveRemoval> writeCandidates = split.write();
        Set<String> stillHeld = new HashSet<>(candidates);
        stillHeld.remove(split.ownId());
        if (writeCandidates.size() > cap) {
            trip(publisherId, split.publisherMode(), writeCandidates.keySet(), cap);
            unapplied.putAll(writeCandidates);
            withheldReason = "held back by the breaker: %s write candidates exceed max-removals-per-run %s"
                    .formatted(writeCandidates.size(), cap);
        } else {
            clear(publisherId);
            for (String did : new TreeSet<>(writeCandidates.keySet())) {
                if (releasedBy(publisherId, did, writeCandidates.get(did), seenOfPublisher)) {
                    stillHeld.remove(did);
                }
            }
        }
        heldGauge(publisherId).set(stillHeld.size());
        withholdAll(publisherId, unapplied, writeCandidates.keySet(), withheldReason, seenOfPublisher);
        adoptAll(publisherId, registryUrl, split.publisherMode(), split.adopt(), split.adoptionCap(), incomplete);
    }

    // no I/O: runs under the refresh lock; the policy is re-read so its cap matches the modes.
    // A publisher no longer announcing removals gets a split without registry-url, so the run skips it.
    private Split split(String publisherId, Set<String> candidates, Set<String> adoptionCandidates,
                        Set<String> incompleteAdoptions) {
        Optional<OnParticipantRemoved> found = properties.removalPublishers().stream()
                .filter(publisher -> publisher.getId().equals(publisherId))
                .findFirst()
                .map(Publisher::getOnParticipantRemoved);
        if (found.isEmpty()) {
            return new Split(null, null, 0, RemovalMode.REPORT, Set.of(), Map.of(), Map.of(), Map.of(), 0, Map.of());
        }
        OnParticipantRemoved current = found.get();
        String ownId = properties.ownId(generalProperties);
        Set<String> kept = new TreeSet<>();
        Map<String, EffectiveRemoval> reportCandidates = new HashMap<>();
        Map<String, EffectiveRemoval> writeCandidates = new HashMap<>();
        for (String did : candidates) {
            Optional<EffectiveRemoval> decided = properties.effectiveRemoval(publisherId, did, ownId);
            if (decided.isEmpty()) {
                kept.add(did);
            } else if (decided.get().mode().writes()) {
                writeCandidates.put(did, decided.get());
            } else {
                reportCandidates.put(did, decided.get());
            }
        }
        Map<String, EffectiveRemoval> adopt = new TreeMap<>();
        Map<String, EffectiveRemoval> completable = new TreeMap<>();
        if (properties.adoption().isAllowed() && current.isAdoptUnattributed()) {
            adoptionCandidates.forEach(did -> properties.effectiveRemoval(publisherId, did, ownId)
                    .ifPresent(decided -> adopt.put(did, decided)));
            incompleteAdoptions.forEach(did -> properties.effectiveRemoval(publisherId, did, ownId)
                    .ifPresent(decided -> completable.put(did, decided)));
        }
        return new Split(current.getRegistryUrl(), ownId, current.effectiveMaxRemovalsPerRun(), current.effectiveMode(),
                kept, reportCandidates, writeCandidates, adopt, current.effectiveMaxAdoptionsPerRun(), completable);
    }

    // applies the candidate; true if it no longer holds the publisher's membership afterwards
    private boolean releasedBy(String publisherId, String did, EffectiveRemoval decided,
                               Map<String, String> seenOfPublisher) {
        // an applied candidate that stays (e.g. a failed write) is reported anew if the breaker later holds it back
        seenOfPublisher.remove(did);
        try {
            RemovalOutcome outcome = removal.apply(publisherId, did, RemovalTrigger.RECONCILIATION, decided).block();
            return outcome != null && outcome.held() != null && outcome.held().membershipOf(publisherId).isEmpty();
        } catch (RuntimeException e) {
            log.warn("Participant {} removed by {}: reconciliation failed, retried in the next run: {}", did, publisherId,
                    DownstreamError.reason(e), e);
            return false;
        }
    }

    private void withholdAll(String publisherId, Map<String, EffectiveRemoval> unapplied, Set<String> withheld,
                             @Nullable String withheldReason, Map<String, String> seenOfPublisher) {
        int reads = 0;
        for (String did : new TreeSet<>(unapplied.keySet())) {
            RemovalMode mode = unapplied.get(did).mode();
            if (mode.tagValue().equals(seenOfPublisher.get(did))) {
                report.touch(publisherId, did);
                continue;
            }
            if (reads >= MAX_NEW_CANDIDATES_PER_RUN) {
                continue;
            }
            reads++;
            try {
                removal.withhold(publisherId, did, mode, withheld.contains(did) ? withheldReason : null).block();
                seenOfPublisher.put(did, mode.tagValue());
            } catch (RuntimeException e) {
                log.warn("Participant {} removed by {}: what is held could not be read for the report, retried in the next run: {}",
                        did, publisherId, DownstreamError.reason(e), e);
            }
        }
    }

    // an adoption that failed from its grant on holds the copies twice, and in `local` it is no adoption candidate any more
    private void completeAdoptions(String publisherId, String registryUrl, Map<String, AdoptionIncompleteException> incomplete,
                                   Map<String, EffectiveRemoval> completable, Set<String> delisted) {
        for (String did : List.copyOf(incomplete.keySet())) {
            AdoptionIncompleteException pending = incomplete.remove(did);
            EffectiveRemoval decided = completable.get(did);
            if (decided == null) {
                log.warn("Participant {} listed by {}: its adoption is not completed, adoption is no longer allowed for it; "
                        + "resolve the duplicate of its unattributed credentials manually.", did, publisherId);
                continue;
            }
            try {
                adoption.complete(publisherId, registryUrl, did, decided.mode().tagValue(), pending.getCopied(),
                        pending.isRevokeAttempted(), delisted.contains(did)).block();
            } catch (AdoptionIncompleteException e) {
                incomplete.put(did, e);
                warnIncomplete(publisherId, did, decided.mode().tagValue(), e);
            } catch (RuntimeException e) {
                incomplete.put(did, pending);
                warnIncomplete(publisherId, did, decided.mode().tagValue(), e);
            }
        }
    }

    // not under the breaker: an adoption only adds a grant before it removes the duplicate, so truncating is safe
    private void adoptAll(String publisherId, String registryUrl, RemovalMode publisherMode,
                          Map<String, EffectiveRemoval> candidates, int cap, Map<String, AdoptionIncompleteException> incomplete) {
        Map<String, Instant> notAdoptableOfPublisher = notAdoptable.computeIfAbsent(publisherId, id -> new HashMap<>());
        notAdoptableOfPublisher.keySet().retainAll(candidates.keySet());
        if (candidates.isEmpty() || cap == 0) {
            return;
        }
        Set<String> held;
        try {
            // only issuers the list holds are read; a failure here skips adoption, never the removals before it
            held = adapter.allIssuers().blockOptional().orElse(Set.of());
        } catch (RuntimeException e) {
            // a standing cause (e.g. more issuers than max-pages) fails every run alike, so it is reported once
            String reason = DownstreamError.reason(e);
            if (!reason.equals(listingFailures.put(publisherId, reason))) {
                log.warn("Adoption for publisher {} skipped until its issuers can be listed: {}", publisherId, reason, e);
                removal.count(publisherId, publisherMode.tagValue(), RemovalAction.FAILED);
            }
            return;
        }
        if (listingFailures.remove(publisherId) != null) {
            log.info("Adoption for publisher {} resumed: its issuers are listed again.", publisherId);
        }
        Instant now = clock.instant();
        int reads = 0;
        int attempts = 0;
        for (Map.Entry<String, EffectiveRemoval> candidate : candidates.entrySet()) {
            if (attempts >= cap || reads >= MAX_NEW_CANDIDATES_PER_RUN) {
                break;
            }
            String did = candidate.getKey();
            Instant readAt = notAdoptableOfPublisher.get(did);
            if (!held.contains(did) || readAt != null && now.isBefore(readAt.plus(NOT_ADOPTABLE_RECHECK_INTERVAL))) {
                continue;
            }
            reads++;
            String modeTag = candidate.getValue().mode().tagValue();
            try {
                ParticipantAdoption.Result result = adoption.adopt(publisherId, registryUrl, did, modeTag).block();
                if (result == ParticipantAdoption.Result.NOT_ADOPTABLE) {
                    notAdoptableOfPublisher.put(did, now);
                    continue;
                }
                notAdoptableOfPublisher.remove(did);
                if (result != ParticipantAdoption.Result.SKIPPED) {
                    attempts++;
                }
            } catch (AdoptionIncompleteException e) {
                attempts++;
                incomplete.put(did, e);
                warnIncomplete(publisherId, did, modeTag, e);
            } catch (RuntimeException e) {
                attempts++;
                log.warn("Participant {} listed by {}: adoption failed before anything was written, retried in the next run: {}",
                        did, publisherId, DownstreamError.reason(e), e);
                removal.count(publisherId, modeTag, RemovalAction.FAILED);
            }
        }
    }

    private void warnIncomplete(String publisherId, String did, String modeTag, RuntimeException e) {
        log.warn("Participant {} listed by {}: adoption incomplete, its membership grant may hold a duplicate of its "
                        + "unattributed credentials; completed in the next run (after a restart, revoked when the participant is removed): {}",
                did, publisherId, DownstreamError.reason(e), e);
        removal.count(publisherId, modeTag, RemovalAction.FAILED);
    }

    // configuration only, so it is checked even while runs are skipped: it is the start-up and refresh warning
    private void warnIfAdoptionRefused() {
        AdoptionPolicy policy = properties.adoption();
        List<Publisher> removalPublishers = properties.removalPublishers();
        List<Publisher> asking = removalPublishers.stream()
                .filter(publisher -> publisher.getOnParticipantRemoved().isAdoptUnattributed())
                .toList();
        AdoptionRefusal refusal = policy.state() == AdoptionPolicy.State.REFUSED
                ? new AdoptionRefusal(policy.reason(), properties.getDataspaceId(),
                removalPublishers.stream().map(Publisher::getId).toList(),
                asking.stream().collect(Collectors.toMap(Publisher::getId,
                        publisher -> {
                            String registryUrl = publisher.getOnParticipantRemoved().getRegistryUrl();
                            return registryUrl == null || registryUrl.isBlank();
                        },
                        (first, second) -> first, TreeMap::new)))
                : null;
        if (Objects.equals(refusal, loggedAdoptionRefusal)) {
            return;
        }
        loggedAdoptionRefusal = refusal;
        if (refusal == null) {
            return;
        }
        log.warn("adopt-unattributed of {} is ignored, no unattributed entry is adopted: {}; {} publishers announce "
                        + "participant removals ({}).",
                asking.stream().map(Publisher::getId).toList(), policy.reason().logText(), removalPublishers.size(),
                removalPublishers.stream().map(Publisher::getId).toList());
        asking.forEach(publisher -> removal.count(publisher.getId(),
                publisher.getOnParticipantRemoved().effectiveMode().tagValue(), RemovalAction.ADOPTION_REFUSED));
    }

    private void trip(String publisherId, RemovalMode publisherMode, Set<String> writeCandidates, int cap) {
        Breaker breaker = new Breaker(Set.copyOf(writeCandidates), cap);
        Breaker previous = breakers.put(publisherId, breaker);
        if (!breaker.equals(previous)) {
            log.error("Participant reconciliation of publisher {} tripped its breaker: {} participants to remove exceed "
                            + "max-removals-per-run {}, none is applied: {}", publisherId, writeCandidates.size(), cap,
                    sample(writeCandidates));
            removal.count(publisherId, publisherMode.tagValue(), RemovalAction.BREAKER);
        }
    }

    private static String sample(Set<String> dids) {
        List<String> sample = new TreeSet<>(dids).stream().limit(BREAKER_LOG_SAMPLE_SIZE).toList();
        String joined = String.join(", ", sample);
        return dids.size() > BREAKER_LOG_SAMPLE_SIZE ? joined + ", …" : joined;
    }

    private void clear(String publisherId) {
        if (breakers.remove(publisherId) != null) {
            log.info("Participant reconciliation of publisher {}: breaker cleared.", publisherId);
        }
    }

    private void skipWithoutRegistry(String publisherId, OnParticipantRemoved policy) {
        clear(publisherId);
        seen.remove(publisherId);
        notAdoptable.remove(publisherId);
        listingFailures.remove(publisherId);
        // not reconciled any more, so its last value would be exported for good
        Optional.ofNullable(heldGauges.get(publisherId)).ifPresent(gauge -> gauge.set(0));
        report.retainStandingDrift(publisherId, Set.of());
        if (!policy.configuresWrites()) {
            log.debug("Publisher {} has no registry-url and only reports; it is not reconciled.", publisherId);
            return;
        }
        String configured = policy.toString();
        if (!configured.equals(loggedMissingRegistry.put(publisherId, configured))) {
            log.error("Publisher {} configures a writing removal mode but no registry-url, so its removals act as report "
                    + "and it is not reconciled: {}.", publisherId, NO_REGISTRY_URL_CONSEQUENCE);
        }
    }

    private AtomicInteger heldGauge(String publisherId) {
        return heldGauges.computeIfAbsent(publisherId, id -> meterRegistry.gauge(HELD_METRIC_NAME,
                Tags.of(ParticipantRemoval.PUBLISHER_TAG, id), new AtomicInteger()));
    }

    // a publisher removed by a refresh neither keeps a breaker tripped nor counts or reports drift
    private void forgetAllBut(Set<String> publisherIds) {
        seen.keySet().retainAll(publisherIds);
        notAdoptable.keySet().retainAll(publisherIds);
        listingFailures.keySet().retainAll(publisherIds);
        incompleteAdoptions.forEach((id, incomplete) -> {
            if (!publisherIds.contains(id) && !incomplete.isEmpty()) {
                log.warn("Publisher {} no longer announces removals, so the incomplete adoptions of {} are not completed; "
                        + "resolve the duplicates of their unattributed credentials manually.", id, incomplete.keySet());
            }
        });
        incompleteAdoptions.keySet().retainAll(publisherIds);
        loggedMissingRegistry.keySet().retainAll(publisherIds);
        breakers.keySet().retainAll(publisherIds);
        heldGauges.forEach((id, gauge) -> {
            if (!publisherIds.contains(id)) {
                gauge.set(0);
            }
        });
        report.retainStandingDriftOf(publisherIds);
    }
}
