package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Health of the participant.removed handling: DEGRADED while a required TIL capability is UNVERIFIED or TOO_OLD or a
 * breaker is tripped, else UP. Reads only published state, so it never waits for a probe or a sweep; never DOWN.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
@RequiredArgsConstructor
public class ParticipantRemovedHealthIndicator implements HealthIndicator {

    static final String NAME = "dataspace-participant-removed";
    static final String TIL_DETAIL = "til";
    static final String BREAKERS_DETAIL = "breakers";
    static final String WRITE_CANDIDATES_DETAIL = "writeCandidates";
    static final String MAX_REMOVALS_PER_RUN_DETAIL = "maxRemovalsPerRun";
    // a capability no probe has answered yet; it is probed before the first event or sweep needs it
    static final String NOT_PROBED_STATE = "not_probed";

    private final TilCapabilities capabilities;
    private final ParticipantReconciler reconciler;

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromSupplier(this::result);
    }

    HealthResult result() {
        Map<TilCapability, TilCapability.State> states = capabilities.state();
        Map<String, String> til = new TreeMap<>();
        boolean degraded = false;
        for (TilCapability capability : capabilities.required()) {
            TilCapability.State state = states.get(capability);
            til.put(capability.getId(), state == null ? NOT_PROBED_STATE : state.name().toLowerCase(Locale.ROOT));
            degraded |= state == TilCapability.State.UNVERIFIED || state == TilCapability.State.TOO_OLD;
        }
        Map<String, Object> breakers = new TreeMap<>();
        reconciler.trippedBreakers().forEach((publisherId, breaker) -> breakers.put(publisherId, Map.of(
                WRITE_CANDIDATES_DETAIL, breaker.writeCandidates().size(),
                MAX_REMOVALS_PER_RUN_DETAIL, breaker.maxRemovalsPerRun())));
        degraded |= !breakers.isEmpty();

        Map<String, Object> details = Map.of(TIL_DETAIL, til, BREAKERS_DETAIL, breakers);
        return HealthResult.builder(NAME, degraded ? DataSpaceHealth.DEGRADED : HealthStatus.UP).details(details).build();
    }
}
