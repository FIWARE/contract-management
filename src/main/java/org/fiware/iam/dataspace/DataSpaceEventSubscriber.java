package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.DataSpaceEventsProperties.SubscriberSettings;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.logging.DownstreamError;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_INITIAL_DELAY;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RENEWAL_FRACTION;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF;

/**
 * Registers the own POST /events as webhook sink at every publisher with a subscriptions-url, renews each lease before it
 * expires and retries failures with a saturating backoff. Settings are read from the refreshable bean on every use.
 */
@Context
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
@Slf4j
public class DataSpaceEventSubscriber implements ApplicationEventListener<ServerStartupEvent> {

    /**
     * Upper bound for every scheduled delay: an unlimited lease or an oversized interval would overflow the scheduler.
     */
    static final Duration MAX_SCHEDULE_DELAY = Duration.ofDays(1);

    /**
     * Health detail of an entry whose values could not be read, e.g. a null element in its types or scopes.
     */
    static final String INVALID_ENTRY_REASON = "the publisher entry could not be read";

    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;
    private final PublisherSubscriptionClient client;
    private final SubscriptionStateStore store;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final AtomicBoolean started = new AtomicBoolean();

    public DataSpaceEventSubscriber(DataSpaceEventsProperties properties, GeneralProperties generalProperties,
                                    PublisherSubscriptionClient client, SubscriptionStateStore store,
                                    @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler, Clock clock) {
        this.properties = properties;
        this.generalProperties = generalProperties;
        this.client = client;
        this.store = store;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        if (started.compareAndSet(false, true)) {
            taskScheduler.schedule(positiveOrDefault(settings().getInitialDelay(), DEFAULT_SUBSCRIBER_INITIAL_DELAY),
                    this::backstopTick);
        }
    }

    private void backstopTick() {
        reconcile();
    }

    /**
     * Adds a state for every subscribable publisher not known yet and registers it; the only place states are created.
     */
    synchronized void reconcile() {
        try {
            SubscriberSettings settings = settings();
            String ownId = properties.ownId(generalProperties);
            Optional<String> settingsProblem = SubscriberSettingsValidator.problem(settings, ownId);
            if (settingsProblem.isPresent()) {
                log.warn("Not subscribing to any publisher: {}", settingsProblem.get());
                store.markUnusableSettings(settingsProblem.get());
                return;
            }
            Set<String> seen = new HashSet<>();
            for (Publisher publisher : properties.getPublishers()) {
                try {
                    reconcile(publisher, settings, ownId, seen);
                } catch (RuntimeException e) {
                    // one broken entry (e.g. a null list element) must not keep the others from being subscribed
                    String publisherId = publisher == null ? null : publisher.getId();
                    log.warn("Not subscribing to publisher {}: its entry could not be read.", publisherId, e);
                    if (!isBlank(publisherId) && store.get(publisherId).isEmpty()) {
                        store.put(publisherId, PublisherState.skipped(INVALID_ENTRY_REASON));
                    }
                }
            }
            store.markReconciled();
        } catch (RuntimeException e) {
            log.warn("Reconciling the publisher subscriptions failed.", e);
        }
    }

    private void reconcile(Publisher publisher, SubscriberSettings settings, String ownId, Set<String> seen) {
        if (isBlank(publisher.getSubscriptionsUrl())) {
            return;
        }
        String publisherId = publisher.getId();
        if (isBlank(publisherId)) {
            log.warn("Not subscribing at {}: the publisher entry has no id.", publisher.getSubscriptionsUrl());
            return;
        }
        if (!seen.add(publisherId)) {
            log.warn("Publisher {} is configured more than once; only its first entry is subscribed to.", publisherId);
            return;
        }
        if (store.get(publisherId).isPresent()) {
            return;
        }
        Optional<String> skipReason = skipReason(publisher);
        if (skipReason.isPresent()) {
            log.warn("Not subscribing to publisher {}: {}.", publisherId, skipReason.get());
            store.put(publisherId, PublisherState.skipped(skipReason.get()));
            return;
        }
        PublisherState state = PublisherState.pending(SubscriptionTarget.of(publisher, settings, ownId));
        store.put(publisherId, state);
        register(state, state.generation());
    }

    /**
     * Delay before retry number attempt + 1: doubles from the initial backoff and saturates at the maximum.
     */
    Duration backoff(int attempt) {
        SubscriberSettings.Retry retry = settings().getRetry();
        Duration maxBackoff = positiveOrDefault(retry.getMaxBackoff(), DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF);
        Duration delay = positiveOrDefault(retry.getInitialBackoff(), DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF);
        for (int doublings = 0; doublings < attempt && delay.compareTo(maxBackoff) < 0; doublings++) {
            delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private void register(PublisherState state, long generation) {
        if (!state.isCurrent(generation)) {
            return;
        }
        SubscriptionTarget target = state.target();
        try {
            client.subscribe(target)
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("the subscription request completed without an answer")))
                    .subscribe(
                    registration -> onRegistration(state, generation, target, registration),
                    error -> onFailure(state, generation, target, error));
        } catch (RuntimeException e) {
            onFailure(state, generation, target, e);
        }
    }

