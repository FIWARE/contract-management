package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.fiware.iam.exception.EventDeliveryException;
import org.fiware.iam.http.AuthHandler;
import org.fiware.iam.logging.DownstreamError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.fiware.iam.http.Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE;
import static org.fiware.iam.http.Oid4VpAuthHandler.SCOPE_ATTRIBUTE;

/**
 * Best-effort delivery of events to the sinks of the matching subscriptions, each through its own {@link SubscriptionChannel}.
 * Retries with exponential backoff, answers a 401 with OID4VP, and counts every dropped or delivered event in {@value #METRIC_NAME}.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class EventDelivery {

    static final String METRIC_NAME = "dataspace.events.delivered";
    static final String TYPE_TAG = "type";
    static final String OUTCOME_TAG = "outcome";
    static final String CLOUDEVENTS_JSON = "application/cloudevents+json";

    /**
     * Final result of an event at one subscription; its lower-case name is the metric's outcome tag.
     */
    enum Outcome {
        DELIVERED, FAILED, GONE, EXPIRED, OVERFLOW;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final HttpClient httpClient;
    // only use authentication if a handler is provided
    private final Optional<AuthHandler> authHandler;
    private final SubscriptionRegistry registry;
    private final DataSpaceEventsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, SubscriptionChannel> channels = new ConcurrentHashMap<>();

    public EventDelivery(@Client(configuration = PublisherHttpClientConfiguration.class) HttpClient httpClient,
                         Optional<AuthHandler> authHandler, SubscriptionRegistry registry,
                         DataSpaceEventsProperties properties, Optional<MeterRegistry> meterRegistry) {
        this.httpClient = httpClient;
        this.authHandler = authHandler;
        this.registry = registry;
        this.properties = properties;
        // without micronaut.metrics.enabled there is no registry; an empty composite counts nothing
        this.meterRegistry = meterRegistry.orElseGet(CompositeMeterRegistry::new);
    }

    /**
     * Queues the event for every active subscription of its type and returns at once; the outcome is only logged and counted.
     */
    public void publish(DataSpaceEventVO event) {
        Objects.requireNonNull(event.getType(), "An event to publish needs a type.");
        pruneChannels();
        for (Subscription subscription : registry.active(event.getType())) {
            SubscriptionChannel channel = channels.computeIfAbsent(subscription.id(),
                    id -> new SubscriptionChannel(queueSize(), (ch, queued) -> deliver(id, ch, queued)));
            switch (channel.offer(event)) {
                case QUEUED -> log.debug("Event {} queued for subscription {}.", event.getId(), subscription.id());
                case OVERFLOW_STARTED -> {
                    log.warn("Queue of subscription {} of {} is full; dropping events until its sink catches up.",
                            subscription.id(), subscription.subscriberId());
                    count(event, Outcome.OVERFLOW);
                }
                case OVERFLOW -> count(event, Outcome.OVERFLOW);
                // pruned concurrently: the subscription just ended
                case CLOSED -> count(event, Outcome.EXPIRED);
            }
        }
    }

    /**
     * Disposes the channels of removed or expired subscriptions; a renewed subscription keeps its id and its channel.
     */
    @Scheduled(fixedDelay = SubscriptionRegistry.EVICTION_INTERVAL)
    public void pruneChannels() {
        channels.entrySet().removeIf(entry -> {
            if (registry.get(entry.getKey()).isPresent()) {
                return false;
            }
            entry.getValue().dispose();
            return true;
        });
    }

    @PreDestroy
    void close() {
        channels.values().forEach(SubscriptionChannel::dispose);
        channels.clear();
    }

    private Mono<Void> deliver(String subscriptionId, SubscriptionChannel channel, DataSpaceEventVO event) {
        DataSpaceEventsProperties.PublisherSettings.Delivery settings = properties.getPublisher().getDelivery();
        int maxAttempts = settings.getMaxAttempts() > 0 ? settings.getMaxAttempts()
                : DataSpaceEventsProperties.DEFAULT_DELIVERY_MAX_ATTEMPTS;
        Attempts attempts = new Attempts();
        return Mono.defer(() -> attempt(subscriptionId, channel, event, attempts))
                .retryWhen(Retry.backoff(maxAttempts - 1L,
                                positiveOrDefault(settings.getInitialBackoff(), DataSpaceEventsProperties.DEFAULT_DELIVERY_INITIAL_BACKOFF))
                        .maxBackoff(positiveOrDefault(settings.getMaxBackoff(), DataSpaceEventsProperties.DEFAULT_DELIVERY_MAX_BACKOFF))
                        .filter(EventDelivery::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .then(Mono.fromRunnable(() -> {
                    log.debug("Event {} delivered to subscription {}.", event.getId(), subscriptionId);
                    count(event, Outcome.DELIVERED);
                }))
                .onErrorResume(e -> {
                    dropped(subscriptionId, event, attempts, e);
                    return Mono.empty();
                })
                // an OID4VP token fetch must not run on the caller's (event loop) thread
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> attempt(String subscriptionId, SubscriptionChannel channel, DataSpaceEventVO event, Attempts attempts) {
        // read before each attempt, so expiry, removal and renewal take effect on queued events
        Optional<Subscription> current = registry.get(subscriptionId);
        if (current.isEmpty()) {
            return Mono.error(new SubscriptionEnded());
        }
        Subscription subscription = current.get();
        attempts.subscription = subscription;
        attempts.count++;
        MutableHttpRequest<DataSpaceEventVO> request = HttpRequest.POST(subscription.sink().toString(), event)
                .contentType(CLOUDEVENTS_JSON);
        String origin = properties.getPublisher().getOrigin();
        if (origin != null) {
            request.header(WebhookHeaders.REQUEST_ORIGIN, origin);
        }
        request.setAttribute(CLIENT_ID_ATTRIBUTE, subscription.clientId());
        request.setAttribute(SCOPE_ATTRIBUTE, subscription.scopes());
        // paces every request, including the one after an OID4VP token fetch
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> pacedSend = req -> channel.nextSlot(subscription.allowedRate())
                .then(Mono.defer(() -> Mono.from(httpClient.exchange(req, String.class))));
        return authHandler.map(handler -> handler.executeWithAuth(request, pacedSend))
                .orElseGet(() -> pacedSend.apply(request))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("No response from " + subscription.sink())))
                .flatMap(response -> isSuccess(response.code()) ? Mono.<Void>empty()
                        // a returned error response is turned into the exception other paths throw, so one classification fits all
                        : Mono.error(new HttpClientResponseException("Sink answered " + response.code(), response)));
    }

    private void dropped(String subscriptionId, DataSpaceEventVO event, Attempts attempts, Throwable e) {
        Outcome outcome = outcomeOf(e);
        switch (outcome) {
            case EXPIRED -> log.debug("Event {} not delivered: subscription {} expired or was removed.", event.getId(), subscriptionId);
            case GONE -> {
                if (registry.remove(subscriptionId)) {
                    log.info("Subscription {} of {} removed: its sink {} answered 410 Gone.", subscriptionId,
                            attempts.subscriberId(), attempts.sink());
                }
            }
            default -> {
                EventDeliveryException failure = new EventDeliveryException(
                        "Event %s of type %s dropped for subscription %s of %s at %s after %d attempt(s).".formatted(
                                event.getId(), event.getType(), subscriptionId, attempts.subscriberId(), attempts.sink(),
                                attempts.count), e);
                log.error("{}", DownstreamError.reason(failure), failure);
            }
        }
        count(event, outcome);
    }

    // the event matched a subscription when it was queued, so its type is bounded by the subscriptions' types
    private void count(DataSpaceEventVO event, Outcome outcome) {
        meterRegistry.counter(METRIC_NAME, TYPE_TAG, event.getType(), OUTCOME_TAG, outcome.tag()).increment();
    }

    private int queueSize() {
        int queueSize = properties.getPublisher().getDelivery().getQueueSize();
        return queueSize > 0 ? queueSize : DataSpaceEventsProperties.DEFAULT_DELIVERY_QUEUE_SIZE;
    }

    /**
     * The status a failed attempt was answered with; empty for transport errors, a failed token fetch and anything else.
     */
    static Optional<Integer> statusOf(Throwable e) {
        return e instanceof HttpClientResponseException hcre ? Optional.of(hcre.getResponse().code()) : Optional.empty();
    }

    private static boolean isRetryable(Throwable e) {
        if (e instanceof SubscriptionEnded) {
            return false;
        }
        return statusOf(e)
                .map(status -> status >= HttpStatus.INTERNAL_SERVER_ERROR.getCode()
                        || status == HttpStatus.REQUEST_TIMEOUT.getCode()
                        || status == HttpStatus.TOO_MANY_REQUESTS.getCode())
                .orElse(true);
    }

    private static Outcome outcomeOf(Throwable e) {
        if (e instanceof SubscriptionEnded) {
            return Outcome.EXPIRED;
        }
        return statusOf(e).filter(status -> status == HttpStatus.GONE.getCode()).isPresent() ? Outcome.GONE : Outcome.FAILED;
    }

    // redirects are not followed, so a 3xx is a failure as well
    private static boolean isSuccess(int status) {
        return status >= HttpStatus.OK.getCode() && status < HttpStatus.MULTIPLE_CHOICES.getCode();
    }

    // invalid values (e.g. blanked by a refresh) fall back to the defaults instead of breaking delivery
    private static Duration positiveOrDefault(@Nullable Duration value, Duration defaultValue) {
        return value != null && value.isPositive() ? value : defaultValue;
    }

    /**
     * Attempts made for one event and the subscription seen by the last one; written by one attempt at a time.
     */
    private static final class Attempts {
        private volatile int count;
        @Nullable
        private volatile Subscription subscription;

        @Nullable
        String subscriberId() {
            Subscription last = subscription;
            return last == null ? null : last.subscriberId();
        }

        @Nullable
        String sink() {
            Subscription last = subscription;
            return last == null ? null : last.sink().toString();
        }
    }

    /**
     * The subscription expired or was removed while the event was queued; ends its delivery without a retry.
     */
    private static final class SubscriptionEnded extends RuntimeException {
        SubscriptionEnded() {
            super(null, null, false, false);
        }
    }
}
