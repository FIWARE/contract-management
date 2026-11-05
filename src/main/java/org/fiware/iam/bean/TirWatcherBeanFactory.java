package org.fiware.iam.bean;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Own scheduler of the trust anchor watcher, so a slow registry enumeration cannot delay other scheduled work.
 */
@Factory
public class TirWatcherBeanFactory {

    public static final String TIR_WATCHER = "tir-watcher";

    @Bean
    @Singleton
    @Named(TIR_WATCHER)
    @Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)
    public TaskScheduler tirWatcherTaskScheduler(@Named(TIR_WATCHER) ScheduledExecutorService executorService) {
        return new ScheduledExecutorTaskScheduler(executorService);
    }
}
