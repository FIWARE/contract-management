package org.fiware.iam.dataspace;

import io.micronaut.runtime.server.event.ServerStartupEvent;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_RECONCILE_INTERVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Checks the one-shot chain of the participant reconciliation: initial delay, rescheduling after every run, refreshed intervals.
 */
class ParticipantReconcilerSchedulerTest {

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);
    private static final Duration INTERVAL = Duration.ofMinutes(2);

    private ParticipantReconciler reconciler;
    private DataSpaceEventsProperties properties;
    private RecordingTaskScheduler taskScheduler;
    private ParticipantReconcilerScheduler scheduler;

    @BeforeEach
    void setUp() {
        reconciler = mock(ParticipantReconciler.class);
        properties = new DataSpaceEventsProperties();
        properties.getParticipantRemoved().getReconcile().setInitialDelay(INITIAL_DELAY);
        properties.getParticipantRemoved().getReconcile().setInterval(INTERVAL);
        taskScheduler = new RecordingTaskScheduler();
        scheduler = new ParticipantReconcilerScheduler(reconciler, properties, taskScheduler);
    }

    @Test
    void firstRunHappensAfterTheInitialDelay() {
        start();
        start();

        List<RecordingTaskScheduler.Task> pending = taskScheduler.pending();
        assertEquals(1, pending.size(), "a second startup event schedules nothing");
        assertEquals(INITIAL_DELAY, pending.getFirst().delay());
        verify(reconciler, never()).run();

        taskScheduler.runNext();
        verify(reconciler).run();
    }

    static Stream<Arguments> runFailures() {
        return Stream.of(
                Arguments.of("success", null),
                Arguments.of("runtime exception", new IllegalStateException("broken")),
                Arguments.of("error", new AssertionError("broken")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runFailures")
    void reschedulesAfterEveryRun(String description, Throwable failure) {
        if (failure != null) {
            doThrow(failure).when(reconciler).run();
        }
        start();

        taskScheduler.runNext();
        taskScheduler.runNext();

        verify(reconciler, times(2)).run();
        assertEquals(List.of(INTERVAL), delays());
    }

    static Stream<Arguments> refreshedIntervals() {
        Duration changed = Duration.ofSeconds(30);
        return Stream.of(
                Arguments.of("changed", changed, changed),
                Arguments.of("zero", Duration.ZERO, DEFAULT_RECONCILE_INTERVAL),
                Arguments.of("negative", Duration.ofMinutes(-1), DEFAULT_RECONCILE_INTERVAL),
                Arguments.of("emptied", null, DEFAULT_RECONCILE_INTERVAL),
                Arguments.of("oversized", Duration.ofDays(365), DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refreshedIntervals")
    void appliesTheIntervalCurrentAtTheEndOfEachRun(String description, Duration refreshed, Duration expected) {
        start();
        taskScheduler.runNext();
        assertEquals(List.of(INTERVAL), delays());

        properties.getParticipantRemoved().getReconcile().setInterval(refreshed);
        taskScheduler.runNext();
        assertEquals(List.of(expected), delays());

        taskScheduler.runNext();
        assertEquals(List.of(expected), delays(), "the chain continues");
        verify(reconciler, times(3)).run();
    }

    @Test
    void stopCancelsTheChain() {
        start();
        scheduler.stop();

        assertTrue(taskScheduler.pending().isEmpty());
    }

    @Test
    void aRunDuringStopSchedulesNoFurtherRun() {
        doAnswer(invocation -> {
            scheduler.stop();
            return null;
        }).when(reconciler).run();
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
