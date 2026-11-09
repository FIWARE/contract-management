package org.fiware.iam.dataspace;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.fiware.iam.til.TilCapability.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.fiware.iam.dataspace.ParticipantRemovedHealthIndicator.BREAKERS_DETAIL;
import static org.fiware.iam.dataspace.ParticipantRemovedHealthIndicator.MAX_REMOVALS_PER_RUN_DETAIL;
import static org.fiware.iam.dataspace.ParticipantRemovedHealthIndicator.NOT_PROBED_STATE;
import static org.fiware.iam.dataspace.ParticipantRemovedHealthIndicator.TIL_DETAIL;
import static org.fiware.iam.dataspace.ParticipantRemovedHealthIndicator.WRITE_CANDIDATES_DETAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks when the participant.removed handling reports DEGRADED: an unusable required TIL capability or a tripped breaker.
 */
class ParticipantRemovedHealthIndicatorTest {

    private static final String PUBLISHER = "did:web:anchor.org";
    private static final int CAP = 1;

    private TilCapabilities capabilities;
    private ParticipantReconciler reconciler;
    private ParticipantRemovedHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        capabilities = mock(TilCapabilities.class);
        reconciler = mock(ParticipantReconciler.class);
        when(capabilities.required()).thenReturn(Set.of(TilCapability.TYPED_SCOPES, TilCapability.SCOPES_ENDPOINT));
        when(reconciler.trippedBreakers()).thenReturn(Map.of());
        indicator = new ParticipantRemovedHealthIndicator(capabilities, reconciler);
    }

    static Stream<Arguments> capabilityStates() {
        return Stream.of(
                Arguments.of(State.SUPPORTED, "supported", HealthStatus.UP),
                Arguments.of(State.PENDING, "pending", HealthStatus.UP),
                Arguments.of(null, NOT_PROBED_STATE, HealthStatus.UP),
                Arguments.of(State.UNVERIFIED, "unverified", DataSpaceHealth.DEGRADED),
                Arguments.of(State.TOO_OLD, "too_old", DataSpaceHealth.DEGRADED));
    }

    @ParameterizedTest(name = "scopes-endpoint {0} -> {2}")
    @MethodSource("capabilityStates")
    void requiredCapabilityStateDecidesTheHealth(State scopesEndpoint, String detail, HealthStatus expected) {
        Map<TilCapability, State> states = new EnumMap<>(TilCapability.class);
        states.put(TilCapability.TYPED_SCOPES, State.SUPPORTED);
        if (scopesEndpoint != null) {
            states.put(TilCapability.SCOPES_ENDPOINT, scopesEndpoint);
        }
        // an unrequired capability never degrades
        states.put(TilCapability.UNSCOPED, State.TOO_OLD);
        when(capabilities.state()).thenReturn(states);

        HealthResult result = indicator.result();

        assertEquals(expected, result.getStatus());
        assertEquals(Map.of(TilCapability.TYPED_SCOPES.getId(), "supported", TilCapability.SCOPES_ENDPOINT.getId(), detail),
                details(result).get(TIL_DETAIL));
    }

    @Test
    void trippedBreakerDegrades() {
        when(capabilities.state()).thenReturn(Map.of(TilCapability.TYPED_SCOPES, State.SUPPORTED,
                TilCapability.SCOPES_ENDPOINT, State.SUPPORTED));
        when(reconciler.trippedBreakers()).thenReturn(Map.of(PUBLISHER,
                new ParticipantReconciler.Breaker(Set.of("did:web:a.org", "did:web:b.org"), CAP)));

        HealthResult result = indicator.result();

        assertEquals(DataSpaceHealth.DEGRADED, result.getStatus());
        assertEquals(Map.of(PUBLISHER, Map.of(WRITE_CANDIDATES_DETAIL, 2, MAX_REMOVALS_PER_RUN_DETAIL, CAP)),
                details(result).get(BREAKERS_DETAIL));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(HealthResult result) {
        return (Map<String, Object>) result.getDetails();
    }
}
