package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.dataspace.TirWatcher.Status;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health of the trust anchor watcher: DEGRADED while an alert is active or no cycle succeeded for a while, else UP.
 * Reads only the watcher's published status, so it never waits for a cycle blocked on a slow registry; never DOWN.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)
@RequiredArgsConstructor
public class TirWatcherHealthIndicator implements HealthIndicator {

    static final String NAME = "dataspace-tir-watcher";

    /**
     * Intervals without a successful cycle after which the watcher counts as stuck.
     */
    static final int STALE_INTERVALS = 3;

    static final String STATE_DETAIL = "state";
    static final String NOT_SCHEDULED_STATE = "not_scheduled";
    static final String SCHEDULED_STATE = "scheduled";
    static final String REASON_DETAIL = "reason";
    static final String NO_SUCCESSFUL_CYCLE_REASON = "no_successful_cycle";
    static final String LAST_SUCCESSFUL_CYCLE_DETAIL = "lastSuccessfulCycle";
    static final String FIRST_CYCLE_DUE_DETAIL = "firstCycleDue";
    static final String SNAPSHOT_SIZE_DETAIL = "snapshotSize";
    static final String PENDING_SIZE_DETAIL = "pendingSize";
    static final String UNDELIVERED_SIZE_DETAIL = "undeliveredSize";

    private final TirWatcher watcher;
    private final DataSpaceEventsProperties properties;
    private final Clock clock;

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromSupplier(this::result);
    }

    HealthResult result() {
        Status status = watcher.status();
        Map<String, Object> details = new LinkedHashMap<>();
        if (status.firstCycleDue() == null) {
            details.put(STATE_DETAIL, NOT_SCHEDULED_STATE);
            return HealthResult.builder(NAME, HealthStatus.UP).details(details).build();
        }
        details.put(STATE_DETAIL, status.lastOutcome() == null ? SCHEDULED_STATE : status.lastOutcome().tagValue());
        if (status.lastSuccessfulCycle() != null) {
            details.put(LAST_SUCCESSFUL_CYCLE_DETAIL, status.lastSuccessfulCycle().toString());
        }
        details.put(FIRST_CYCLE_DUE_DETAIL, status.firstCycleDue().toString());
        details.put(SNAPSHOT_SIZE_DETAIL, status.snapshotSize());
        details.put(PENDING_SIZE_DETAIL, status.pendingSize());
        details.put(UNDELIVERED_SIZE_DETAIL, status.undeliveredSize());

        String reason = null;
        if (status.alertActive()) {
            reason = status.alert().getCode();
        } else if (isStale(status)) {
            reason = NO_SUCCESSFUL_CYCLE_REASON;
        }
        if (reason == null) {
            return HealthResult.builder(NAME, HealthStatus.UP).details(details).build();
        }
        details.put(REASON_DETAIL, reason);
        return HealthResult.builder(NAME, DataSpaceHealth.DEGRADED).details(details).build();
    }

    // before the first successful cycle the baseline is when the first cycle was due, so the initial delay never counts
    private boolean isStale(Status status) {
        Instant baseline = status.lastSuccessfulCycle() != null ? status.lastSuccessfulCycle() : status.firstCycleDue();
        Instant deadline = baseline.plus(TirWatcherScheduler.interval(properties.getTirWatcher()).multipliedBy(STALE_INTERVALS));
        return clock.instant().isAfter(deadline);
    }
}
