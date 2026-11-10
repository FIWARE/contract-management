package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.bean.ParticipantReconcilerBeanFactory;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Runs the participant reconciliation as a one-shot chain on its own scheduler: each next run is scheduled the then-current
 * interval after the previous one ended, so a refreshed interval applies from the next run on.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class ParticipantReconcilerScheduler implements ApplicationEventListener<ServerStartupEvent> {

    private final ParticipantReconciler reconciler;
    private final DataSpaceEventsProperties properties;
    private final TaskScheduler taskScheduler;

    // guarded by this
    private boolean started;
    private boolean stopped;
    private ScheduledFuture<?> next;

    public ParticipantReconcilerScheduler(ParticipantReconciler reconciler, DataSpaceEventsProperties properties,
                                          @Named(ParticipantReconcilerBeanFactory.PARTICIPANT_RECONCILER) TaskScheduler taskScheduler) {
        this.reconciler = reconciler;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public synchronized void onApplicationEvent(ServerStartupEvent event) {
        if (started || stopped) {
            return;
        }
        started = true;
        next = taskScheduler.schedule(capped(properties.getParticipantRemoved().getReconcile().effectiveInitialDelay()),
                this::tick);
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
            reconciler.run();
        } catch (Throwable t) {
            log.error("Participant reconciliation failed; the next run happens as scheduled.", t);
        } finally {
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        if (stopped) {
            return;
        }
        try {
            next = taskScheduler.schedule(capped(properties.getParticipantRemoved().getReconcile().effectiveInterval()),
                    this::tick);
        } catch (RuntimeException e) {
            log.error("Could not schedule the next participant reconciliation; removed participants are no longer reconciled.", e);
        }
    }

    private static Duration capped(Duration delay) {
        return delay.compareTo(DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY) > 0
                ? DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY : delay;
    }
}
