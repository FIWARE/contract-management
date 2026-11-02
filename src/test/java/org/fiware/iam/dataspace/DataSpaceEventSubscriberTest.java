package org.fiware.iam.dataspace;

import io.micronaut.health.HealthStatus;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.DataSpaceEventsProperties.SubscriberSettings;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dataspace.PublisherSubscriptionClient.Registration;
import org.fiware.iam.dataspace.SubscriptionStateStore.Phase;
import org.fiware.iam.dataspace.SubscriptionStateStore.PublisherStatus;
import org.fiware.iam.exception.EventSubscriptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Checks registration, retry with backoff, renewal and the resulting health of {@link DataSpaceEventSubscriber}.
 */
class DataSpaceEventSubscriberTest {

    private static final String OTHER_PUBLISHER_ID = "did:web:other.org";
    private static final String OTHER_URL = "https://cm.other.org/subscriptions";
    private static final String OTHER_ORIGIN = "cm.other.org";
    private static final String OTHER_TYPE = "org.fiware.dataspace.other";
    private static final String OTHER_SINK = "https://cm-new.consumer.org/events";
    private static final String GLOBAL_CLIENT_ID = "subscriber-client";
    private static final String OVERRIDE_CLIENT_ID = "provider-specific-client";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final String OWN_ID = "did:web:consumer.org";
    private static final String SINK = "https://cm.consumer.org/events";
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final String PUBLISHER_ID = "did:web:provider.org";
    private static final String SUBSCRIPTIONS_URL = "https://cm.provider.org/subscriptions";
    private static final String ORIGIN = "cm.provider.org";
    private static final String TYPE = "org.fiware.dataspace.example";
    private static final String SUBSCRIPTION_ID = "subscription-1";
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(7);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(40);
    private static final int FIRST_CAPPED_ATTEMPT = 3;
    private static final Duration LEASE = Duration.ofHours(1);
    private static final Duration HALF_LEASE = LEASE.dividedBy(2);
    private static final Duration CONFLICT_RENEWAL_INTERVAL = Duration.ofMinutes(20);
    private static final Duration RECONCILE_INTERVAL = Duration.ofMinutes(3);
    private static final Duration DEFAULT_MIN_RENEWAL_DELAY = DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY;
    private static final RuntimeException PUBLISHER_DOWN =
            new EventSubscriptionException(PUBLISHER_ID, SUBSCRIPTIONS_URL, "connection refused", null, null);

