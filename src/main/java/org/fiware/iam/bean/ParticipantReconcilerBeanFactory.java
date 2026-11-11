package org.fiware.iam.bean;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.util.concurrent.ExecutorService;

/**
 * Own scheduler of the participant.removed reconciliation, so slow registries or a slow TIL cannot delay other scheduled work.
 */
@Factory
public class ParticipantReconcilerBeanFactory {

    public static final String PARTICIPANT_RECONCILER = "participant-reconciler";

    @Bean
    @Singleton
    @Named(PARTICIPANT_RECONCILER)
    @Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
    public TaskScheduler participantReconcilerTaskScheduler(@Named(PARTICIPANT_RECONCILER) ExecutorService executorService) {
        return new ScheduledExecutorTaskScheduler(executorService);
    }
}