    private void onRegistration(PublisherState state, long generation, SubscriptionTarget target,
                                PublisherSubscriptionClient.Registration registration) {
        try {
            Instant now = clock.instant();
            Duration delay;
            synchronized (state) {
                if (!state.isCurrent(generation)) {
                    return;
                }
                if (registration.conflict() || registration.expiresAt() == null) {
                    delay = capped(positiveOrDefault(settings().getConflictRenewalInterval(),
                            DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL));
                    // a max-backoff of grace: a failing re-registration is retried before the publisher counts as expired
                    Instant validUntil = now.plus(delay).plus(capped(backoff(Integer.MAX_VALUE)));
                    // CONFLICT stays reserved for a real 409 (e.g. the publisher's cap), only the timing is shared
                    if (registration.conflict()) {
                        state.conflict(registration.id(), validUntil);
                    } else {
                        state.registered(registration.id(), validUntil);
                    }
                } else {
                    delay = renewalDelay(now, registration.expiresAt());
                    state.registered(registration.id(), registration.expiresAt());
                }
                scheduleNext(state, generation, delay);
            }
            if (registration.conflict()) {
                log.info("Publisher {} at {} answered 409, treated as existing subscription; re-registering in {}.",
                        target.publisherId(), target.subscriptionsUrl(), delay);
            } else if (registration.expiresAt() == null) {
                log.info("Subscribed to {} at {} without an expiry; re-registering in {}.",
                        target.publisherId(), target.subscriptionsUrl(), delay);
            } else {
                log.info("Subscribed to {} at {} until {}.", target.publisherId(), target.subscriptionsUrl(),
                        registration.expiresAt());
            }
        } catch (RuntimeException e) {
            onFailure(state, generation, target, e);
        }
    }

    private void onFailure(PublisherState state, long generation, SubscriptionTarget target, Throwable error) {
        try {
            Duration delay;
            synchronized (state) {
                if (!state.isCurrent(generation)) {
                    return;
                }
                Duration backoff = backoff(state.attempt());
                state.attemptFailed(backoff.compareTo(backoff(Integer.MAX_VALUE)) >= 0);
                delay = capped(backoff);
                scheduleNext(state, generation, delay);
            }
            log.warn("Subscribing to {} at {} failed: {}. Retrying in {}.", target.publisherId(), target.subscriptionsUrl(),
                    DownstreamError.reason(error), delay, error);
        } catch (RuntimeException e) {
            log.warn("Could not schedule the next subscription attempt for publisher {}; it is retried after a reconcile.",
                    target.publisherId(), e);
        }
    }

    private void scheduleNext(PublisherState state, long generation, Duration delay) {
        state.scheduled(taskScheduler.schedule(delay, () -> register(state, generation)));
    }

    private Duration renewalDelay(Instant now, Instant expiresAt) {
        double fraction = settings().getRenewalFraction();
        if (!(fraction > 0 && fraction < 1)) {
            fraction = DEFAULT_SUBSCRIBER_RENEWAL_FRACTION;
        }
        Duration remaining = Duration.between(now, expiresAt);
        Duration delay = remaining.isNegative() ? Duration.ZERO : Duration.ofMillis((long) (remaining.toMillis() * fraction));
        Duration minRenewalDelay = positiveOrDefault(settings().getMinRenewalDelay(), DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY);
        return capped(delay.compareTo(minRenewalDelay) < 0 ? minRenewalDelay : delay);
    }

    private SubscriberSettings settings() {
        return properties.getSubscriber();
    }

    // a subscriptions-url alone is not enough: without types nothing is subscribed, without the origin our handshake answer fails
    private static Optional<String> skipReason(Publisher publisher) {
        if (publisher.getTypes() == null || publisher.getTypes().isEmpty()) {
            return Optional.of("no types configured");
        }
        if (isBlank(publisher.getOrigin())) {
            return Optional.of("no origin configured, the webhook handshake would fail");
        }
        return Optional.empty();
    }

    // invalid values (e.g. blanked by a refresh) fall back to the defaults instead of breaking the subscriptions
    private static Duration positiveOrDefault(@Nullable Duration value, Duration defaultValue) {
        return value != null && value.isPositive() ? value : defaultValue;
    }

    private static Duration capped(Duration delay) {
        return delay.compareTo(MAX_SCHEDULE_DELAY) > 0 ? MAX_SCHEDULE_DELAY : delay;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
