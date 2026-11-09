package org.fiware.iam.til;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the trusted-issuers-list supports of the capabilities the participant-removed handling needs, decided by probes.
 * <p>
 * UNVERIFIED and PENDING capabilities are re-probed on use, at most once per {@link #PROBE_RETRY_INTERVAL}; TOO_OLD is
 * final until a refresh. The required set follows the current, refreshable configuration.
 */
@Singleton
@Slf4j
public class TilCapabilities implements ApplicationEventListener<RefreshEvent> {

    /**
     * Upper bound of one whole probe, shorter than the client's read timeout so start-up never waits long for a hanging list.
     */
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration PROBE_RETRY_INTERVAL = Duration.ofSeconds(30);

    private final TrustedIssuersListAdapter adapter;
    private final DataSpaceEventsProperties properties;
    private final Clock clock;
    private final Scheduler timeoutScheduler;

    // guarded by this
    private final Map<TilCapability, TilCapability.State> states = new EnumMap<>(TilCapability.class);
    private final Map<TilCapability, Instant> lastProbes = new EnumMap<>(TilCapability.class);
    // a probe answered after a refresh describes the list before it, so its result is dropped
    private long generation;

    @Inject
    public TilCapabilities(TrustedIssuersListAdapter adapter, DataSpaceEventsProperties properties, Clock clock) {
        this(adapter, properties, clock, Schedulers.parallel());
    }

    TilCapabilities(TrustedIssuersListAdapter adapter, DataSpaceEventsProperties properties, Clock clock,
            Scheduler timeoutScheduler) {
        this.adapter = adapter;
        this.properties = properties;
        this.clock = clock;
        this.timeoutScheduler = timeoutScheduler;
        for (TilCapability capability : TilCapability.values()) {
            states.put(capability, TilCapability.State.UNVERIFIED);
        }
    }

    /**
     * The capabilities the current configuration needs: unscoped addressing only while adoption is allowed.
     */
    public Set<TilCapability> required() {
        Set<TilCapability> required = EnumSet.of(TilCapability.TYPED_SCOPES, TilCapability.SCOPES_ENDPOINT);
        if (properties.adoption().isAllowed()) {
            required.add(TilCapability.UNSCOPED);
        }
        return required;
    }

    /**
     * The last known state of every capability, required or not.
     */
    public synchronized Map<TilCapability, TilCapability.State> state() {
        return Map.copyOf(states);
    }

    /**
     * Re-probe the required capabilities that are undecided and not probed within the retry interval, concurrently.
     * Never fails; emits the resulting state of the required ones.
     */
    public Mono<Map<TilCapability, TilCapability.State>> probeRequired() {
        Set<TilCapability> required = required();
        List<Mono<Void>> probes = required.stream()
                .filter(this::claimProbe)
                .map(this::probe)
                .toList();
        return Flux.merge(probes)
                .then(Mono.fromSupplier(() -> requiredStates(required)));
    }

    /**
     * Complete if every required capability is SUPPORTED or PENDING, re-probing undecided ones first. Fails with a
     * {@link TrustedIssuersException}: {@code TIL_TOO_OLD} if one is TOO_OLD, else {@code TIL_CAPABILITY_UNVERIFIED}.
     */
    public Mono<Void> requireSupported() {
        return probeRequired().flatMap(required -> {
            List<String> tooOld = idsIn(required, TilCapability.State.TOO_OLD);
            if (!tooOld.isEmpty()) {
                return Mono.error(new TrustedIssuersException(FailureReason.TIL_TOO_OLD,
                        "The trusted-issuers-list is too old: it lacks %s.".formatted(tooOld)));
            }
            List<String> unverified = idsIn(required, TilCapability.State.UNVERIFIED);
            if (!unverified.isEmpty()) {
                return Mono.error(new TrustedIssuersException(FailureReason.TIL_CAPABILITY_UNVERIFIED,
                        "The trusted-issuers-list could not be checked for %s.".formatted(unverified)));
            }
            return Mono.empty();
        });
    }

    /**
     * A refresh may point to an upgraded list: TOO_OLD capabilities become UNVERIFIED and are probed on next use.
     */
    @Override
    public synchronized void onApplicationEvent(RefreshEvent event) {
        generation++;
        states.replaceAll((capability, state) -> state == TilCapability.State.TOO_OLD ? TilCapability.State.UNVERIFIED : state);
        lastProbes.clear();
    }

    private synchronized boolean claimProbe(TilCapability capability) {
        TilCapability.State state = states.get(capability);
        if (state != TilCapability.State.UNVERIFIED && state != TilCapability.State.PENDING) {
            return false;
        }
        Instant now = clock.instant();
        Instant last = lastProbes.get(capability);
        if (last != null && now.isBefore(last.plus(PROBE_RETRY_INTERVAL))) {
            return false;
        }
        lastProbes.put(capability, now);
        return true;
    }

    private Mono<Void> probe(TilCapability capability) {
        long probedGeneration = currentGeneration();
        return Mono.defer(probeOf(capability))
                .timeout(PROBE_TIMEOUT, timeoutScheduler)
                .onErrorReturn(TilCapability.State.UNVERIFIED)
                .defaultIfEmpty(TilCapability.State.UNVERIFIED)
                .doOnNext(state -> record(capability, state, probedGeneration))
                .then();
    }

    private Supplier<Mono<TilCapability.State>> probeOf(TilCapability capability) {
        return switch (capability) {
            case TYPED_SCOPES -> adapter::probeTypedScopes;
            case SCOPES_ENDPOINT -> adapter::probeScopesEndpoint;
            case UNSCOPED -> adapter::probeUnscoped;
        };
    }

    private synchronized void record(TilCapability capability, TilCapability.State state, long probedGeneration) {
        if (probedGeneration != generation) {
            return;
        }
        TilCapability.State previous = states.put(capability, state);
        if (state == TilCapability.State.TOO_OLD && previous != TilCapability.State.TOO_OLD) {
            log.error("The trusted-issuers-list does not support {}; the participant-removed handling cannot use it until "
                    + "the list is upgraded and the configuration refreshed or the service restarted.", capability.getId());
        }
    }

    private synchronized long currentGeneration() {
        return generation;
    }

    private synchronized Map<TilCapability, TilCapability.State> requiredStates(Set<TilCapability> required) {
        Map<TilCapability, TilCapability.State> result = new EnumMap<>(TilCapability.class);
        required.forEach(capability -> result.put(capability, states.get(capability)));
        return result;
    }

    private static List<String> idsIn(Map<TilCapability, TilCapability.State> states, TilCapability.State state) {
        return states.entrySet().stream()
                .filter(entry -> entry.getValue() == state)
                .map(entry -> entry.getKey().getId())
                .toList();
    }
}
