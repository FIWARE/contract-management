package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.TirEnumerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.NOT_SCHEDULED_STATE;
import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.NO_SUCCESSFUL_CYCLE_REASON;
import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.REASON_DETAIL;
import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.SCHEDULED_STATE;
import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.SNAPSHOT_SIZE_DETAIL;
import static org.fiware.iam.dataspace.TirWatcherHealthIndicator.STATE_DETAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks when the trust anchor watcher reports DEGRADED: active alerts and no successful cycle for three intervals,
 * never during the initial delay, and without waiting for a cycle blocked on the registry.
 */
class TirWatcherHealthIndicatorTest {

    private static final Instant START = Instant.parse("2026-11-09T12:00:00Z");
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final Duration BLOCKED_CALL_TIMEOUT = Duration.ofSeconds(2);
    private static final int CAP_TRIPPED_BY_ANY_REMOVAL = 0;
    private static final String A = "did:web:a.org";
    private static final String B = "did:web:b.org";
    // a registry result of null stands for an incomplete enumeration
    private static final Set<String> ERROR = null;

    private TrustedIssuersRegistryReader reader;
    private DataSpaceEventsProperties properties;
    private MutableClock clock;
    private RecordingTaskScheduler taskScheduler;
    private TirWatcher watcher;
    private TirWatcherHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        reader = mock(TrustedIssuersRegistryReader.class);
        SubscriptionRegistry subscriptionRegistry = mock(SubscriptionRegistry.class);
        properties = new DataSpaceEventsProperties();
        properties.getTirWatcher().setInitialDelay(INITIAL_DELAY);
        properties.getTirWatcher().setInterval(INTERVAL);
        clock = new MutableClock(START);
        taskScheduler = new RecordingTaskScheduler();
        watcher = new TirWatcher(reader, mock(OwnEventPublisher.class), subscriptionRegistry, properties, clock,
                Optional.of(new SimpleMeterRegistry()));
        indicator = new TirWatcherHealthIndicator(watcher, properties, clock);
    }

    @Test
    void upAndNotScheduledBeforeStartup() {
        HealthResult result = indicator.result();

        assertEquals(HealthStatus.UP, result.getStatus());
        assertEquals(NOT_SCHEDULED_STATE, details(result).get(STATE_DETAIL));
    }

    /**
     * {@code checkAt} is measured from startup; {@code refreshedInterval} replaces the interval before the check.
     */
    record Case(String name, Duration initialDelay, int cap, List<Set<String>> registry, Duration checkAt,
                Optional<Duration> refreshedInterval, HealthStatus expected, String state, String reason) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        DataSpaceEventsProperties.TirWatcherSettings defaults = new DataSpaceEventsProperties.TirWatcherSettings();
        int cap = defaults.getMaxRemovalsPerCycle();
        Duration due = INITIAL_DELAY;
        Duration stale = INTERVAL.multipliedBy(TirWatcherHealthIndicator.STALE_INTERVALS);
        Duration longDelay = Duration.ofMinutes(5);
        return Stream.of(
                new Case("during the initial delay", INITIAL_DELAY, cap, List.of(), Duration.ofSeconds(10),
                        Optional.empty(), HealthStatus.UP, SCHEDULED_STATE, null),
                new Case("initial delay longer than three intervals", longDelay, cap, List.of(), Duration.ofMinutes(4),
                        Optional.empty(), HealthStatus.UP, SCHEDULED_STATE, null),
                new Case("three intervals past the first due cycle", INITIAL_DELAY, cap, List.of(), due.plus(stale),
                        Optional.empty(), HealthStatus.UP, SCHEDULED_STATE, null),
                new Case("more than three intervals past the first due cycle", INITIAL_DELAY, cap, List.of(),
                        due.plus(stale).plusSeconds(1), Optional.empty(), DataSpaceHealth.DEGRADED, SCHEDULED_STATE,
                        NO_SUCCESSFUL_CYCLE_REASON),
                new Case("refreshed interval PT0S uses the default", INITIAL_DELAY, cap, List.of(), due.plusMinutes(2),
                        Optional.of(Duration.ZERO), HealthStatus.UP, SCHEDULED_STATE, null),
                new Case("only incomplete cycles", INITIAL_DELAY, cap, listOf(ERROR, ERROR, ERROR, ERROR),
                        due.plus(stale).plusSeconds(1), Optional.empty(), DataSpaceHealth.DEGRADED, "incomplete",
                        NO_SUCCESSFUL_CYCLE_REASON),
                new Case("incomplete cycles within three intervals of a success", INITIAL_DELAY, cap,
                        listOf(Set.of(A), ERROR, ERROR), due.plusMinutes(2), Optional.empty(), HealthStatus.UP,
                        "incomplete", null),
                new Case("incomplete cycles for more than three intervals after a success", INITIAL_DELAY, cap,
                        listOf(Set.of(A), ERROR, ERROR, ERROR, ERROR), due.plusMinutes(4),
                        Optional.empty(), DataSpaceHealth.DEGRADED, "incomplete", NO_SUCCESSFUL_CYCLE_REASON),
                new Case("initial snapshot", INITIAL_DELAY, cap, List.of(Set.of(A)), due, Optional.empty(),
                        HealthStatus.UP, "initial", null),
                new Case("complete cycle", INITIAL_DELAY, cap, List.of(Set.of(A), Set.of(A, B)), due.plusMinutes(1),
                        Optional.empty(), HealthStatus.UP, "complete", null),
                new Case("registry turned empty", INITIAL_DELAY, cap, List.of(Set.of(A, B), Set.of()),
                        due.plusMinutes(1), Optional.empty(), DataSpaceHealth.DEGRADED, "empty", "tir_empty"),
                new Case("circuit breaker", INITIAL_DELAY, CAP_TRIPPED_BY_ANY_REMOVAL, List.of(Set.of(A, B), Set.of(A)),
                        due.plusMinutes(1), Optional.empty(), DataSpaceHealth.DEGRADED, "breaker", "tir_mass_removal"),
                new Case("alert cleared", INITIAL_DELAY, cap, List.of(Set.of(A, B), Set.of(), Set.of(A, B)),
                        due.plusMinutes(2), Optional.empty(), HealthStatus.UP, "complete", null)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void reportsHealth(Case scenario) {
        properties.getTirWatcher().setInitialDelay(scenario.initialDelay);
        properties.getTirWatcher().setMaxRemovalsPerCycle(scenario.cap);
        Iterator<Set<String>> results = scenario.registry.iterator();
        when(reader.enumerate()).thenAnswer(invocation -> {
            Set<String> result = results.next();
            if (result == null) {
                throw new TirEnumerationException("unreachable", 1, null, null);
            }
            return result;
        });
        startScheduler();
        for (int i = 0; i < scenario.registry.size(); i++) {
            RecordingTaskScheduler.Task next = taskScheduler.pending().getFirst();
            clock.advance(next.delay());
            taskScheduler.run(next);
        }
        scenario.refreshedInterval.ifPresent(properties.getTirWatcher()::setInterval);
        clock.set(START.plus(scenario.checkAt));

        HealthResult result = indicator.result();

        assertEquals(scenario.expected, result.getStatus());
        assertEquals(scenario.state, details(result).get(STATE_DETAIL));
        assertEquals(scenario.reason, details(result).get(REASON_DETAIL));
    }

    @Test
    void answersWhileACycleIsBlockedOnTheRegistry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.enumerate()).thenReturn(Set.of(A)).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return Set.of(A);
        });
        startScheduler();
        watcher.cycle();
        CompletableFuture<TirWatcher.Outcome> blocked = CompletableFuture.supplyAsync(watcher::cycle);
        try {
            assertTrue(entered.await(BLOCKED_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "cycle reached the registry");

            HealthResult result = assertTimeoutPreemptively(BLOCKED_CALL_TIMEOUT, indicator::result);

            assertEquals(HealthStatus.UP, result.getStatus());
            assertEquals("initial", details(result).get(STATE_DETAIL));
            assertEquals(1, details(result).get(SNAPSHOT_SIZE_DETAIL));
        } finally {
            release.countDown();
        }
        assertEquals(TirWatcher.Outcome.COMPLETE, blocked.get(BLOCKED_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
    }

    private void startScheduler() {
        new TirWatcherScheduler(watcher, properties, taskScheduler, clock).onApplicationEvent(mock(ServerStartupEvent.class));
    }

    // List.of rejects the null that stands for an incomplete enumeration
    @SafeVarargs
    private static List<Set<String>> listOf(Set<String>... results) {
        return new ArrayList<>(Arrays.asList(results));
    }

    private static Map<?, ?> details(HealthResult result) {
        return (Map<?, ?>) result.getDetails();
    }
}
