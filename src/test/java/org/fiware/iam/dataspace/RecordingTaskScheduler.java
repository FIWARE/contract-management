package org.fiware.iam.dataspace;

import io.micronaut.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task scheduler for tests that only records one-shot tasks; they run when the test says so, in scheduling order.
 */
final class RecordingTaskScheduler implements TaskScheduler {

    private final List<Task> tasks = new ArrayList<>();

    /**
     * A recorded task; cancelling it removes it from the pending ones.
     */
    static final class Task implements ScheduledFuture<Object> {
        private final Duration delay;
        private final Runnable command;
        private boolean cancelled;
        private boolean done;

        private Task(Duration delay, Runnable command) {
            this.delay = delay;
            this.command = command;
        }

        Duration delay() {
            return delay;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = !done;
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    synchronized List<Task> pending() {
        return tasks.stream().filter(task -> !task.isDone()).toList();
    }

    /**
     * Runs the oldest pending task and returns it.
     */
    Task runNext() {
        Task task;
        synchronized (this) {
            task = pending().stream().findFirst().orElseThrow(() -> new AssertionError("No pending task."));
        }
        return run(task);
    }

    /**
     * Runs the given pending task and returns it.
     */
    Task run(Task task) {
        synchronized (this) {
            if (task.isDone()) {
                throw new AssertionError("Task is not pending.");
            }
            task.done = true;
        }
        task.command.run();
        return task;
    }

    @Override
    public synchronized ScheduledFuture<?> schedule(Duration delay, Runnable command) {
        Task task = new Task(delay, command);
        tasks.add(task);
        return task;
    }

    @Override
    public ScheduledFuture<?> schedule(String cron, Runnable command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(String cron, Callable<V> command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Duration delay, Callable<V> callable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Duration initialDelay, Duration period, Runnable command) {
        throw new UnsupportedOperationException();
    }
}
