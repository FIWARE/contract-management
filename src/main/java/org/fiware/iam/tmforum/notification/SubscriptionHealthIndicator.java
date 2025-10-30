package org.fiware.iam.tmforum.notification;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.AsyncSingleResultPublisher;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.NotificationProperties;
import org.reactivestreams.Publisher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Indicator to collect the existence of all configured subscriptions towards TMForum
 */
@Requires(condition = NotificationProperties.NotificationCondition.class)
@Singleton
public class SubscriptionHealthIndicator implements HealthIndicator {

    private final Map<String, Boolean> registrationMap = new LinkedHashMap<>();

    @Override
    public Publisher<HealthResult> getResult() {

        HealthResult.Builder healthResultBuilder = HealthResult.builder("Subscription Health");
        Optional<Boolean> optionalUnhealthy = registrationMap.values().stream().filter(key -> !key).findAny();
        if (optionalUnhealthy.isPresent()) {
            return new AsyncSingleResultPublisher<>(() -> healthResultBuilder.status(HealthStatus.DOWN).build());
        } else {
            return new AsyncSingleResultPublisher<>(() -> healthResultBuilder.status(HealthStatus.UP).build());
        }
    }

    public void initiateSubscriptionInMap(String key) {
        registrationMap.put(key, false);
    }

    public void setSubscriptionHealthy(String key) {
        registrationMap.put(key, true);
    }

}
