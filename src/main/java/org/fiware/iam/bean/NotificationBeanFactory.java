package org.fiware.iam.bean;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.concurrent.ScheduledExecutorService;

@Factory
public class NotificationBeanFactory {

    @Bean
    @Singleton
    @Named("notification-subscriber")
    public TaskScheduler notificationTaskScheduler(@Named("notification-subscriber") ScheduledExecutorService executorService) {
        return new ScheduledExecutorTaskScheduler(executorService);
    }
}