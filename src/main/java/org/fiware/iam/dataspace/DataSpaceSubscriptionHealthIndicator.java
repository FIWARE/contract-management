package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.dataspace.SubscriptionStateStore.Phase;
import org.fiware.iam.dataspace.SubscriptionStateStore.PublisherStatus;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Health of the subscriptions at the publishers: UP while every subscribable publisher holds a live registration, else DEGRADED.
 * Never DOWN: the publisher's handshake only reaches a pod that passes its probes, so DOWN would block registration for good.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
@RequiredArgsConstructor
public class DataSpaceSubscriptionHealthIndicator implements HealthIndicator {

    static final String NAME = "dataspace-subscriptions";
    static final String EXPIRED = "EXPIRED";
    static final String STATE_DETAIL = "state";
    static final String EXPIRES_AT_DETAIL = "expiresAt";
    static final String REASON_DETAIL = "reason";
    static final String SETTINGS_DETAIL = "subscriber-settings";

    public static final HealthStatus DEGRADED = DataSpaceHealth.DEGRADED;

    private final SubscriptionStateStore store;
    private final Clock clock;

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromSupplier(this::result);
    }

    HealthResult result() {
        Instant now = clock.instant();
        Optional<String> settingsProblem = store.settingsProblem();
        boolean healthy = store.isReconciled() && settingsProblem.isEmpty();
        Map<String, Object> details = new LinkedHashMap<>();
        settingsProblem.ifPresent(problem -> details.put(SETTINGS_DETAIL, problem));
        for (Map.Entry<String, PublisherStatus> entry : store.snapshot().entrySet()) {
            PublisherStatus status = entry.getValue();
            boolean live = isLive(status, now);
            if (status.phase() != Phase.SKIPPED) {
                healthy &= live;
            }
            details.put(entry.getKey(), detailsOf(status, live));
        }
        return HealthResult.builder(NAME, healthy ? HealthStatus.UP : DEGRADED).details(details).build();
    }

    private static boolean isLive(PublisherStatus status, Instant now) {
        boolean registered = status.phase() == Phase.REGISTERED || status.phase() == Phase.CONFLICT;
        return registered && status.validUntil() != null && now.isBefore(status.validUntil());
    }

    private static Map<String, Object> detailsOf(PublisherStatus status, boolean live) {
        boolean expired = !live && (status.phase() == Phase.REGISTERED || status.phase() == Phase.CONFLICT);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(STATE_DETAIL, expired ? EXPIRED : status.phase().name());
        if (status.validUntil() != null) {
            details.put(EXPIRES_AT_DETAIL, status.validUntil().toString());
        }
        if (status.reason() != null) {
            details.put(REASON_DETAIL, status.reason());
        }
        return details;
    }
}
