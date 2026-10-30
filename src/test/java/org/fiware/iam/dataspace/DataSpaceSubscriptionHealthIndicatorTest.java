package org.fiware.iam.dataspace;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.fiware.iam.dataspace.SubscriptionStateStore.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.fiware.iam.dataspace.DataSpaceSubscriptionHealthIndicator.DEGRADED;
import static org.fiware.iam.dataspace.DataSpaceSubscriptionHealthIndicator.EXPIRED;
import static org.fiware.iam.dataspace.DataSpaceSubscriptionHealthIndicator.EXPIRES_AT_DETAIL;
import static org.fiware.iam.dataspace.DataSpaceSubscriptionHealthIndicator.REASON_DETAIL;
import static org.fiware.iam.dataspace.DataSpaceSubscriptionHealthIndicator.STATE_DETAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Checks the status and details the indicator derives from the subscription states, filled directly into the store.
 */
class DataSpaceSubscriptionHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant VALID_UNTIL = NOW.plus(Duration.ofHours(1));
    private static final String PUBLISHER_ID = "did:web:provider.org";
    private static final String SKIPPED_ID = "did:web:skipped.org";
    private static final String SKIP_REASON = "no types configured";

    private final SubscriptionStateStore store = new SubscriptionStateStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final DataSpaceSubscriptionHealthIndicator indicator = new DataSpaceSubscriptionHealthIndicator(store, clock);

    @Test
    void degradedBeforeTheFirstReconcile() {
        assertEquals(DEGRADED, indicator.result().getStatus());
    }

    @Test
    void upWithoutPublishers() {
        store.markReconciled();

        HealthResult result = indicator.result();

        assertEquals(HealthStatus.UP, result.getStatus());
        assertEquals(DataSpaceSubscriptionHealthIndicator.NAME, result.getName());
        assertEquals(Map.of(), result.getDetails());
    }

    static Stream<Arguments> states() {
        return Stream.of(
                Arguments.of("pending", Phase.PENDING, Duration.ZERO, DEGRADED, Phase.PENDING.name()),
                Arguments.of("registered", Phase.REGISTERED, Duration.ZERO, HealthStatus.UP, Phase.REGISTERED.name()),
                Arguments.of("registered just before expiry", Phase.REGISTERED, Duration.ofHours(1).minusMillis(1),
                        HealthStatus.UP, Phase.REGISTERED.name()),
                Arguments.of("registered at expiry", Phase.REGISTERED, Duration.ofHours(1), DEGRADED, EXPIRED),
                Arguments.of("conflict", Phase.CONFLICT, Duration.ZERO, HealthStatus.UP, Phase.CONFLICT.name()),
                Arguments.of("conflict past re-registration", Phase.CONFLICT, Duration.ofHours(2), DEGRADED, EXPIRED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("states")
    void statusFollowsThePublisherState(String description, Phase phase, Duration elapsed, HealthStatus expectedStatus,
                                        String expectedState) {
        PublisherState state = stateIn(phase);
        store.put(PUBLISHER_ID, state);
        store.put(SKIPPED_ID, PublisherState.skipped(SKIP_REASON));
        store.markReconciled();
        clock.advance(elapsed);

        HealthResult result = indicator.result();

        assertEquals(expectedStatus, result.getStatus());
        assertEquals(List.of(PUBLISHER_ID, SKIPPED_ID), List.copyOf(details(result).keySet()));
        Map<String, Object> publisherDetails = details(result).get(PUBLISHER_ID);
        assertEquals(expectedState, publisherDetails.get(STATE_DETAIL));
        assertEquals(phase == Phase.PENDING ? null : VALID_UNTIL.toString(), publisherDetails.get(EXPIRES_AT_DETAIL));
        assertEquals(Map.of(STATE_DETAIL, Phase.SKIPPED.name(), REASON_DETAIL, SKIP_REASON), details(result).get(SKIPPED_ID));
    }

    @Test
    void skippedPublishersDoNotAffectTheStatus() {
        store.put(SKIPPED_ID, PublisherState.skipped(SKIP_REASON));
        store.markReconciled();

        assertEquals(HealthStatus.UP, indicator.result().getStatus());
    }

    @Test
    void degradedIsNeverDown() {
        store.put(PUBLISHER_ID, stateIn(Phase.PENDING));
        store.markReconciled();

        HealthResult result = Mono.from(indicator.getResult()).block();

        assertEquals(DEGRADED, result.getStatus());
        assertNotEquals(HealthStatus.DOWN, result.getStatus());
        assertEquals(1, DEGRADED.compareTo(HealthStatus.UP), "DEGRADED ranks above UP in the aggregate");
        assertEquals(-1, DEGRADED.compareTo(HealthStatus.DOWN), "DOWN still wins the aggregate");
    }

    private static PublisherState stateIn(Phase phase) {
        PublisherState state = PublisherState.pending(null);
        switch (phase) {
            case REGISTERED -> state.registered("subscription-1", VALID_UNTIL);
            case CONFLICT -> state.conflict(null, VALID_UNTIL);
            default -> {
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> details(HealthResult result) {
        return (Map<String, Map<String, Object>>) result.getDetails();
    }
}
