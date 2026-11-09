package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.bean.TirWatcherBeanFactory;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Runs the trust anchor watcher as a one-shot chain on its own scheduler: each next cycle is scheduled after the
 * then-current interval, so a refreshed interval applies from the next cycle on.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)
public class TirWatcherScheduler implements ApplicationEventListener<ServerStartupEvent> {

    private final TirWatcher watcher;
    private final DataSpaceEventsProperties properties;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    // guarded by this
    private boolean started;
    private boolean stopped;
    private ScheduledFuture<?> next;

    public TirWatcherScheduler(TirWatcher watcher, DataSpaceEventsProperties properties,
                               @Named(TirWatcherBeanFactory.TIR_WATCHER) TaskScheduler taskScheduler, Clock clock) {
        this.watcher = watcher;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    /**
     * The delay between two cycles as actually scheduled: the effective interval, capped like every scheduled delay.
     */
    static Duration interval(TirWatcherSettings settings) {
        return capped(settings.effectiveInterval());
    }

    @Override
    public synchronized void onApplicationEvent(ServerStartupEvent event) {
        if (started || stopped) {
            return;
        }
        started = true;
        Duration initialDelay = capped(properties.getTirWatcher().effectiveInitialDelay());
        watcher.scheduled(clock.instant().plus(initialDelay));
        next = taskScheduler.schedule(initialDelay, this::tick);
    }

    @PreDestroy
    synchronized void stop() {
        stopped = true;
        if (next != null) {
            next.cancel(false);
        }
    }

    private void tick() {
        try {
            watcher.cycle();
        } catch (Throwable t) {
            log.error("Trust anchor registry watcher cycle failed; the next cycle runs as scheduled.", t);
        } finally {
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        if (stopped) {
            return;
        }
        try {
            next = taskScheduler.schedule(interval(properties.getTirWatcher()), this::tick);
        } catch (RuntimeException e) {
            log.error("Could not schedule the next trust anchor registry watcher cycle; the registry is no longer watched.", e);
        }
    }

    private static Duration capped(Duration delay) {
        return delay.compareTo(DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY) > 0
                ? DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY : delay;
    }
}
