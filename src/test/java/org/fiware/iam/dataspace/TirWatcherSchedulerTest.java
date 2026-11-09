package org.fiware.iam.dataspace;

import io.micronaut.runtime.server.event.ServerStartupEvent;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_INTERVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks the one-shot chain of the trust anchor watcher: initial delay, rescheduling after every outcome, refreshed intervals.
 */
class TirWatcherSchedulerTest {

    private static final Instant START = Instant.parse("2026-11-09T12:00:00Z");
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);
    private static final Duration INTERVAL = Duration.ofMinutes(2);

    private TirWatcher watcher;
    private DataSpaceEventsProperties properties;
    private RecordingTaskScheduler taskScheduler;
    private TirWatcherScheduler scheduler;

    @BeforeEach
    void setUp() {
        watcher = mock(TirWatcher.class);
        properties = new DataSpaceEventsProperties();
        properties.getTirWatcher().setInitialDelay(INITIAL_DELAY);
        properties.getTirWatcher().setInterval(INTERVAL);
        taskScheduler = new RecordingTaskScheduler();
        scheduler = new TirWatcherScheduler(watcher, properties, taskScheduler, new MutableClock(START));
    }

    @Test
    void firstCycleRunsAfterTheInitialDelay() {
        start();
        start();

        List<RecordingTaskScheduler.Task> pending = taskScheduler.pending();
        assertEquals(1, pending.size(), "a second startup event schedules nothing");
        assertEquals(INITIAL_DELAY, pending.getFirst().delay());
        verify(watcher).scheduled(START.plus(INITIAL_DELAY));
        verify(watcher, times(0)).cycle();

        taskScheduler.runNext();
        verify(watcher).cycle();
    }

    static Stream<Arguments> cycleResults() {
        return Stream.of(
                Arguments.of("success", (Supplier<TirWatcher.Outcome>) () -> TirWatcher.Outcome.COMPLETE),
                Arguments.of("runtime exception", (Supplier<TirWatcher.Outcome>) () -> {
                    throw new IllegalStateException("broken");
                }),
                Arguments.of("error", (Supplier<TirWatcher.Outcome>) () -> {
                    throw new AssertionError("broken");
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cycleResults")
    void reschedulesAfterEveryCycle(String description, Supplier<TirWatcher.Outcome> result) {
        when(watcher.cycle()).thenAnswer(invocation -> result.get());
        start();

        taskScheduler.runNext();
        taskScheduler.runNext();

        verify(watcher, times(2)).cycle();
        assertEquals(List.of(INTERVAL), delays());
    }

    static Stream<Arguments> refreshedIntervals() {
        Duration changed = Duration.ofSeconds(30);
        return Stream.of(
                Arguments.of("changed", changed, changed),
                Arguments.of("zero", Duration.ZERO, DEFAULT_TIR_WATCHER_INTERVAL),
                Arguments.of("negative", Duration.ofMinutes(-1), DEFAULT_TIR_WATCHER_INTERVAL),
                Arguments.of("emptied", null, DEFAULT_TIR_WATCHER_INTERVAL),
                Arguments.of("oversized", Duration.ofDays(365), DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refreshedIntervals")
    void appliesTheIntervalCurrentAtTheEndOfEachCycle(String description, Duration refreshed, Duration expected) {
        start();
        taskScheduler.runNext();
        assertEquals(List.of(INTERVAL), delays());

        properties.getTirWatcher().setInterval(refreshed);
        taskScheduler.runNext();
        assertEquals(List.of(expected), delays());

        taskScheduler.runNext();
        assertEquals(List.of(expected), delays(), "the chain continues");
        verify(watcher, times(3)).cycle();
    }

    @Test
    void stopCancelsTheChain() {
        start();
        scheduler.stop();

        assertTrue(taskScheduler.pending().isEmpty());
    }

    @Test
    void aCycleRunningDuringStopSchedulesNoFurtherCycle() {
        when(watcher.cycle()).thenAnswer(invocation -> {
            scheduler.stop();
            return TirWatcher.Outcome.COMPLETE;
        });
        start();

        taskScheduler.runNext();

        assertTrue(taskScheduler.pending().isEmpty());
    }

    private void start() {
        scheduler.onApplicationEvent(mock(ServerStartupEvent.class));
    }

    private List<Duration> delays() {
        return taskScheduler.pending().stream().map(RecordingTaskScheduler.Task::delay).toList();
    }
}
