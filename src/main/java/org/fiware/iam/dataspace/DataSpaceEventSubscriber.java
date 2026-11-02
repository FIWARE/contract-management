package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_INITIAL_DELAY;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RENEWAL_FRACTION;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF;
import static org.fiware.iam.configuration.DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF;

/**
 * Registers the own POST /events as webhook sink at every publisher with a subscriptions-url, renews each lease before it
 * expires and retries failures with a saturating backoff. Settings are read from the refreshable bean on every use;
 * a refresh or the periodic backstop reconcile registers added, re-registers changed and unsubscribes removed publishers.
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
    private final ConfigurationRefreshLock refreshLock;
    private final AtomicBoolean started = new AtomicBoolean();
    // only touched inside the synchronized reconcile
    private Set<String> reportedWarnings = Set.of();

    public DataSpaceEventSubscriber(DataSpaceEventsProperties properties, GeneralProperties generalProperties,
                                    PublisherSubscriptionClient client, SubscriptionStateStore store,
                                    @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler, Clock clock,
                                    ConfigurationRefreshLock refreshLock) {
        this.properties = properties;
        this.generalProperties = generalProperties;
        this.client = client;
        this.store = store;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.refreshLock = refreshLock;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        if (started.compareAndSet(false, true)) {
            taskScheduler.schedule(positiveOrDefault(settings().getInitialDelay(), DEFAULT_SUBSCRIBER_INITIAL_DELAY),
                    this::backstopTick);
        }
    }

    /**
     * Schedules a one-shot reconcile. Ignored before startup, whose own reconcile reads the then-current values anyway.
     */
    void onRefresh() {
        if (!started.get()) {
            log.debug("Ignoring a configuration refresh before startup.");
            return;
        }
        // the listener may run before the properties are rebound, so the reconcile runs async and waits for the rebind
        taskScheduler.schedule(Duration.ZERO, this::reconcile);
    }

    // the only scheduler of itself, so there is exactly one chain however many refresh-triggered runs happen
    private void backstopTick() {
        try {
            reconcile();
        } finally {
            scheduleBackstop();
        }
    }

    private void scheduleBackstop() {
        try {
            taskScheduler.schedule(capped(positiveOrDefault(settings().getReconcileInterval(),
                    DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL)), this::backstopTick);
        } catch (RuntimeException e) {
            log.warn("Could not schedule the next periodic reconcile; publisher changes now only apply on a refresh.", e);
        }
    }

    /**
     * Brings the states in line with the configured publishers: adds, re-registers on change, drops removed ones.
     * The only place states are created or removed; synchronized, so overlapping runs cannot duplicate a state.
     */
    synchronized void reconcile() {
        try {
            // both refresh paths rebind under this lock, so the read never sees half-rebound properties
            Reading reading = refreshLock.exclusively(this::read);
            report(reading.warnings());
            if (reading.settingsProblem().isPresent()) {
                store.markUnusableSettings(reading.settingsProblem().get());
                return;
            }
            for (String publisherId : store.publisherIds()) {
                if (!reading.wanted().containsKey(publisherId)) {
                    remove(publisherId);
                }
            }
            reading.wanted().forEach(this::reconcilePublisher);
            store.markReconciled();
        } catch (RuntimeException e) {
            log.warn("Reconciling the publisher subscriptions failed.", e);
        }
    }

    private Reading read() {
        Map<String, Throwable> warnings = new LinkedHashMap<>();
        SubscriberSettings settings = settings();
        String ownId = properties.ownId(generalProperties);
        Optional<String> settingsProblem = SubscriberSettingsValidator.problem(settings, ownId);
        if (settingsProblem.isPresent()) {
            warnings.put("Not subscribing to any publisher: " + settingsProblem.get(), null);
            return new Reading(settingsProblem, Map.of(), warnings);
        }
        Map<String, Wanted> wanted = new LinkedHashMap<>();
        for (Publisher publisher : properties.getPublishers()) {
            try {
                read(publisher, settings, ownId, wanted, warnings);
            } catch (RuntimeException e) {
                // one broken entry (e.g. a null list element) must not keep the others from being subscribed
                String publisherId = publisher == null ? null : publisher.getId();
                warnings.put("Not subscribing to publisher " + publisherId + ": its entry could not be read.", e);
                if (!isBlank(publisherId)) {
                    wanted.putIfAbsent(publisherId, Wanted.skip(INVALID_ENTRY_REASON));
                }
            }
        }
        return new Reading(Optional.empty(), wanted, warnings);
    }

    private static void read(Publisher publisher, SubscriberSettings settings, String ownId, Map<String, Wanted> wanted,
                             Map<String, Throwable> warnings) {
        if (isBlank(publisher.getSubscriptionsUrl())) {
            return;
        }
        String publisherId = publisher.getId();
        if (isBlank(publisherId)) {
            warnings.put("Not subscribing at " + publisher.getSubscriptionsUrl() + ": the publisher entry has no id.", null);
            return;
        }
        if (wanted.containsKey(publisherId)) {
            warnings.put("Publisher " + publisherId + " is configured more than once; only its first entry is subscribed to.",
                    null);
            return;
        }
        Optional<String> skipReason = skipReason(publisher);
        if (skipReason.isPresent()) {
            warnings.put("Not subscribing to publisher " + publisherId + ": " + skipReason.get() + ".", null);
            wanted.put(publisherId, Wanted.skip(skipReason.get()));
            return;
        }
        wanted.put(publisherId, new Wanted(SubscriptionTarget.of(publisher, settings, ownId), null));
    }

    // the backstop re-reads every minute; a lasting misconfiguration is warned about once, not on every run
    private void report(Map<String, Throwable> warnings) {
        warnings.forEach((message, cause) -> {
            if (!reportedWarnings.contains(message)) {
                log.warn(message, cause);
            }
        });
        reportedWarnings = Set.copyOf(warnings.keySet());
    }

    private void reconcilePublisher(String publisherId, Wanted wanted) {
        Optional<PublisherState> existing = store.get(publisherId);
        SubscriptionTarget current = existing.map(PublisherState::target).orElse(null);
        if (wanted.target() == null) {
            if (current != null) {
                log.info("Publisher {} can no longer be subscribed to ({}); dropping its subscription.", publisherId,
                        wanted.skipReason());
                retire(existing.get());
            }
            if (current != null || existing.isEmpty() || !wanted.skipReason().equals(existing.get().status().reason())) {
                store.put(publisherId, PublisherState.skipped(wanted.skipReason()));
            }
            return;
        }
        if (current == null) {
            PublisherState state = PublisherState.pending(wanted.target());
            store.put(publisherId, state);
            register(state, state.generation());
        } else if (!current.equals(wanted.target())) {
            retarget(existing.get(), current, wanted.target());
        }
    }

    private void retarget(PublisherState state, SubscriptionTarget current, SubscriptionTarget changed) {
        boolean rekeyed = !current.sameKeyAs(changed);
        String oldId;
        long generation;
        synchronized (state) {
            oldId = state.subscriptionId();
            generation = state.retarget(changed, rekeyed);
        }
        if (!rekeyed) {
            log.info("Subscription settings for publisher {} changed; renewing in place.", changed.publisherId());
            register(state, generation);
            return;
        }
        log.info("Subscription key for publisher {} changed; re-registering at {}.", changed.publisherId(),
                changed.subscriptionsUrl());
        if (oldId == null) {
            register(state, generation);
            return;
        }
        // deleting first frees the old subscription's slot under the publisher's per-subscriber cap
        unsubscribe(current, oldId).then(Mono.fromRunnable(() -> register(state, generation))).subscribe();
    }

    private void remove(String publisherId) {
        store.get(publisherId).ifPresent(state -> {
            store.remove(publisherId);
            retire(state);
            if (state.target() != null) {
                log.info("Publisher {} is no longer configured for subscription; dropped its subscription.", publisherId);
            }
        });
    }

    // stops the loop and removes the subscription at the publisher if its id is known; the lease expires anyway otherwise
    private void retire(PublisherState state) {
        SubscriptionTarget target;
        String subscriptionId;
        synchronized (state) {
            state.cancel();
            target = state.target();
            subscriptionId = state.subscriptionId();
        }
        if (target != null && subscriptionId != null) {
            unsubscribe(target, subscriptionId).subscribe();
        }
    }

    private Mono<Void> unsubscribe(SubscriptionTarget target, String subscriptionId) {
        return Mono.defer(() -> client.unsubscribe(target, subscriptionId))
                .doOnSuccess(ignored -> log.info("Removed subscription {} at {}.", subscriptionId, target.subscriptionsUrl()))
                .onErrorResume(error -> {
                    log.info("Could not remove subscription {} at {}: {}. It ends when its lease expires.", subscriptionId,
                            target.subscriptionsUrl(), DownstreamError.reason(error));
                    return Mono.empty();
                });
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

    private record Reading(Optional<String> settingsProblem, Map<String, Wanted> wanted, Map<String, Throwable> warnings) {
    }

    // either the target to subscribe with or why the publisher is skipped
    private record Wanted(@Nullable SubscriptionTarget target, @Nullable String skipReason) {

        static Wanted skip(String reason) {
            return new Wanted(null, reason);
        }
    }

    /**
     * Forwards every RefreshEvent to the subscriber; a separate bean, because one class cannot listen to two event types.
     */
    @Singleton
    @Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
    @RequiredArgsConstructor
    static class RefreshListener implements ApplicationEventListener<RefreshEvent> {

        private final DataSpaceEventSubscriber subscriber;

        @Override
        public void onApplicationEvent(RefreshEvent event) {
            subscriber.onRefresh();
        }
    }
}