    private final DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
    private final PublisherSubscriptionClient client = mock(PublisherSubscriptionClient.class);
    private final SubscriptionStateStore store = new SubscriptionStateStore();
    private final RecordingTaskScheduler scheduler = new RecordingTaskScheduler();
    private final MutableClock clock = new MutableClock(NOW);
    private final DataSpaceSubscriptionHealthIndicator indicator = new DataSpaceSubscriptionHealthIndicator(store, clock);
    private DataSpaceEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        properties.setOwnParticipantId(OWN_ID);
        SubscriberSettings settings = properties.getSubscriber();
        settings.setEnabled(true);
        settings.setSink(SINK);
        settings.getProtocolSettings().setClientId(PROTOCOL_CLIENT_ID);
        settings.setInitialDelay(INITIAL_DELAY);
        settings.setConflictRenewalInterval(CONFLICT_RENEWAL_INTERVAL);
        settings.getRetry().setInitialBackoff(INITIAL_BACKOFF);
        settings.getRetry().setMaxBackoff(MAX_BACKOFF);
        settings.setReconcileInterval(RECONCILE_INTERVAL);
        properties.getPublishers().add(publisher(PUBLISHER_ID, SUBSCRIPTIONS_URL, List.of(TYPE), ORIGIN));
        subscriber = new DataSpaceEventSubscriber(properties, mock(GeneralProperties.class), client, store, scheduler, clock,
                new ConfigurationRefreshLock());
    }

    static Stream<Arguments> backoffs() {
        return Stream.of(
                Arguments.of("first retry", INITIAL_BACKOFF, MAX_BACKOFF, 0, INITIAL_BACKOFF),
                Arguments.of("second retry", INITIAL_BACKOFF, MAX_BACKOFF, 1, INITIAL_BACKOFF.multipliedBy(2)),
                Arguments.of("first capped attempt", INITIAL_BACKOFF, MAX_BACKOFF, FIRST_CAPPED_ATTEMPT, MAX_BACKOFF),
                Arguments.of("attempt 100", INITIAL_BACKOFF, MAX_BACKOFF, 100, MAX_BACKOFF),
                Arguments.of("attempt Integer.MAX_VALUE", INITIAL_BACKOFF, MAX_BACKOFF, Integer.MAX_VALUE, MAX_BACKOFF),
                Arguments.of("initial above max", MAX_BACKOFF.multipliedBy(2), MAX_BACKOFF, 0, MAX_BACKOFF),
                Arguments.of("zero initial falls back", Duration.ZERO, MAX_BACKOFF, 0,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF),
                Arguments.of("negative max falls back", INITIAL_BACKOFF, Duration.ofSeconds(-1), Integer.MAX_VALUE,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF),
                Arguments.of("huge max saturates without overflow", INITIAL_BACKOFF, Duration.ofSeconds(Long.MAX_VALUE),
                        Integer.MAX_VALUE, Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backoffs")
    void backoffDoublesAndSaturates(String description, Duration initialBackoff, Duration maxBackoff, int attempt,
                                    Duration expected) {
        properties.getSubscriber().getRetry().setInitialBackoff(initialBackoff);
        properties.getSubscriber().getRetry().setMaxBackoff(maxBackoff);

        assertEquals(expected, subscriber.backoff(attempt));
    }

    @Test
    void unavailablePublisherIsRetriedWithBackoffUntilTheFirstSuccess() {
        int failures = 6;
        List<Mono<Registration>> answers = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            answers.add(Mono.error(PUBLISHER_DOWN));
        }
        answers.add(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        answers.add(Mono.error(PUBLISHER_DOWN));
        stubSubscribe(answers);
        start();

        List<Duration> retryDelays = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            RecordingTaskScheduler.Task retry = onlyPublisherTask();
            retryDelays.add(retry.delay());
            assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status(), "after failure " + (i + 1));
            runPublisherTask();
        }

        assertEquals(List.of(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2), INITIAL_BACKOFF.multipliedBy(4), MAX_BACKOFF,
                MAX_BACKOFF, MAX_BACKOFF), retryDelays);
        assertEquals(HealthStatus.UP, status());
        assertEquals(HALF_LEASE, onlyPublisherTask().delay());

        runPublisherTask();

        assertEquals(INITIAL_BACKOFF, onlyPublisherTask().delay(), "a failed renewal starts the backoff again");
        assertEquals(HealthStatus.UP, status(), "the lease is still valid");
    }

    static Stream<Arguments> successes() {
        return Stream.of(
                Arguments.of("registered", Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE)), HALF_LEASE),
                Arguments.of("409", Registration.CONFLICT, CONFLICT_RENEWAL_INTERVAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successes")
    void successResetsTheBackoff(String description, Registration success, Duration expectedRenewal) {
        stubSubscribe(List.of(Mono.error(PUBLISHER_DOWN), Mono.error(PUBLISHER_DOWN), Mono.just(success),
                Mono.error(PUBLISHER_DOWN)));
        start();
        runPublisherTask();
        assertEquals(INITIAL_BACKOFF.multipliedBy(2), onlyPublisherTask().delay());

        runPublisherTask();
        assertEquals(expectedRenewal, onlyPublisherTask().delay());

        runPublisherTask();
        assertEquals(INITIAL_BACKOFF, onlyPublisherTask().delay());
    }

    @Test
    void synchronousClientFailureIsRetried() {
        when(client.subscribe(any())).thenThrow(new IllegalStateException("broken"));
        start();

        assertEquals(INITIAL_BACKOFF, onlyPublisherTask().delay());
        runPublisherTask();
        assertEquals(INITIAL_BACKOFF.multipliedBy(2), onlyPublisherTask().delay());
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
    }

    @Test
    void emptyAnswerIsRetried() {
        when(client.subscribe(any())).thenReturn(Mono.empty());
        start();

        assertEquals(INITIAL_BACKOFF, onlyPublisherTask().delay());
    }

    static Stream<Arguments> renewals() {
        return Stream.of(
                Arguments.of("default fraction", DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RENEWAL_FRACTION, LEASE, HALF_LEASE),
                Arguments.of("quarter", 0.25, LEASE, LEASE.dividedBy(4)),
                Arguments.of("zero falls back", 0.0, LEASE, HALF_LEASE),
                Arguments.of("one falls back", 1.0, LEASE, HALF_LEASE),
                Arguments.of("above one falls back", 1.5, LEASE, HALF_LEASE),
                Arguments.of("negative falls back", -0.5, LEASE, HALF_LEASE),
                Arguments.of("tiny lease", 0.5, Duration.ofSeconds(4), DEFAULT_MIN_RENEWAL_DELAY),
                Arguments.of("expiry in the past", 0.5, Duration.ofHours(-1), DEFAULT_MIN_RENEWAL_DELAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renewals")
    void renewalIsScheduledBeforeExpiry(String description, double fraction, Duration lease, Duration expectedDelay) {
        properties.getSubscriber().setRenewalFraction(fraction);
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(lease)))));
        start();

        assertEquals(expectedDelay, onlyPublisherTask().delay());

        runPublisherTask();
        verify(client, times(2)).subscribe(any());
    }

    @Test
    void renewalFailingPastExpiryDegradesUntilTheNextSuccess() {
        stubSubscribe(List.of(
                Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))),
                Mono.error(PUBLISHER_DOWN),
                Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE.multipliedBy(2))))));
        start();
        assertEquals(HealthStatus.UP, status());

        clock.advance(HALF_LEASE);
        runPublisherTask();
        assertEquals(HealthStatus.UP, status(), "a failed renewal before expiry keeps the lease");

        clock.advance(HALF_LEASE);
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
        assertEquals(DataSpaceSubscriptionHealthIndicator.EXPIRED, publisherDetails().get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL));

        runPublisherTask();
        assertEquals(HealthStatus.UP, status());
        assertEquals(Phase.REGISTERED.name(), publisherDetails().get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL));
    }

    @Test
    void conflictCountsAsSubscribed() {
        stubSubscribe(List.of(Mono.just(Registration.CONFLICT)));
        start();

        assertEquals(HealthStatus.UP, status());
        assertEquals(new PublisherStatus(Phase.CONFLICT, NOW.plus(CONFLICT_RENEWAL_INTERVAL).plus(MAX_BACKOFF), null),
                store.snapshot().get(PUBLISHER_ID));
        assertEquals(CONFLICT_RENEWAL_INTERVAL, onlyPublisherTask().delay());

        runPublisherTask();
        verify(client, times(2)).subscribe(any());
    }

    @Test
    void failingConflictReRegistrationDegradesOnlyAfterMaxBackoff() {
        stubSubscribe(List.of(Mono.just(Registration.CONFLICT), Mono.error(PUBLISHER_DOWN)));
        start();

        clock.advance(CONFLICT_RENEWAL_INTERVAL);
        runPublisherTask();
        assertEquals(HealthStatus.UP, status(), "the re-registration is still being retried");

        clock.advance(MAX_BACKOFF);
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
        assertEquals(DataSpaceSubscriptionHealthIndicator.EXPIRED, publisherDetails().get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL));
    }

    static Stream<Arguments> oversizedDelays() {
        Duration oversized = Duration.ofDays(365L * 1000);
        return Stream.of(
                Arguments.of("unlimited lease", (Consumer<SubscriberSettings>) settings -> {
                }, Mono.just(Registration.registered(SUBSCRIPTION_ID, Instant.parse("9999-12-31T23:59:59Z"))), HealthStatus.UP),
                Arguments.of("oversized conflict interval",
                        (Consumer<SubscriberSettings>) settings -> settings.setConflictRenewalInterval(oversized),
                        Mono.just(Registration.CONFLICT), HealthStatus.UP),
                Arguments.of("oversized max backoff", (Consumer<SubscriberSettings>) settings -> {
                    settings.getRetry().setInitialBackoff(oversized);
                    settings.getRetry().setMaxBackoff(oversized);
                }, Mono.error(PUBLISHER_DOWN), DataSpaceSubscriptionHealthIndicator.DEGRADED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedDelays")
    void oversizedDelaysAreCapped(String description, Consumer<SubscriberSettings> configure, Mono<Registration> answer,
                                  HealthStatus expectedStatus) {
        configure.accept(properties.getSubscriber());
        stubSubscribe(List.of(answer));
        start();

        assertEquals(DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY, onlyPublisherTask().delay());
        assertEquals(expectedStatus, status());
        verify(client).subscribe(any());
    }

    @Test
    void registrationWithoutExpiryIsRenewedLikeAConflictButStaysRegistered() {
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, null))));
        start();

        PublisherStatus status = store.snapshot().get(PUBLISHER_ID);
        assertEquals(Phase.REGISTERED, status.phase());
        assertEquals(NOW.plus(CONFLICT_RENEWAL_INTERVAL).plus(MAX_BACKOFF), status.validUntil());
        assertEquals(SUBSCRIPTION_ID, store.get(PUBLISHER_ID).orElseThrow().subscriptionId());
        assertEquals(CONFLICT_RENEWAL_INTERVAL, onlyPublisherTask().delay());
        assertEquals(HealthStatus.UP, status());
    }

    @Test
    void startupSchedulesOneReconcileThatRegistersEachPublisherOnce() {
        String secondPublisher = "did:web:other.org";
        String secondUrl = "https://cm.other.org/subscriptions";
        properties.getPublishers().add(publisher(secondPublisher, secondUrl, List.of(TYPE), "cm.other.org"));
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));

        subscriber.onApplicationEvent(startupEvent());
        subscriber.onApplicationEvent(startupEvent());

        assertEquals(List.of(INITIAL_DELAY), delays(scheduler.pending()));
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status(), "nothing is registered before the first reconcile");
        scheduler.runNext();

        ArgumentCaptor<SubscriptionTarget> targets = ArgumentCaptor.forClass(SubscriptionTarget.class);
        verify(client, times(2)).subscribe(targets.capture());
        assertEquals(List.of(SUBSCRIPTIONS_URL, secondUrl),
                targets.getAllValues().stream().map(SubscriptionTarget::subscriptionsUrl).toList());
        SubscriptionTarget first = targets.getAllValues().get(0);
        assertEquals(OWN_ID, first.subscriberId());
        assertEquals(SINK, first.sink());
        assertEquals(List.of(TYPE), first.types());
        assertEquals(HealthStatus.UP, status());
    }

    @Test
    void unsubscribablePublishersAreNotContacted() {
        properties.getPublishers().add(publisher("did:web:listener-only.org", null, List.of(TYPE), "cm.listener-only.org"));
        properties.getPublishers().add(publisher("did:web:blank-url.org", " ", List.of(TYPE), "cm.blank-url.org"));
        properties.getPublishers().add(publisher("did:web:no-types.org", "https://cm.no-types.org/subscriptions", List.of(),
                "cm.no-types.org"));
        properties.getPublishers().add(publisher("did:web:no-origin.org", "https://cm.no-origin.org/subscriptions",
                List.of(TYPE), " "));
        properties.getPublishers().add(publisher(PUBLISHER_ID, "https://cm.duplicate.org/subscriptions", List.of(TYPE), ORIGIN));
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE)))));
        start();

        verify(client).subscribe(any());
        assertEquals(HealthStatus.UP, status());
        Map<String, PublisherStatus> snapshot = store.snapshot();
        assertEquals(List.of("did:web:no-origin.org", "did:web:no-types.org", PUBLISHER_ID), List.copyOf(snapshot.keySet()));
        assertEquals(Phase.SKIPPED, snapshot.get("did:web:no-types.org").phase());
        assertTrue(snapshot.get("did:web:no-types.org").reason().contains("types"));
        assertEquals(Phase.SKIPPED, snapshot.get("did:web:no-origin.org").phase());
        assertTrue(snapshot.get("did:web:no-origin.org").reason().contains("origin"));
    }

    static Stream<Arguments> unusableSettings() {
        return Stream.of(
                Arguments.of("blank sink", (Consumer<DataSpaceEventsProperties>) p -> p.getSubscriber().setSink(""),
                        SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("no own id", (Consumer<DataSpaceEventsProperties>) p -> p.setOwnParticipantId(" "),
                        SubscriberSettingsValidator.OWN_ID_KEY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableSettings")
    void unusableSubscriberSettingsRegisterNothing(String description, Consumer<DataSpaceEventsProperties> change,
                                                  String offendingKey) {
        change.accept(properties);
        start();

        verify(client, never()).subscribe(any());
        assertTrue(publisherTasks().isEmpty());
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
        Map<?, ?> details = (Map<?, ?>) indicator.result().getDetails();
        assertTrue(details.get(DataSpaceSubscriptionHealthIndicator.SETTINGS_DETAIL).toString().contains(offendingKey));
    }

    @Test
    void brokenEntriesDoNotKeepTheOthersFromBeingSubscribed() {
        String brokenId = "did:web:broken.org";
        Publisher broken = publisher(brokenId, "https://cm.broken.org/subscriptions", List.of(TYPE), "cm.broken.org");
        // a YAML '~' list element, which List.copyOf rejects
        broken.getTypes().add(null);
        properties.getPublishers().add(0, broken);
        properties.getPublishers().add(0, null);
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE)))));
        start();

        ArgumentCaptor<SubscriptionTarget> target = ArgumentCaptor.forClass(SubscriptionTarget.class);
        verify(client).subscribe(target.capture());
        assertEquals(PUBLISHER_ID, target.getValue().publisherId());
        PublisherStatus brokenStatus = store.snapshot().get(brokenId);
        assertEquals(Phase.SKIPPED, brokenStatus.phase());
        assertEquals(DataSpaceEventSubscriber.INVALID_ENTRY_REASON, brokenStatus.reason());
        assertEquals(HealthStatus.UP, status());
    }

    @Test
    void failedReconcileIsNotReportedAsUp() {
        properties.setPublishers(null);
        start();

        verify(client, never()).subscribe(any());
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
    }

    @Test
    void staleCallbackIsIgnored() {
        Sinks.One<Registration> answer = Sinks.one();
        when(client.subscribe(any())).thenReturn(answer.asMono());
        start();
        PublisherState state = store.get(PUBLISHER_ID).orElseThrow();

        state.cancel();
        answer.tryEmitValue(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE)));

        assertEquals(Phase.PENDING, state.status().phase());
        assertNull(state.subscriptionId());
        assertTrue(publisherTasks().isEmpty());
    }

    @Test
    void cancelledRetryDoesNotRegister() {
        stubSubscribe(List.of(Mono.error(PUBLISHER_DOWN)));
        start();
        RecordingTaskScheduler.Task retry = onlyPublisherTask();

        store.get(PUBLISHER_ID).orElseThrow().cancel();

        assertTrue(retry.isCancelled());
        assertFalse(publisherTasks().contains(retry));
        verify(client).subscribe(any());
    }

    static Stream<Arguments> refreshChanges() {
        Consumer<DataSpaceEventsProperties> asConfigured = p -> {
        };
        Consumer<DataSpaceEventsProperties> withOverride = p -> p.getPublishers().get(0).setClientId(OVERRIDE_CLIENT_ID);
        return Stream.of(
                Arguments.of("add", asConfigured,
                        change(p -> p.getPublishers().add(publisher(OTHER_PUBLISHER_ID, OTHER_URL, List.of(TYPE), OTHER_ORIGIN))),
                        OTHER_PUBLISHER_ID, field(SubscriptionTarget::subscriptionsUrl), OTHER_URL, false, Phase.REGISTERED),
                Arguments.of("change types", asConfigured, change(p -> p.getPublishers().get(0).setTypes(List.of(OTHER_TYPE))),
                        PUBLISHER_ID, field(SubscriptionTarget::types), List.of(OTHER_TYPE), false, Phase.REGISTERED),
                Arguments.of("change origin", asConfigured, change(p -> p.getPublishers().get(0).setOrigin(OTHER_ORIGIN)),
                        PUBLISHER_ID, field(SubscriptionTarget::origin), OTHER_ORIGIN, false, Phase.REGISTERED),
                Arguments.of("change URL", asConfigured, change(p -> p.getPublishers().get(0).setSubscriptionsUrl(OTHER_URL)),
                        PUBLISHER_ID, field(SubscriptionTarget::subscriptionsUrl), OTHER_URL, true, Phase.REGISTERED),
                Arguments.of("change sink", asConfigured, change(p -> p.getSubscriber().setSink(OTHER_SINK)),
                        PUBLISHER_ID, field(SubscriptionTarget::sink), OTHER_SINK, true, Phase.REGISTERED),
                Arguments.of("change global client id", asConfigured, change(p -> p.getSubscriber().setClientId(GLOBAL_CLIENT_ID)),
                        PUBLISHER_ID, field(SubscriptionTarget::clientId), GLOBAL_CLIENT_ID, false, Phase.REGISTERED),
                Arguments.of("blank override client id", withOverride, change(p -> {
                            p.getSubscriber().setClientId(GLOBAL_CLIENT_ID);
                            p.getPublishers().get(0).setClientId("");
                        }), PUBLISHER_ID, field(SubscriptionTarget::clientId), GLOBAL_CLIENT_ID, false, Phase.REGISTERED),
                Arguments.of("remove", asConfigured, change(p -> p.getPublishers().clear()), PUBLISHER_ID, null, null, true, null),
                Arguments.of("remove URL", asConfigured, change(p -> p.getPublishers().get(0).setSubscriptionsUrl("")),
                        PUBLISHER_ID, null, null, true, null),
                Arguments.of("remove origin", asConfigured, change(p -> p.getPublishers().get(0).setOrigin("")),
                        PUBLISHER_ID, null, null, true, Phase.SKIPPED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refreshChanges")
    void refreshChangesAreApplied(String description, Consumer<DataSpaceEventsProperties> initial,
                                  Consumer<DataSpaceEventsProperties> change, String publisherId,
                                  Function<SubscriptionTarget, Object> changedField, Object expectedValue,
                                  boolean unsubscribesOld, Phase expectedPhase) {
        initial.accept(properties);
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        when(client.unsubscribe(any(), any())).thenReturn(Mono.empty());
        start();
        SubscriptionTarget before = store.get(PUBLISHER_ID).orElseThrow().target();
        reset(client);
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        when(client.unsubscribe(any(), any())).thenReturn(Mono.empty());

        change.accept(properties);
        subscriber.reconcile();

        InOrder order = inOrder(client);
        if (unsubscribesOld) {
            order.verify(client).unsubscribe(before, SUBSCRIPTION_ID);
        }
        if (changedField != null) {
            ArgumentCaptor<SubscriptionTarget> target = ArgumentCaptor.forClass(SubscriptionTarget.class);
            order.verify(client).subscribe(target.capture());
            assertEquals(publisherId, target.getValue().publisherId());
            assertEquals(expectedValue, changedField.apply(target.getValue()));
        }
        verifyNoMoreInteractions(client);
        assertEquals(expectedPhase, Optional.ofNullable(store.snapshot().get(publisherId)).map(PublisherStatus::phase).orElse(null));
        assertEquals(expectedPhase != null, details().containsKey(publisherId), "health details");
        assertEquals(HealthStatus.UP, status());
        long registered = store.snapshot().values().stream().filter(status -> status.phase() == Phase.REGISTERED).count();
        assertEquals(registered, publisherTasks().size(), "an old loop is cancelled, every registered publisher renews once");
    }

    @Test
    void unchangedPropertiesMakeNoClientCall() {
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        start();
        reset(client);
        RecordingTaskScheduler.Task renewal = onlyPublisherTask();

        subscriber.reconcile();

        verifyNoMoreInteractions(client);
        assertEquals(List.of(renewal), publisherTasks());
    }

    @Test
    void urlChangeAfterAConflictDeletesNothing() {
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.CONFLICT));
        start();

        properties.getPublishers().get(0).setSubscriptionsUrl(OTHER_URL);
        subscriber.reconcile();

        verify(client, never()).unsubscribe(any(), any());
        verify(client, times(2)).subscribe(any());
    }

    @Test
    void failedUnsubscribeDoesNotStopTheReRegistration() {
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        when(client.unsubscribe(any(), any())).thenReturn(Mono.error(PUBLISHER_DOWN));
        start();

        properties.getSubscriber().setSink(OTHER_SINK);
        subscriber.reconcile();

        ArgumentCaptor<SubscriptionTarget> targets = ArgumentCaptor.forClass(SubscriptionTarget.class);
        verify(client, times(2)).subscribe(targets.capture());
        assertEquals(OTHER_SINK, targets.getValue().sink());
        assertEquals(HealthStatus.UP, status());
    }

    static Stream<Arguments> rekeyingChanges() {
        return Stream.of(
                Arguments.of("change sink", change(p -> p.getSubscriber().setSink(OTHER_SINK))),
                Arguments.of("change URL", change(p -> p.getPublishers().get(0).setSubscriptionsUrl(OTHER_URL))),
                Arguments.of("change own id", change(p -> p.setOwnParticipantId("did:web:renamed.org"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rekeyingChanges")
    void rekeyedSubscriptionIsPendingUntilTheNewRegistrationSucceeds(String description,
                                                                    Consumer<DataSpaceEventsProperties> change) {
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))), Mono.error(PUBLISHER_DOWN),
                Mono.just(Registration.registered("subscription-2", NOW.plus(LEASE)))));
        when(client.unsubscribe(any(), any())).thenReturn(Mono.empty());
        start();

        change.accept(properties);
        subscriber.reconcile();

        PublisherState state = store.get(PUBLISHER_ID).orElseThrow();
        assertEquals(new PublisherStatus(Phase.PENDING, null, null), state.status());
        assertNull(state.subscriptionId(), "the old id is never reused");
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
        assertEquals(INITIAL_BACKOFF, onlyPublisherTask().delay(), "the attempt counter starts over");

        runPublisherTask();
        assertEquals(HealthStatus.UP, status());
        assertEquals("subscription-2", state.subscriptionId());
    }

    @Test
    void inPlaceRenewalKeepsTheOldLeaseWhileItFails() {
        stubSubscribe(List.of(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))), Mono.error(PUBLISHER_DOWN)));
        start();

        properties.getPublishers().get(0).setTypes(List.of(OTHER_TYPE));
        subscriber.reconcile();

        assertEquals(new PublisherStatus(Phase.REGISTERED, NOW.plus(LEASE), null), store.snapshot().get(PUBLISHER_ID));
        assertEquals(HealthStatus.UP, status(), "the old lease still delivers");
        clock.advance(LEASE);
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
    }

    @Test
    void skippedPublisherIsSubscribedOnceItBecomesValid() {
        properties.getPublishers().get(0).setOrigin("");
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        start();
        assertEquals(Phase.SKIPPED, store.snapshot().get(PUBLISHER_ID).phase());

        properties.getPublishers().get(0).setTypes(List.of());
        subscriber.reconcile();
        assertTrue(store.snapshot().get(PUBLISHER_ID).reason().contains("types"), "the skip reason follows the entry");

        properties.getPublishers().get(0).setTypes(List.of(TYPE));
        properties.getPublishers().get(0).setOrigin(ORIGIN);
        subscriber.reconcile();

        verify(client).subscribe(any());
        assertEquals(Phase.REGISTERED, store.snapshot().get(PUBLISHER_ID).phase());
    }

    @Test
    void refreshBeforeStartupSchedulesNothing() {
        refreshListener().onApplicationEvent(new RefreshEvent());

        assertTrue(scheduler.pending().isEmpty());
        verifyZeroInteractions(client);
    }

    @Test
    void refreshAndBackstopCreateOneStatePerAddedPublisher() {
        when(client.subscribe(any())).thenReturn(Mono.just(Registration.registered(SUBSCRIPTION_ID, NOW.plus(LEASE))));
        start();
        properties.getPublishers().add(publisher(OTHER_PUBLISHER_ID, OTHER_URL, List.of(TYPE), OTHER_ORIGIN));

        refreshListener().onApplicationEvent(new RefreshEvent());
        scheduler.run(onlyTaskWithDelay(Duration.ZERO));
        scheduler.run(onlyTaskWithDelay(RECONCILE_INTERVAL));

        ArgumentCaptor<SubscriptionTarget> targets = ArgumentCaptor.forClass(SubscriptionTarget.class);
        verify(client, times(2)).subscribe(targets.capture());
        assertEquals(List.of(PUBLISHER_ID, OTHER_PUBLISHER_ID),
                targets.getAllValues().stream().map(SubscriptionTarget::publisherId).toList());
        assertEquals(List.of(OTHER_PUBLISHER_ID, PUBLISHER_ID), List.copyOf(store.snapshot().keySet()));
        assertEquals(2, publisherTasks().size(), "one renewal per publisher");
    }

    @Test
    void refreshesNeverMultiplyTheBackstop() {
        int refreshes = 5;
        start();

        for (int i = 0; i < refreshes; i++) {
            refreshListener().onApplicationEvent(new RefreshEvent());
            scheduler.run(onlyTaskWithDelay(Duration.ZERO));
        }

        assertEquals(1, backstopTasks().size());
    }

    static Stream<Arguments> reconcileIntervals() {
        Duration refreshed = Duration.ofMinutes(7);
        return Stream.of(
                Arguments.of("refreshed", refreshed, refreshed),
                Arguments.of("zero falls back", Duration.ZERO, DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL),
                Arguments.of("negative falls back", Duration.ofMinutes(-1),
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL),
                Arguments.of("oversized is capped", Duration.ofDays(365L * 1000), DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reconcileIntervals")
    void backstopReschedulesWithTheCurrentIntervalEvenAfterAFailedReconcile(String description, Duration configured,
                                                                            Duration expected) {
        properties.getPublishers().clear();
        start();
        RecordingTaskScheduler.Task backstop = backstopTasks().get(0);
        properties.getSubscriber().setReconcileInterval(configured);
        properties.setPublishers(null);

        scheduler.run(backstop);
        scheduler.run(onlyTaskWithDelay(expected));

        assertEquals(expected, onlyTaskWithDelay(expected).delay());
        assertEquals(1, scheduler.pending().size(), "only the backstop is pending");
    }

    private void start() {
        subscriber.onApplicationEvent(startupEvent());
        assertEquals(List.of(INITIAL_DELAY), delays(scheduler.pending()));
        scheduler.runNext();
        assertEquals(1, backstopTasks().size(), "the startup reconcile starts the backstop");
    }

    @SuppressWarnings("unchecked")
    private void stubSubscribe(List<Mono<Registration>> answers) {
        when(client.subscribe(any())).thenReturn(answers.get(0), answers.subList(1, answers.size()).toArray(Mono[]::new));
    }

    private RecordingTaskScheduler.Task onlyPublisherTask() {
        List<RecordingTaskScheduler.Task> pending = publisherTasks();
        assertEquals(1, pending.size(), "pending publisher tasks");
        return pending.get(0);
    }

    private void runPublisherTask() {
        scheduler.run(publisherTasks().stream().findFirst().orElseThrow(() -> new AssertionError("No publisher task.")));
    }

    // the backstop is told apart by its delay, which no publisher task uses in these tests
    private List<RecordingTaskScheduler.Task> publisherTasks() {
        return scheduler.pending().stream().filter(task -> !task.delay().equals(RECONCILE_INTERVAL)).toList();
    }

    private List<RecordingTaskScheduler.Task> backstopTasks() {
        return scheduler.pending().stream().filter(task -> task.delay().equals(RECONCILE_INTERVAL)).toList();
    }

    private RecordingTaskScheduler.Task onlyTaskWithDelay(Duration delay) {
        List<RecordingTaskScheduler.Task> matching = scheduler.pending().stream().filter(task -> task.delay().equals(delay)).toList();
        assertEquals(1, matching.size(), "pending tasks with delay " + delay);
        return matching.get(0);
    }

    private static List<Duration> delays(List<RecordingTaskScheduler.Task> tasks) {
        return tasks.stream().map(RecordingTaskScheduler.Task::delay).toList();
    }

    private DataSpaceEventSubscriber.RefreshListener refreshListener() {
        return new DataSpaceEventSubscriber.RefreshListener(subscriber);
    }

    private Map<?, ?> details() {
        return (Map<?, ?>) indicator.result().getDetails();
    }

    private static Consumer<DataSpaceEventsProperties> change(Consumer<DataSpaceEventsProperties> change) {
        return change;
    }

    private static Function<SubscriptionTarget, Object> field(Function<SubscriptionTarget, Object> field) {
        return field;
    }

    private HealthStatus status() {
        return indicator.result().getStatus();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publisherDetails() {
        return ((Map<String, Map<String, Object>>) indicator.result().getDetails()).get(PUBLISHER_ID);
    }

    private static ServerStartupEvent startupEvent() {
        return new ServerStartupEvent(mock(EmbeddedServer.class));
    }

    private static Publisher publisher(String id, String subscriptionsUrl, List<String> types, String origin) {
        Publisher publisher = new Publisher();
        publisher.setId(id);
        publisher.setSubscriptionsUrl(subscriptionsUrl);
        publisher.setTypes(new ArrayList<>(types));
        publisher.setOrigin(origin);
        return publisher;
    }
}
