package org.fiware.iam.dataspace;

import io.micronaut.health.HealthStatus;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks registration, retry with backoff, renewal and the resulting health of {@link DataSpaceEventSubscriber}.
 */
class DataSpaceEventSubscriberTest {

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
        properties.getPublishers().add(publisher(PUBLISHER_ID, SUBSCRIPTIONS_URL, List.of(TYPE), ORIGIN));
        subscriber = new DataSpaceEventSubscriber(properties, mock(GeneralProperties.class), client, store, scheduler, clock);
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
            RecordingTaskScheduler.Task retry = onlyPendingTask();
            retryDelays.add(retry.delay());
            assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status(), "after failure " + (i + 1));
            scheduler.runNext();
        }

        assertEquals(List.of(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2), INITIAL_BACKOFF.multipliedBy(4), MAX_BACKOFF,
                MAX_BACKOFF, MAX_BACKOFF), retryDelays);
        assertEquals(HealthStatus.UP, status());
        assertEquals(HALF_LEASE, onlyPendingTask().delay());

        scheduler.runNext();

        assertEquals(INITIAL_BACKOFF, onlyPendingTask().delay(), "a failed renewal starts the backoff again");
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
        scheduler.runNext();
        assertEquals(INITIAL_BACKOFF.multipliedBy(2), onlyPendingTask().delay());

        scheduler.runNext();
        assertEquals(expectedRenewal, onlyPendingTask().delay());

        scheduler.runNext();
        assertEquals(INITIAL_BACKOFF, onlyPendingTask().delay());
    }

    @Test
    void synchronousClientFailureIsRetried() {
        when(client.subscribe(any())).thenThrow(new IllegalStateException("broken"));
        start();

        assertEquals(INITIAL_BACKOFF, onlyPendingTask().delay());
        scheduler.runNext();
        assertEquals(INITIAL_BACKOFF.multipliedBy(2), onlyPendingTask().delay());
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
    }

    @Test
    void emptyAnswerIsRetried() {
        when(client.subscribe(any())).thenReturn(Mono.empty());
        start();

        assertEquals(INITIAL_BACKOFF, onlyPendingTask().delay());
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

        assertEquals(expectedDelay, onlyPendingTask().delay());

        scheduler.runNext();
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
        scheduler.runNext();
        assertEquals(HealthStatus.UP, status(), "a failed renewal before expiry keeps the lease");

        clock.advance(HALF_LEASE);
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, status());
        assertEquals(DataSpaceSubscriptionHealthIndicator.EXPIRED, publisherDetails().get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL));

        scheduler.runNext();
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
        assertEquals(CONFLICT_RENEWAL_INTERVAL, onlyPendingTask().delay());

        scheduler.runNext();
        verify(client, times(2)).subscribe(any());
    }

    @Test
    void failingConflictReRegistrationDegradesOnlyAfterMaxBackoff() {
        stubSubscribe(List.of(Mono.just(Registration.CONFLICT), Mono.error(PUBLISHER_DOWN)));
        start();

        clock.advance(CONFLICT_RENEWAL_INTERVAL);
        scheduler.runNext();
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

        assertEquals(DataSpaceEventSubscriber.MAX_SCHEDULE_DELAY, onlyPendingTask().delay());
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
        assertEquals(CONFLICT_RENEWAL_INTERVAL, onlyPendingTask().delay());
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

        assertEquals(INITIAL_DELAY, onlyPendingTask().delay());
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
        assertTrue(scheduler.pending().isEmpty());
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
        assertTrue(scheduler.pending().isEmpty());
    }

    @Test
    void cancelledRetryDoesNotRegister() {
        stubSubscribe(List.of(Mono.error(PUBLISHER_DOWN)));
        start();
        RecordingTaskScheduler.Task retry = onlyPendingTask();

        store.get(PUBLISHER_ID).orElseThrow().cancel();

        assertTrue(retry.isCancelled());
        assertFalse(scheduler.pending().contains(retry));
        verify(client).subscribe(any());
    }

    private void start() {
        subscriber.onApplicationEvent(startupEvent());
        assertEquals(INITIAL_DELAY, onlyPendingTask().delay());
        scheduler.runNext();
    }

    @SuppressWarnings("unchecked")
    private void stubSubscribe(List<Mono<Registration>> answers) {
        when(client.subscribe(any())).thenReturn(answers.get(0), answers.subList(1, answers.size()).toArray(Mono[]::new));
    }

    private RecordingTaskScheduler.Task onlyPendingTask() {
        List<RecordingTaskScheduler.Task> pending = scheduler.pending();
        assertEquals(1, pending.size(), "pending tasks");
        return pending.get(0);
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
