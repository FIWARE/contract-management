package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.dataspace.SubscriptionStateStore.Phase;
import org.fiware.iam.dataspace.SubscriptionStateStore.PublisherStatus;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Mutable subscription state of one publisher. All access synchronizes on the instance.
 * Every replaced or cancelled task loop bumps the generation; callbacks carrying an older generation must do nothing.
 */
final class PublisherState {

    @Nullable
    private SubscriptionTarget target;
    private Phase phase;
    @Nullable
    private String subscriptionId;
    @Nullable
    private Instant validUntil;
    @Nullable
    private String reason;
    private int attempt;
    @Nullable
    private ScheduledFuture<?> next;
    private long generation;

    private PublisherState(@Nullable SubscriptionTarget target, Phase phase, @Nullable String reason) {
        this.target = target;
        this.phase = phase;
        this.reason = reason;
    }

    static PublisherState pending(SubscriptionTarget target) {
        return new PublisherState(target, Phase.PENDING, null);
    }

    static PublisherState skipped(String reason) {
        return new PublisherState(null, Phase.SKIPPED, reason);
    }

    synchronized PublisherStatus status() {
        return new PublisherStatus(phase, validUntil, reason);
    }

    /**
     * The target the subscription is registered with; null for SKIPPED.
     */
    @Nullable
    synchronized SubscriptionTarget target() {
        return target;
    }

    @Nullable
    synchronized String subscriptionId() {
        return subscriptionId;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return generation == expectedGeneration;
    }

    synchronized int attempt() {
        return attempt;
    }

    synchronized void registered(@Nullable String id, Instant expiresAt) {
        phase = Phase.REGISTERED;
        subscriptionId = id;
        validUntil = expiresAt;
        attempt = 0;
    }

    synchronized void conflict(@Nullable String id, Instant reRegistrationAt) {
        phase = Phase.CONFLICT;
        subscriptionId = id;
        validUntil = reRegistrationAt;
        attempt = 0;
    }

    synchronized void attemptFailed(boolean saturated) {
        if (!saturated) {
            attempt++;
        }
    }

    /**
     * Replaces the pending task; the previous one has already run or is cancelled by the caller.
     */
    synchronized void scheduled(@Nullable ScheduledFuture<?> future) {
        next = future;
    }

    /**
     * Cancels the pending task and invalidates every callback of the current loop.
     */
    synchronized void cancel() {
        generation++;
        if (next != null) {
            next.cancel(false);
            next = null;
        }
    }
}
