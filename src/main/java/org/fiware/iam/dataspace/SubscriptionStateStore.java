package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscription state per publisher id, written by {@link DataSpaceEventSubscriber} and read by the health indicator.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
public class SubscriptionStateStore {

    /**
     * Where the subscription at one publisher stands.
     */
    public enum Phase {PENDING, REGISTERED, CONFLICT, SKIPPED}

    /**
     * Immutable view of one publisher's state. validUntil is the lease end for REGISTERED and the next re-registration for
     * CONFLICT; reason is only set for SKIPPED.
     */
    public record PublisherStatus(Phase phase, @Nullable Instant validUntil, @Nullable String reason) {
    }

    private final Map<String, PublisherState> states = new ConcurrentHashMap<>();
    private volatile boolean reconciled;

    /**
     * The status of every known publisher, ordered by id.
     */
    public Map<String, PublisherStatus> snapshot() {
        Map<String, PublisherStatus> snapshot = new TreeMap<>();
        states.forEach((publisherId, state) -> snapshot.put(publisherId, state.status()));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * False until the first reconcile has filled the store, so an empty store at startup is not mistaken for "nothing to do".
     */
    public boolean isReconciled() {
        return reconciled;
    }

    void markReconciled() {
        reconciled = true;
    }

    Optional<PublisherState> get(String publisherId) {
        return Optional.ofNullable(states.get(publisherId));
    }

    void put(String publisherId, PublisherState state) {
        states.put(publisherId, state);
    }
}
