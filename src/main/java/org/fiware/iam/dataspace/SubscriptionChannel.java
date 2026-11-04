package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.events.model.DataSpaceEventVO;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiFunction;

/**
 * Bounded queue of one subscription, delivered one event after another, so a slow or failing sink only holds back its own
 * events. Also paces the requests to that sink. Lives as long as the subscription id, so a renewal keeps queue and pacing.
 */
@Slf4j
final class SubscriptionChannel {

    private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();
    // concatMap must not prefetch, or events would wait beyond the bounded queue
    private static final int NO_PREFETCH = 0;

    /**
     * Result of queueing an event. OVERFLOW_STARTED is the first refusal after an accepted event, so a full queue is reported once.
     */
    enum Offer { QUEUED, OVERFLOW_STARTED, OVERFLOW, CLOSED }

    private final Sinks.Many<DataSpaceEventVO> queue;
    private final Disposable worker;
    // guarded by this
    private long nextSlot = System.nanoTime();
    private boolean overflowing;

    /**
     * The delivery must complete empty on failure; an error would end the channel.
     */
    SubscriptionChannel(int queueSize, BiFunction<SubscriptionChannel, DataSpaceEventVO, Mono<Void>> delivery) {
        queue = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(queueSize));
        worker = queue.asFlux()
                .concatMap(event -> delivery.apply(this, event)
                        .onErrorResume(e -> {
                            log.error("Unexpected failure delivering event {}.", event.getId(), e);
                            return Mono.empty();
                        }), NO_PREFETCH)
                .subscribe();
    }

    synchronized Offer offer(DataSpaceEventVO event) {
        Sinks.EmitResult result = queue.tryEmitNext(event);
        if (result.isSuccess()) {
            overflowing = false;
            return Offer.QUEUED;
        }
        if (result != Sinks.EmitResult.FAIL_OVERFLOW) {
            return Offer.CLOSED;
        }
        boolean started = !overflowing;
        overflowing = true;
        return started ? Offer.OVERFLOW_STARTED : Offer.OVERFLOW;
    }

    /**
     * Completes when the next request to the sink may be sent: at most allowedRate requests per minute, unlimited if null.
     */
    Mono<Void> nextSlot(@Nullable Integer allowedRate) {
        return Mono.defer(() -> {
            Duration wait = reserveSlot(allowedRate);
            return wait.isZero() ? Mono.<Void>empty() : Mono.delay(wait).then();
        });
    }

    /**
     * Accepts no further events. Queued and in-flight ones still run through the delivery, which ends and counts them.
     */
    // same monitor as offer: the sink refuses concurrent emissions, and a lost completion would leak the worker
    synchronized void complete() {
        queue.tryEmitComplete();
    }

    /**
     * Stops delivery at once on shutdown; queued events are dropped uncounted.
     */
    void dispose() {
        worker.dispose();
    }

    /**
     * Longest wait for one slot at the given rate; zero if unlimited.
     */
    static Duration interval(@Nullable Integer allowedRate) {
        return Duration.ofNanos(allowedRate == null || allowedRate <= 0 ? 0 : NANOS_PER_MINUTE / allowedRate);
    }

    private synchronized Duration reserveSlot(@Nullable Integer allowedRate) {
        long now = System.nanoTime();
        long slot = nextSlot - now > 0 ? nextSlot : now;
        nextSlot = slot + interval(allowedRate).toNanos();
        return Duration.ofNanos(slot - now);
    }
}
