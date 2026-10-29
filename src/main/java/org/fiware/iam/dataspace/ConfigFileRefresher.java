package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.scheduling.io.watch.event.FileChangedEvent;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Refreshes the configuration when the watched directory changes, like a POST to /refresh does.
 * Reacts to any change below the watched path: a ConfigMap update swaps the ..data symlink, never the config file itself.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.WatchCondition.class)
public class ConfigFileRefresher implements ApplicationEventListener<FileChangedEvent> {

    /**
     * Quiet period after the last file event before refreshing; one ConfigMap update emits several events.
     */
    static final Duration DEBOUNCE = Duration.ofMillis(500);

    private final Environment environment;
    private final ApplicationEventPublisher<RefreshEvent> refreshPublisher;
    private final TaskScheduler scheduler;
    private final ConfigurationRefreshLock refreshLock;
    private ScheduledFuture<?> pending;

    public ConfigFileRefresher(Environment environment, ApplicationEventPublisher<RefreshEvent> refreshPublisher,
                               @Named(TaskExecutors.SCHEDULED) TaskScheduler scheduler, ConfigurationRefreshLock refreshLock) {
        this.environment = environment;
        this.refreshPublisher = refreshPublisher;
        this.scheduler = scheduler;
        this.refreshLock = refreshLock;
    }

    @Override
    public synchronized void onApplicationEvent(FileChangedEvent event) {
        log.debug("Configuration path {} changed ({}).", event.getPath(), event.getEventType());
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(DEBOUNCE, this::refresh);
    }

    void refresh() {
        try {
            refreshLock.exclusively(this::refreshAndPublish);
        } catch (RuntimeException e) {
            log.warn("Refreshing the configuration after a file change failed.", e);
        }
    }

    private Map<String, Object> refreshAndPublish() {
        // refreshAndDiff re-reads the property sources; a bare RefreshEvent would rebind from the stale environment
        Map<String, Object> diff = environment.refreshAndDiff();
        if (!diff.isEmpty()) {
            log.info("Configuration file changed, refreshing keys {}.", diff.keySet());
            refreshPublisher.publishEvent(new RefreshEvent(diff));
        }
        return diff;
    }
}
