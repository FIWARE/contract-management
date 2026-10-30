package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.SubscriptionLimitException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, single-replica store of the active subscriptions; a restart loses them and subscribers renew.
 * Leases and caps are read from the refreshable publisher settings on every registration.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class SubscriptionRegistry {

    static final String EVICTION_INTERVAL = "1m";

    private final DataSpaceEventsProperties properties;
    private final Clock clock;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * Activates a subscription, or renews the subscriber's existing one for the same sink in place (same id, new lease).
     *
     * @throws SubscriptionLimitException if a new subscription would exceed the per-subscriber or the total cap
     */
    public synchronized Subscription register(String subscriberId, URI sink, List<String> types, String clientId,
                                              @Nullable Set<String> scopes, @Nullable Integer allowedRate) {
        Instant now = clock.instant();
        evictExpired(now);
        Optional<Subscription> existing = subscriptions.values().stream()
                .filter(subscription -> subscription.subscriberId().equals(subscriberId) && subscription.sink().equals(sink))
                .findFirst();
        if (existing.isEmpty()) {
            enforceCaps(subscriberId);
        }
        String id = existing.map(Subscription::id).orElseGet(() -> UUID.randomUUID().toString());
        Subscription subscription = new Subscription(id, subscriberId, sink, types, clientId, scopes,
                now.plus(leaseTtl()), allowedRate);
        subscriptions.put(id, subscription);
        return subscription;
    }

    /**
     * Removes the subscription if it is active and owned by the subscriber; false otherwise.
     */
    public synchronized boolean remove(String id, String subscriberId) {
        Subscription subscription = subscriptions.get(id);
        if (subscription == null || !subscription.subscriberId().equals(subscriberId)) {
            return false;
        }
        subscriptions.remove(id);
        return !subscription.isExpired(clock.instant());
    }

    /**
     * Removes the subscription regardless of its owner; false if it was not active.
     */
    public synchronized boolean remove(String id) {
        Subscription subscription = subscriptions.remove(id);
        return subscription != null && !subscription.isExpired(clock.instant());
    }

    public List<Subscription> active(String type) {
        Instant now = clock.instant();
        return subscriptions.values().stream()
                .filter(subscription -> !subscription.isExpired(now) && subscription.matches(type))
                .toList();
    }

    public Optional<Subscription> get(String id) {
        return Optional.ofNullable(subscriptions.get(id))
                .filter(subscription -> !subscription.isExpired(clock.instant()));
    }

    /**
     * Drops expired leases periodically, so subscribers that stopped renewing do not hold memory.
     */
    @Scheduled(fixedDelay = EVICTION_INTERVAL)
    public synchronized void evictExpired() {
        evictExpired(clock.instant());
    }

    private void evictExpired(Instant now) {
        subscriptions.values().removeIf(subscription -> {
            if (subscription.isExpired(now)) {
                log.info("Subscription {} of {} to {} expired.", subscription.id(), subscription.subscriberId(), subscription.sink());
                return true;
            }
            return false;
        });
    }

    private void enforceCaps(String subscriberId) {
        DataSpaceEventsProperties.PublisherSettings settings = properties.getPublisher();
        int maxPerSubscriber = positiveOrDefault(settings.getMaxSubscriptionsPerSubscriber(),
                DataSpaceEventsProperties.DEFAULT_MAX_SUBSCRIPTIONS_PER_SUBSCRIBER);
        int maxTotal = positiveOrDefault(settings.getMaxSubscriptions(), DataSpaceEventsProperties.DEFAULT_MAX_SUBSCRIPTIONS);
        long ofSubscriber = subscriptions.values().stream()
                .filter(subscription -> subscription.subscriberId().equals(subscriberId))
                .count();
        if (ofSubscriber >= maxPerSubscriber) {
            throw new SubscriptionLimitException("Subscriber %s already holds the maximum of %d subscriptions."
                    .formatted(subscriberId, maxPerSubscriber));
        }
        if (subscriptions.size() >= maxTotal) {
            throw new SubscriptionLimitException("The maximum of %d subscriptions is reached; refused subscriber %s."
                    .formatted(maxTotal, subscriberId));
        }
    }

    // invalid values (e.g. blanked by a refresh) fall back to the defaults instead of refusing every subscription
    private Duration leaseTtl() {
        Duration leaseTtl = properties.getPublisher().getLeaseTtl();
        return leaseTtl != null && leaseTtl.isPositive() ? leaseTtl : DataSpaceEventsProperties.DEFAULT_LEASE_TTL;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
