package org.fiware.iam.configuration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSpaceEventsPropertiesTest {

    private static final String PREFIX = "dataspace.events.";
    private static final String PROVIDER_ID = "did:web:provider.org";
    private static final String PROVIDER_ORIGIN = "provider.org";
    private static final String CONSUMER_ID = "did:web:consumer.org";
    private static final String CONSUMER_ORIGIN = "events.consumer.org";
    private static final String TYPE_A = "org.fiware.dataspace.a";
    private static final String TYPE_B = "org.fiware.dataspace.b";
    private static final String GENERAL_DID = "did:web:general.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String IDENTITY_HEADER = "X-Participant";
    private static final String PARTICIPANT_CLAIM = "sub";
    private static final int MAX_ENTRIES = 42;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String OWN_ORIGIN = "cm.own.org";
    private static final Duration LEASE_TTL = Duration.ofHours(2);
    private static final int MAX_PER_SUBSCRIBER = 3;
    private static final int MAX_SUBSCRIPTIONS = 30;
    private static final int MAX_ATTEMPTS = 7;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final int QUEUE_SIZE = 50;
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(12);
    private static final String SINK = "https://cm.own.org/events";
    private static final String SUBSCRIBER_CLIENT_ID = "subscriber-client";
    private static final List<String> SUBSCRIBER_SCOPES = List.of("subscriptions");
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final List<String> PROTOCOL_SCOPES = List.of("events", "default");
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final double RENEWAL_FRACTION = 0.75;
    private static final Duration MIN_RENEWAL_DELAY = Duration.ofSeconds(3);
    private static final Duration CONFLICT_RENEWAL_INTERVAL = Duration.ofMinutes(30);
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(30);
    private static final Duration RETRY_INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofMinutes(2);
    private static final String SUBSCRIPTIONS_URL = "https://provider.org/subscriptions";
    private static final String PUBLISHER_CLIENT_ID = "provider-client";
    private static final List<String> PUBLISHER_SCOPES = List.of("provider-subscriptions");
    private static final Duration TIR_INITIAL_DELAY = Duration.ofSeconds(10);
    private static final Duration TIR_INTERVAL = Duration.ofMinutes(5);
    private static final int TIR_PAGE_SIZE = 25;
    private static final int TIR_MAX_PAGES = 50;
    private static final int TIR_CONFIRMATIONS = 3;
    private static final int TIR_MAX_REMOVALS = 7;
    private static final String OWN_PARTICIPANT_ID = "did:web:trust-anchor.org";
    private static final String ANCHOR_1 = "did:web:anchor-1.org";
    private static final String ANCHOR_2 = "did:web:anchor-2.org";
    private static final String REMOVED_DID = "did:web:removed.org";
    private static final String OTHER_DID = "did:web:other.org";
    private static final String REGISTRY_URL = "https://tir.anchor-1.org";
    private static final String DATASPACE_ID = "my-dataspace";
    private static final String REMOVED = DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
    private static final int MAX_REMOVALS_PER_RUN = 3;
    private static final int MAX_ADOPTIONS_PER_RUN = 20;
    private static final int REMOVED_PAGE_SIZE = 30;
    private static final int REMOVED_MAX_PAGES = 40;
    private static final Duration SWEEP_INITIAL_DELAY = Duration.ofSeconds(20);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(10);
    // keeps the test context away from the Rainbow and TM Forum startup calls
    private static final Map<String, Object> ISOLATED = Map.of("notification.enabled", false, "general.enable-rainbow", false);

    private ApplicationContext context;
    private Map<String, Object> config;

    @BeforeEach
    void startContext() {
        config = new HashMap<>(Map.of(
                PREFIX + "publishers", List.of(
                        Map.of("id", PROVIDER_ID, "origin", PROVIDER_ORIGIN, "types", List.of(TYPE_A, TYPE_B)),
                        Map.of("id", CONSUMER_ID, "origin", CONSUMER_ORIGIN, "types", List.of(TYPE_A))),
                PREFIX + "listener.identity.header", IDENTITY_HEADER,
                PREFIX + "listener.dedup.max-entries", MAX_ENTRIES,
                PREFIX + "listener.dedup.ttl", TTL.toString()));
        context = ApplicationContext.builder()
                .properties(ISOLATED)
                .properties(Map.of("general.did", GENERAL_DID))
                .propertySources(PropertySource.of("test", config))
                .start();
    }

    @AfterEach
    void stopContext() {
        context.close();
    }

    private DataSpaceEventsProperties properties() {
        return context.getBean(DataSpaceEventsProperties.class);
    }

    private void refresh() {
        context.publishEvent(new RefreshEvent(context.getEnvironment().refreshAndDiff()));
    }

    @Test
    void bindsConfiguredValuesAndDefaults() {
        DataSpaceEventsProperties.Listener listener = properties().getListener();

        assertFalse(listener.isEnabled());
        assertEquals(IDENTITY_HEADER, listener.getIdentity().getHeader());
        assertEquals(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM, listener.getIdentity().getParticipantClaim());
        assertEquals(MAX_ENTRIES, listener.getDedup().getMaxEntries());
        assertEquals(TTL, listener.getDedup().getTtl());
        assertFalse(listener.getWatch().isEnabled());
        assertEquals(2, properties().getPublishers().size());
    }

    @Test
    void bindsDefaultsWithoutConfiguration() {
        try (ApplicationContext empty = ApplicationContext.run(ISOLATED)) {
            DataSpaceEventsProperties properties = empty.getBean(DataSpaceEventsProperties.class);

            assertTrue(properties.getPublishers().isEmpty());
            assertEquals(DataSpaceEventsProperties.DEFAULT_DEDUP_MAX_ENTRIES, properties.getListener().getDedup().getMaxEntries());
            assertEquals(DataSpaceEventsProperties.DEFAULT_DEDUP_TTL, properties.getListener().getDedup().getTtl());
        }
    }

    @ParameterizedTest(name = "publisher settings {0}")
    @MethodSource("publisherSettings")
    void bindsPublisherSettings(String name, Map<String, Object> publisherConfig, boolean enabled, String origin,
                                Duration leaseTtl, int maxPerSubscriber, int maxSubscriptions, boolean allowInsecureSinks,
                                int maxAttempts, Duration initialBackoff, Duration maxBackoff, int queueSize,
                                Duration attemptTimeout) {
        try (ApplicationContext ctx = ApplicationContext.builder()
                .properties(ISOLATED)
                .propertySources(PropertySource.of("test", publisherConfig))
                .start()) {
            DataSpaceEventsProperties.PublisherSettings publisher = ctx.getBean(DataSpaceEventsProperties.class).getPublisher();
            DataSpaceEventsProperties.PublisherSettings.Delivery delivery = publisher.getDelivery();

            assertEquals(enabled, publisher.isEnabled());
            assertEquals(origin, publisher.getOrigin());
            assertEquals(leaseTtl, publisher.getLeaseTtl());
            assertEquals(maxPerSubscriber, publisher.getMaxSubscriptionsPerSubscriber());
            assertEquals(maxSubscriptions, publisher.getMaxSubscriptions());
            assertEquals(allowInsecureSinks, publisher.isAllowInsecureSinks());
            assertEquals(maxAttempts, delivery.getMaxAttempts());
            assertEquals(initialBackoff, delivery.getInitialBackoff());
            assertEquals(maxBackoff, delivery.getMaxBackoff());
            assertEquals(queueSize, delivery.getQueueSize());
            assertEquals(attemptTimeout, delivery.getAttemptTimeout());
        }
    }

    static Stream<Arguments> publisherSettings() {
        String publisher = PREFIX + "publisher.";
        return Stream.of(
                Arguments.of("defaults", Map.of(), false, null,
                        DataSpaceEventsProperties.DEFAULT_LEASE_TTL,
                        DataSpaceEventsProperties.DEFAULT_MAX_SUBSCRIPTIONS_PER_SUBSCRIBER,
                        DataSpaceEventsProperties.DEFAULT_MAX_SUBSCRIPTIONS, false,
                        DataSpaceEventsProperties.DEFAULT_DELIVERY_MAX_ATTEMPTS,
                        DataSpaceEventsProperties.DEFAULT_DELIVERY_INITIAL_BACKOFF,
                        DataSpaceEventsProperties.DEFAULT_DELIVERY_MAX_BACKOFF,
                        DataSpaceEventsProperties.DEFAULT_DELIVERY_QUEUE_SIZE,
                        DataSpaceEventsProperties.DEFAULT_DELIVERY_ATTEMPT_TIMEOUT),
                Arguments.of("configured", Map.ofEntries(
                                Map.entry(publisher + "enabled", true),
                                Map.entry(publisher + "origin", OWN_ORIGIN),
                                Map.entry(publisher + "lease-ttl", LEASE_TTL.toString()),
                                Map.entry(publisher + "max-subscriptions-per-subscriber", MAX_PER_SUBSCRIBER),
                                Map.entry(publisher + "max-subscriptions", MAX_SUBSCRIPTIONS),
                                Map.entry(publisher + "allow-insecure-sinks", true),
                                Map.entry(publisher + "delivery.max-attempts", MAX_ATTEMPTS),
                                Map.entry(publisher + "delivery.initial-backoff", INITIAL_BACKOFF.toString()),
                                Map.entry(publisher + "delivery.max-backoff", MAX_BACKOFF.toString()),
                                Map.entry(publisher + "delivery.queue-size", QUEUE_SIZE),
                                Map.entry(publisher + "delivery.attempt-timeout", ATTEMPT_TIMEOUT.toString())),
                        true, OWN_ORIGIN, LEASE_TTL, MAX_PER_SUBSCRIBER, MAX_SUBSCRIPTIONS, true,
                        MAX_ATTEMPTS, INITIAL_BACKOFF, MAX_BACKOFF, QUEUE_SIZE, ATTEMPT_TIMEOUT));
    }

    @ParameterizedTest(name = "subscriber settings {0}")
    @MethodSource("subscriberSettings")
    void bindsSubscriberSettings(String name, Map<String, Object> subscriberConfig, boolean enabled, String sink, String clientId,
                                 List<String> scopes, String protocolClientId, List<String> protocolScopes, Duration initialDelay,
                                 double renewalFraction, Duration minRenewalDelay, Duration conflictRenewalInterval,
                                 Duration reconcileInterval, Duration retryInitialBackoff, Duration retryMaxBackoff) {
        try (ApplicationContext ctx = ApplicationContext.builder()
                .properties(ISOLATED)
                .propertySources(PropertySource.of("test", subscriberConfig))
                .start()) {
            DataSpaceEventsProperties.SubscriberSettings subscriber = ctx.getBean(DataSpaceEventsProperties.class).getSubscriber();

            assertEquals(enabled, subscriber.isEnabled());
            assertEquals(sink, subscriber.getSink());
            assertEquals(clientId, subscriber.getClientId());
            assertEquals(scopes, subscriber.getScopes());
            assertEquals(protocolClientId, subscriber.getProtocolSettings().getClientId());
            assertEquals(protocolScopes, subscriber.getProtocolSettings().getScopes());
            assertEquals(initialDelay, subscriber.getInitialDelay());
            assertEquals(renewalFraction, subscriber.getRenewalFraction());
            assertEquals(minRenewalDelay, subscriber.getMinRenewalDelay());
            assertEquals(conflictRenewalInterval, subscriber.getConflictRenewalInterval());
            assertEquals(reconcileInterval, subscriber.getReconcileInterval());
            assertEquals(retryInitialBackoff, subscriber.getRetry().getInitialBackoff());
            assertEquals(retryMaxBackoff, subscriber.getRetry().getMaxBackoff());
        }
    }

    static Stream<Arguments> subscriberSettings() {
        String subscriber = PREFIX + "subscriber.";
        return Stream.of(
                Arguments.of("defaults", Map.of(), false, null, null, List.of(), null, List.of(),
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_INITIAL_DELAY,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RENEWAL_FRACTION,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_MIN_RENEWAL_DELAY,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RECONCILE_INTERVAL,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_INITIAL_BACKOFF,
                        DataSpaceEventsProperties.DEFAULT_SUBSCRIBER_RETRY_MAX_BACKOFF),
                Arguments.of("configured", Map.ofEntries(
                                Map.entry(PREFIX + "listener.enabled", true),
                                Map.entry(subscriber + "enabled", true),
                                Map.entry(subscriber + "sink", SINK),
                                Map.entry(subscriber + "client-id", SUBSCRIBER_CLIENT_ID),
                                Map.entry(subscriber + "scopes", SUBSCRIBER_SCOPES),
                                Map.entry(subscriber + "protocol-settings.client-id", PROTOCOL_CLIENT_ID),
                                Map.entry(subscriber + "protocol-settings.scopes", PROTOCOL_SCOPES),
                                Map.entry(subscriber + "initial-delay", INITIAL_DELAY.toString()),
                                Map.entry(subscriber + "renewal-fraction", RENEWAL_FRACTION),
                                Map.entry(subscriber + "min-renewal-delay", MIN_RENEWAL_DELAY.toString()),
                                Map.entry(subscriber + "conflict-renewal-interval", CONFLICT_RENEWAL_INTERVAL.toString()),
                                Map.entry(subscriber + "reconcile-interval", RECONCILE_INTERVAL.toString()),
                                Map.entry(subscriber + "retry.initial-backoff", RETRY_INITIAL_BACKOFF.toString()),
                                Map.entry(subscriber + "retry.max-backoff", RETRY_MAX_BACKOFF.toString())),
                        true, SINK, SUBSCRIBER_CLIENT_ID, SUBSCRIBER_SCOPES, PROTOCOL_CLIENT_ID, PROTOCOL_SCOPES, INITIAL_DELAY,
                        RENEWAL_FRACTION, MIN_RENEWAL_DELAY, CONFLICT_RENEWAL_INTERVAL, RECONCILE_INTERVAL,
                        RETRY_INITIAL_BACKOFF, RETRY_MAX_BACKOFF));
    }

    @ParameterizedTest(name = "tir-watcher settings {0}")
    @MethodSource("tirWatcherSettings")
    void bindsTirWatcherSettings(String name, Map<String, Object> tirWatcherConfig, boolean enabled, Duration initialDelay,
                                 Duration interval, int pageSize, int maxPages, int confirmations, int maxRemovalsPerCycle) {
        try (ApplicationContext ctx = ApplicationContext.builder()
                .properties(ISOLATED)
                .propertySources(PropertySource.of("test", tirWatcherConfig))
                .start()) {
            DataSpaceEventsProperties.TirWatcherSettings tirWatcher = ctx.getBean(DataSpaceEventsProperties.class).getTirWatcher();

            assertEquals(enabled, tirWatcher.isEnabled());
            assertEquals(initialDelay, tirWatcher.getInitialDelay());
            assertEquals(interval, tirWatcher.getInterval());
            assertEquals(pageSize, tirWatcher.getPageSize());
            assertEquals(maxPages, tirWatcher.getMaxPages());
            assertEquals(confirmations, tirWatcher.getConfirmations());
            assertEquals(maxRemovalsPerCycle, tirWatcher.getMaxRemovalsPerCycle());
        }
    }

    static Stream<Arguments> tirWatcherSettings() {
        String tirWatcher = PREFIX + "tir-watcher.";
        return Stream.of(
                Arguments.of("defaults", Map.of(), false,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_INITIAL_DELAY,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_INTERVAL,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_PAGE_SIZE,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_MAX_PAGES,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_CONFIRMATIONS,
                        DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_MAX_REMOVALS_PER_CYCLE),
                // the watcher's startup guard needs the publisher and an own participant id
                Arguments.of("configured", Map.ofEntries(
                                Map.entry(PREFIX + "publisher.enabled", true),
                                Map.entry(PREFIX + "own-participant-id", OWN_PARTICIPANT_ID),
                                Map.entry(tirWatcher + "enabled", true),
                                Map.entry(tirWatcher + "initial-delay", TIR_INITIAL_DELAY.toString()),
                                Map.entry(tirWatcher + "interval", TIR_INTERVAL.toString()),
                                Map.entry(tirWatcher + "page-size", TIR_PAGE_SIZE),
                                Map.entry(tirWatcher + "max-pages", TIR_MAX_PAGES),
                                Map.entry(tirWatcher + "confirmations", TIR_CONFIRMATIONS),
                                Map.entry(tirWatcher + "max-removals-per-cycle", TIR_MAX_REMOVALS)),
                        true, TIR_INITIAL_DELAY, TIR_INTERVAL, TIR_PAGE_SIZE, TIR_MAX_PAGES, TIR_CONFIRMATIONS,
                        TIR_MAX_REMOVALS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tirWatcherClamps")
    void clampsTirWatcherSettings(String name, Consumer<DataSpaceEventsProperties.TirWatcherSettings> configure,
                                  Function<DataSpaceEventsProperties.TirWatcherSettings, Object> effective, Object expected) {
        DataSpaceEventsProperties.TirWatcherSettings settings = new DataSpaceEventsProperties.TirWatcherSettings();
        configure.accept(settings);

        assertEquals(expected, effective.apply(settings));
    }

    static Stream<Arguments> tirWatcherClamps() {
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> pageSize =
                DataSpaceEventsProperties.TirWatcherSettings::effectivePageSize;
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> maxPages =
                DataSpaceEventsProperties.TirWatcherSettings::effectiveMaxPages;
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> confirmations =
                DataSpaceEventsProperties.TirWatcherSettings::effectiveConfirmations;
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> maxRemovals =
                DataSpaceEventsProperties.TirWatcherSettings::effectiveMaxRemovalsPerCycle;
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> interval =
                DataSpaceEventsProperties.TirWatcherSettings::effectiveInterval;
        Function<DataSpaceEventsProperties.TirWatcherSettings, Object> initialDelay =
                DataSpaceEventsProperties.TirWatcherSettings::effectiveInitialDelay;
        Duration defaultInterval = DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_INTERVAL;
        Duration defaultInitialDelay = DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_INITIAL_DELAY;
        return Stream.of(
                clamp("page size 0 -> 1", s -> s.setPageSize(0), pageSize,
                        DataSpaceEventsProperties.TirWatcherSettings.MIN_PAGE_SIZE),
                clamp("page size 500 -> 100", s -> s.setPageSize(500), pageSize,
                        DataSpaceEventsProperties.TirWatcherSettings.MAX_PAGE_SIZE),
                clamp("page size in range", s -> s.setPageSize(TIR_PAGE_SIZE), pageSize, TIR_PAGE_SIZE),
                clamp("max pages 0 -> 1", s -> s.setMaxPages(0), maxPages,
                        DataSpaceEventsProperties.TirWatcherSettings.MIN_MAX_PAGES),
                clamp("max pages in range", s -> s.setMaxPages(TIR_MAX_PAGES), maxPages, TIR_MAX_PAGES),
                clamp("confirmations 0 -> 1", s -> s.setConfirmations(0), confirmations,
                        DataSpaceEventsProperties.TirWatcherSettings.MIN_CONFIRMATIONS),
                clamp("confirmations in range", s -> s.setConfirmations(TIR_CONFIRMATIONS), confirmations, TIR_CONFIRMATIONS),
                clamp("cap -1 -> 0", s -> s.setMaxRemovalsPerCycle(-1), maxRemovals,
                        DataSpaceEventsProperties.TirWatcherSettings.MIN_MAX_REMOVALS_PER_CYCLE),
                clamp("cap 0 stays 0", s -> s.setMaxRemovalsPerCycle(0), maxRemovals, 0),
                clamp("cap in range", s -> s.setMaxRemovalsPerCycle(TIR_MAX_REMOVALS), maxRemovals, TIR_MAX_REMOVALS),
                clamp("interval null -> default", s -> s.setInterval(null), interval, defaultInterval),
                clamp("interval PT0S -> default", s -> s.setInterval(Duration.ZERO), interval, defaultInterval),
                clamp("interval -PT1S -> default", s -> s.setInterval(Duration.ofSeconds(-1)), interval, defaultInterval),
                clamp("interval in range", s -> s.setInterval(TIR_INTERVAL), interval, TIR_INTERVAL),
                clamp("initial delay null -> default", s -> s.setInitialDelay(null), initialDelay, defaultInitialDelay),
                clamp("initial delay PT0S -> default", s -> s.setInitialDelay(Duration.ZERO), initialDelay, defaultInitialDelay),
                clamp("initial delay -PT1S -> default", s -> s.setInitialDelay(Duration.ofSeconds(-1)), initialDelay,
                        defaultInitialDelay),
                clamp("initial delay in range", s -> s.setInitialDelay(TIR_INITIAL_DELAY), initialDelay, TIR_INITIAL_DELAY));
    }

    private static Arguments clamp(String name, Consumer<DataSpaceEventsProperties.TirWatcherSettings> configure,
                                   Function<DataSpaceEventsProperties.TirWatcherSettings, Object> effective, Object expected) {
        return Arguments.of(name, configure, effective, expected);
    }

    @ParameterizedTest(name = "publisher {0}, tir-watcher {1} -> tir-watcher beans {2}")
    @MethodSource("tirWatcherConditions")
    void tirWatcherCondition(boolean publisherEnabled, boolean tirWatcherEnabled, boolean expected) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getPublisher().setEnabled(publisherEnabled);
        properties.getTirWatcher().setEnabled(tirWatcherEnabled);
        ConditionContext conditionContext = mock(ConditionContext.class);
        when(conditionContext.getBean(DataSpaceEventsProperties.class)).thenReturn(properties);

        assertEquals(expected, new DataSpaceEventsProperties.TirWatcherCondition().matches(conditionContext));
    }

    static Stream<Arguments> tirWatcherConditions() {
        return Stream.of(
                Arguments.of(false, false, false),
                Arguments.of(true, false, false),
                Arguments.of(false, true, false),
                Arguments.of(true, true, true));
    }

    @ParameterizedTest(name = "publisher entry {0}")
    @MethodSource("publisherEntries")
    void bindsPublisherSubscriptionFields(String name, Map<String, Object> entry, String subscriptionsUrl, String clientId,
                                          List<String> scopes) {
        config.put(PREFIX + "publishers", List.of(entry));
        refresh();

        DataSpaceEventsProperties.Publisher publisher = properties().getPublishers().get(0);
        assertEquals(subscriptionsUrl, publisher.getSubscriptionsUrl());
        assertEquals(clientId, publisher.getClientId());
        assertEquals(scopes, publisher.getScopes());
    }

    static Stream<Arguments> publisherEntries() {
        Map<String, Object> listenerOnly = Map.of("id", PROVIDER_ID, "origin", PROVIDER_ORIGIN, "types", List.of(TYPE_A));
        Map<String, Object> subscribable = new HashMap<>(listenerOnly);
        subscribable.putAll(Map.of("subscriptions-url", SUBSCRIPTIONS_URL, "client-id", PUBLISHER_CLIENT_ID, "scopes", PUBLISHER_SCOPES));
        return Stream.of(
                Arguments.of("listener-only", listenerOnly, null, null, null),
                Arguments.of("subscribable with overrides", subscribable, SUBSCRIPTIONS_URL, PUBLISHER_CLIENT_ID, PUBLISHER_SCOPES));
    }

    @ParameterizedTest(name = "subscriber {0} -> subscriber beans {0}")
    @MethodSource("subscriberConditions")
    void subscriberCondition(boolean subscriberEnabled) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getSubscriber().setEnabled(subscriberEnabled);
        ConditionContext conditionContext = mock(ConditionContext.class);
        when(conditionContext.getBean(DataSpaceEventsProperties.class)).thenReturn(properties);

        assertEquals(subscriberEnabled, new DataSpaceEventsProperties.SubscriberCondition().matches(conditionContext));
    }

    static Stream<Boolean> subscriberConditions() {
        return Stream.of(false, true);
    }

    @ParameterizedTest(name = "listener {0}, publisher {1} -> identity {2}, publisher beans {3}")
    @MethodSource("conditions")
    void conditions(boolean listenerEnabled, boolean publisherEnabled, boolean identityExpected, boolean publisherExpected) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getListener().setEnabled(listenerEnabled);
        properties.getPublisher().setEnabled(publisherEnabled);
        ConditionContext conditionContext = mock(ConditionContext.class);
        when(conditionContext.getBean(DataSpaceEventsProperties.class)).thenReturn(properties);

        assertEquals(identityExpected, new DataSpaceEventsProperties.IdentityCondition().matches(conditionContext));
        assertEquals(publisherExpected, new DataSpaceEventsProperties.PublisherCondition().matches(conditionContext));
        assertEquals(listenerEnabled, new DataSpaceEventsProperties.ListenerCondition().matches(conditionContext));
    }

    static Stream<Arguments> conditions() {
        return Stream.of(
                Arguments.of(false, false, false, false),
                Arguments.of(true, false, true, false),
                Arguments.of(false, true, true, true),
                Arguments.of(true, true, true, true));
    }

    @ParameterizedTest(name = "{0} may publish {1}: {2}")
    @MethodSource("authorizations")
    void isAuthorized(String publisherId, String type, boolean expected) {
        assertEquals(expected, properties().isAuthorized(publisherId, type));
    }

    static Stream<Arguments> authorizations() {
        return Stream.of(
                Arguments.of(PROVIDER_ID, TYPE_A, true),
                Arguments.of(PROVIDER_ID, TYPE_B, true),
                Arguments.of(CONSUMER_ID, TYPE_A, true),
                Arguments.of(CONSUMER_ID, TYPE_B, false),
                Arguments.of("did:web:unknown.org", TYPE_A, false),
                Arguments.of(PROVIDER_ID.toUpperCase(), TYPE_A, false),
                Arguments.of(PROVIDER_ID, TYPE_A.toUpperCase(), false),
                Arguments.of(null, TYPE_A, false),
                Arguments.of("", TYPE_A, false),
                Arguments.of(" ", TYPE_A, false));
    }

    @ParameterizedTest(name = "publisher id {0} grants nothing")
    @MethodSource("blankIds")
    void publisherWithoutIdAuthorizesNobody(String configuredId) {
        Map<String, Object> publisher = new HashMap<>(Map.of("origin", CONSUMER_ORIGIN, "types", List.of(TYPE_A)));
        if (configuredId != null) {
            publisher.put("id", configuredId);
        }
        config.put(PREFIX + "publishers", List.of(publisher));
        refresh();

        assertFalse(properties().isAuthorized(null, TYPE_A));
        assertFalse(properties().isAuthorized("", TYPE_A));
        assertFalse(properties().isAuthorized(" ", TYPE_A));
    }

    static Stream<String> blankIds() {
        return Stream.of(null, "", " ");
    }

    @ParameterizedTest(name = "origin {0} -> {1}")
    @MethodSource("origins")
    void allowedOrigin(String requestOrigin, Optional<String> expected) {
        assertEquals(expected, properties().allowedOrigin(requestOrigin));
    }

    static Stream<Arguments> origins() {
        return Stream.of(
                Arguments.of(PROVIDER_ORIGIN, Optional.of(PROVIDER_ORIGIN)),
                Arguments.of(PROVIDER_ORIGIN.toUpperCase(), Optional.of(PROVIDER_ORIGIN)),
                Arguments.of(CONSUMER_ORIGIN, Optional.of(CONSUMER_ORIGIN)),
                Arguments.of("unknown.org", Optional.empty()),
                Arguments.of(PROVIDER_ID, Optional.empty()),
                Arguments.of(null, Optional.empty()));
    }

    @ParameterizedTest(name = "own participant id {0} -> {1}")
    @MethodSource("ownIds")
    void ownId(String ownParticipantId, String expected) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.setOwnParticipantId(ownParticipantId);
        GeneralProperties generalProperties = new GeneralProperties();
        generalProperties.setDid(GENERAL_DID);

        assertEquals(expected, properties.ownId(generalProperties));
    }

    static Stream<Arguments> ownIds() {
        return Stream.of(
                Arguments.of(OWN_ID, OWN_ID),
                Arguments.of(null, GENERAL_DID),
                Arguments.of("", GENERAL_DID),
                Arguments.of(" ", GENERAL_DID));
    }

    @Test
    void refreshRebindsWithoutRestart() {
        DataSpaceEventsProperties properties = properties();
        assertFalse(properties.isAuthorized(CONSUMER_ID, TYPE_B));

        config.put(PREFIX + "publishers", List.of(Map.of("id", CONSUMER_ID, "origin", CONSUMER_ORIGIN, "types", List.of(TYPE_B))));
        config.put(PREFIX + "listener.dedup.max-entries", MAX_ENTRIES + 1);
        config.put(PREFIX + "listener.identity.participant-claim", PARTICIPANT_CLAIM);
        config.put(PREFIX + "listener.identity.header", "");
        refresh();

        assertTrue(properties.isAuthorized(CONSUMER_ID, TYPE_B));
        assertFalse(properties.isAuthorized(PROVIDER_ID, TYPE_A));
        assertEquals(Optional.empty(), properties.allowedOrigin(PROVIDER_ORIGIN));
        assertEquals(MAX_ENTRIES + 1, properties.getListener().getDedup().getMaxEntries());
        assertEquals(PARTICIPANT_CLAIM, properties.getListener().getIdentity().getParticipantClaim());
        assertTrue(properties.getListener().getIdentity().getHeader().isBlank());
    }

    private static DataSpaceEventsProperties.Publisher removalPublisher(String id, @Nullable String registryUrl,
                                                                        Consumer<DataSpaceEventsProperties.OnParticipantRemoved> policy) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(REMOVED));
        publisher.getOnParticipantRemoved().setRegistryUrl(registryUrl);
        policy.accept(publisher.getOnParticipantRemoved());
        return publisher;
    }

    private static DataSpaceEventsProperties.Publisher peer(String id) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(TYPE_A));
        return publisher;
    }

    private static DataSpaceEventsProperties propertiesWith(@Nullable String dataspaceId,
                                                            DataSpaceEventsProperties.Publisher... publishers) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.setDataspaceId(dataspaceId);
        properties.setPublishers(List.of(publishers));
        return properties;
    }

    private static DataSpaceEventsProperties.ModeOverride override(String id, RemovalMode mode) {
        DataSpaceEventsProperties.ModeOverride override = new DataSpaceEventsProperties.ModeOverride();
        override.setId(id);
        override.setMode(mode);
        return override;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removalModes")
    void removalMode(String name, DataSpaceEventsProperties properties, String publisherId, String did,
                     Optional<RemovalMode> expected) {
        assertEquals(expected, properties.removalMode(publisherId, did, OWN_ID));
    }

    static Stream<Arguments> removalModes() {
        return Stream.of(
                Arguments.of("default is report",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                        })), ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.REPORT)),
                Arguments.of("publisher mode",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> p.setMode(RemovalMode.GRANTED))),
                        ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.GRANTED)),
                Arguments.of("override beats the mode",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                            p.setMode(RemovalMode.GRANTED);
                            p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.FULL)));
                        })), ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.FULL)),
                Arguments.of("override of another DID does not apply",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                            p.setMode(RemovalMode.GRANTED);
                            p.setOverrides(List.of(override(OTHER_DID, RemovalMode.FULL)));
                        })), ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.GRANTED)),
                Arguments.of("keep beats an override",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                            p.setKeep(List.of(REMOVED_DID));
                            p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.FULL)));
                        })), ANCHOR_1, REMOVED_DID, Optional.empty()),
                Arguments.of("P1's keep excludes the DID for P2 in full",
                        propertiesWith(null,
                                removalPublisher(ANCHOR_1, REGISTRY_URL, p -> p.setKeep(List.of(REMOVED_DID))),
                                removalPublisher(ANCHOR_2, REGISTRY_URL, p -> p.setMode(RemovalMode.FULL))),
                        ANCHOR_2, REMOVED_DID, Optional.empty()),
                Arguments.of("P1's keep excludes the DID for P2's override",
                        propertiesWith(null,
                                removalPublisher(ANCHOR_1, REGISTRY_URL, p -> p.setKeep(List.of(REMOVED_DID))),
                                removalPublisher(ANCHOR_2, REGISTRY_URL,
                                        p -> p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.FULL))))),
                        ANCHOR_2, REMOVED_DID, Optional.empty()),
                Arguments.of("own id excluded even with a full override",
                        propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                            p.setMode(RemovalMode.FULL);
                            p.setOverrides(List.of(override(OWN_ID, RemovalMode.FULL)));
                        })), ANCHOR_1, OWN_ID, Optional.empty()),
                Arguments.of("writing mode without registry-url clamped to report",
                        propertiesWith(null, removalPublisher(ANCHOR_1, null, p -> p.setMode(RemovalMode.FULL))),
                        ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.REPORT)),
                Arguments.of("writing override without registry-url clamped to report",
                        propertiesWith(null, removalPublisher(ANCHOR_1, " ",
                                p -> p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.DATASPACE))))),
                        ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.REPORT)),
                Arguments.of("publisher not authorized for participant.removed gets report",
                        propertiesWith(null, peer(ANCHOR_1)), ANCHOR_1, REMOVED_DID, Optional.of(RemovalMode.REPORT)));
    }

    @ParameterizedTest(name = "own id {0} excludes nothing")
    @MethodSource("blankIds")
    void blankOwnIdExcludesNothing(String ownId) {
        DataSpaceEventsProperties properties = propertiesWith(null,
                removalPublisher(ANCHOR_1, REGISTRY_URL, p -> p.setMode(RemovalMode.DATASPACE)));

        assertEquals(Optional.of(RemovalMode.DATASPACE), properties.removalMode(ANCHOR_1, REMOVED_DID, ownId));
    }

    @ParameterizedTest(name = "kept by {0}")
    @MethodSource("keptBy")
    void keptBy(String name, DataSpaceEventsProperties properties, Optional<String> expected) {
        assertEquals(expected, properties.keptBy(REMOVED_DID));
    }

    static Stream<Arguments> keptBy() {
        return Stream.of(
                Arguments.of("nobody", propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                })), Optional.empty()),
                Arguments.of("second publisher", propertiesWith(null,
                        removalPublisher(ANCHOR_1, REGISTRY_URL, p -> p.setKeep(List.of(OTHER_DID))),
                        removalPublisher(ANCHOR_2, REGISTRY_URL, p -> p.setKeep(List.of(REMOVED_DID)))), Optional.of(ANCHOR_2)));
    }

    @ParameterizedTest(name = "adoption {0}")
    @MethodSource("adoptions")
    void adoption(String name, DataSpaceEventsProperties properties, AdoptionPolicy expected) {
        assertEquals(expected, properties.adoption());
    }

    static Stream<Arguments> adoptions() {
        Consumer<DataSpaceEventsProperties.OnParticipantRemoved> adopt = p -> p.setAdoptUnattributed(true);
        Consumer<DataSpaceEventsProperties.OnParticipantRemoved> none = p -> {
        };
        return Stream.of(
                Arguments.of("off without the flag", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL, none)),
                        AdoptionPolicy.OFF),
                Arguments.of("off without removal publishers", propertiesWith(DATASPACE_ID, peer(ANCHOR_1)), AdoptionPolicy.OFF),
                Arguments.of("allowed", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL, adopt)),
                        AdoptionPolicy.ALLOWED),
                Arguments.of("allowed next to a peer publishing other types",
                        propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL, adopt), peer(ANCHOR_2)),
                        AdoptionPolicy.ALLOWED),
                Arguments.of("refused for two removal publishers", propertiesWith(DATASPACE_ID,
                                removalPublisher(ANCHOR_1, REGISTRY_URL, adopt), removalPublisher(ANCHOR_2, REGISTRY_URL, none)),
                        AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.MULTIPLE_PUBLISHERS)),
                Arguments.of("refused without dataspace-id", propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL, adopt)),
                        AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.NO_DATASPACE_ID)),
                Arguments.of("refused without registry-url", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, null, adopt)),
                        AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.NO_REGISTRY_URL)),
                Arguments.of("two publishers reported before a missing registry-url", propertiesWith(DATASPACE_ID,
                                removalPublisher(ANCHOR_1, null, adopt), removalPublisher(ANCHOR_2, null, none)),
                        AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.MULTIPLE_PUBLISHERS)),
                Arguments.of("missing dataspace-id reported before a missing registry-url",
                        propertiesWith("", removalPublisher(ANCHOR_1, null, adopt)),
                        AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.NO_DATASPACE_ID)));
    }

    @ParameterizedTest(name = "writes configured: {0}")
    @MethodSource("writesConfigured")
    void removalWritesConfigured(String name, DataSpaceEventsProperties properties, boolean expected) {
        assertEquals(expected, properties.removalWritesConfigured());
    }

    static Stream<Arguments> writesConfigured() {
        return Stream.of(
                Arguments.of("report only", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL, p -> {
                })), false),
                Arguments.of("report override", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL,
                        p -> p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.REPORT))))), false),
                Arguments.of("writing mode", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL,
                        p -> p.setMode(RemovalMode.DATASPACE))), true),
                Arguments.of("writing mode without registry-url", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, null,
                        p -> p.setMode(RemovalMode.DATASPACE))), true),
                Arguments.of("writing override", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL,
                        p -> p.setOverrides(List.of(override(REMOVED_DID, RemovalMode.GRANTED))))), true),
                Arguments.of("allowed adoption", propertiesWith(DATASPACE_ID, removalPublisher(ANCHOR_1, REGISTRY_URL,
                        p -> p.setAdoptUnattributed(true))), true),
                Arguments.of("refused adoption with report only", propertiesWith(null, removalPublisher(ANCHOR_1, REGISTRY_URL,
                        p -> p.setAdoptUnattributed(true))), false),
                Arguments.of("writing mode of a peer is ignored", propertiesWith(DATASPACE_ID, peerWithWritingMode()), false));
    }

    private static DataSpaceEventsProperties.Publisher peerWithWritingMode() {
        DataSpaceEventsProperties.Publisher peer = peer(ANCHOR_2);
        peer.getOnParticipantRemoved().setMode(RemovalMode.FULL);
        peer.getOnParticipantRemoved().setRegistryUrl(REGISTRY_URL);
        return peer;
    }

    @Test
    void removalPublishers() {
        DataSpaceEventsProperties.Publisher removal = removalPublisher(ANCHOR_1, null, p -> {
        });
        DataSpaceEventsProperties.Publisher withoutId = removalPublisher(" ", null, p -> {
        });
        DataSpaceEventsProperties.Publisher withoutTypes = peer(OTHER_DID);
        withoutTypes.setTypes(null);
        DataSpaceEventsProperties properties = propertiesWith(null, removal, peer(ANCHOR_2), withoutId, withoutTypes);

        assertEquals(List.of(removal), properties.removalPublishers());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("participantRemovedClamps")
    void clampsParticipantRemovedSettings(String name, Supplier<Object> effective, Object expected) {
        assertEquals(expected, effective.get());
    }

    static Stream<Arguments> participantRemovedClamps() {
        Duration defaultInterval = DataSpaceEventsProperties.DEFAULT_RECONCILE_INTERVAL;
        Duration defaultInitialDelay = DataSpaceEventsProperties.DEFAULT_RECONCILE_INITIAL_DELAY;
        return Stream.of(
                removedClamp("max removals -1 -> 0", p -> p.setMaxRemovalsPerRun(-1),
                        DataSpaceEventsProperties.OnParticipantRemoved::effectiveMaxRemovalsPerRun,
                        DataSpaceEventsProperties.OnParticipantRemoved.MIN_MAX_REMOVALS_PER_RUN),
                removedClamp("max removals 0 stays 0", p -> p.setMaxRemovalsPerRun(0),
                        DataSpaceEventsProperties.OnParticipantRemoved::effectiveMaxRemovalsPerRun, 0),
                removedClamp("max removals in range", p -> p.setMaxRemovalsPerRun(MAX_REMOVALS_PER_RUN),
                        DataSpaceEventsProperties.OnParticipantRemoved::effectiveMaxRemovalsPerRun, MAX_REMOVALS_PER_RUN),
                removedClamp("max adoptions -1 -> 0", p -> p.setMaxAdoptionsPerRun(-1),
                        DataSpaceEventsProperties.OnParticipantRemoved::effectiveMaxAdoptionsPerRun,
                        DataSpaceEventsProperties.OnParticipantRemoved.MIN_MAX_ADOPTIONS_PER_RUN),
                removedClamp("max adoptions in range", p -> p.setMaxAdoptionsPerRun(MAX_ADOPTIONS_PER_RUN),
                        DataSpaceEventsProperties.OnParticipantRemoved::effectiveMaxAdoptionsPerRun, MAX_ADOPTIONS_PER_RUN),
                settingsClamp("page size 0 -> 1", s -> s.setPageSize(0),
                        DataSpaceEventsProperties.ParticipantRemovedSettings::effectivePageSize,
                        DataSpaceEventsProperties.TirWatcherSettings.MIN_PAGE_SIZE),
                settingsClamp("page size 500 -> 100", s -> s.setPageSize(500),
                        DataSpaceEventsProperties.ParticipantRemovedSettings::effectivePageSize,
                        DataSpaceEventsProperties.TirWatcherSettings.MAX_PAGE_SIZE),
                settingsClamp("page size in range", s -> s.setPageSize(REMOVED_PAGE_SIZE),
                        DataSpaceEventsProperties.ParticipantRemovedSettings::effectivePageSize, REMOVED_PAGE_SIZE),
                settingsClamp("max pages 0 -> 1", s -> s.setMaxPages(0),
                        DataSpaceEventsProperties.ParticipantRemovedSettings::effectiveMaxPages,
                        DataSpaceEventsProperties.ParticipantRemovedSettings.MIN_MAX_PAGES),
                settingsClamp("max pages in range", s -> s.setMaxPages(REMOVED_MAX_PAGES),
                        DataSpaceEventsProperties.ParticipantRemovedSettings::effectiveMaxPages, REMOVED_MAX_PAGES),
                settingsClamp("interval null -> default", s -> s.getReconcile().setInterval(null),
                        s -> s.getReconcile().effectiveInterval(), defaultInterval),
                settingsClamp("interval PT0S -> default", s -> s.getReconcile().setInterval(Duration.ZERO),
                        s -> s.getReconcile().effectiveInterval(), defaultInterval),
                settingsClamp("interval in range", s -> s.getReconcile().setInterval(SWEEP_INTERVAL),
                        s -> s.getReconcile().effectiveInterval(), SWEEP_INTERVAL),
                settingsClamp("initial delay -PT1S -> default", s -> s.getReconcile().setInitialDelay(Duration.ofSeconds(-1)),
                        s -> s.getReconcile().effectiveInitialDelay(), defaultInitialDelay),
                settingsClamp("initial delay in range", s -> s.getReconcile().setInitialDelay(SWEEP_INITIAL_DELAY),
                        s -> s.getReconcile().effectiveInitialDelay(), SWEEP_INITIAL_DELAY));
    }

    private static Arguments removedClamp(String name, Consumer<DataSpaceEventsProperties.OnParticipantRemoved> configure,
                                          Function<DataSpaceEventsProperties.OnParticipantRemoved, Object> effective,
                                          Object expected) {
        DataSpaceEventsProperties.OnParticipantRemoved policy = new DataSpaceEventsProperties.OnParticipantRemoved();
        configure.accept(policy);
        return Arguments.of(name, (Supplier<Object>) () -> effective.apply(policy), expected);
    }

    private static Arguments settingsClamp(String name, Consumer<DataSpaceEventsProperties.ParticipantRemovedSettings> configure,
                                           Function<DataSpaceEventsProperties.ParticipantRemovedSettings, Object> effective,
                                           Object expected) {
        DataSpaceEventsProperties.ParticipantRemovedSettings settings = new DataSpaceEventsProperties.ParticipantRemovedSettings();
        configure.accept(settings);
        return Arguments.of(name, (Supplier<Object>) () -> effective.apply(settings), expected);
    }

    @ParameterizedTest(name = "listener {0}, types {1} -> participant-removed beans {2}")
    @MethodSource("participantRemovedConditions")
    void participantRemovedCondition(boolean listenerEnabled, List<String> types, boolean expected) {
        DataSpaceEventsProperties.Publisher publisher = peer(ANCHOR_1);
        publisher.setTypes(types);
        DataSpaceEventsProperties properties = propertiesWith(null, publisher);
        properties.getListener().setEnabled(listenerEnabled);
        ConditionContext conditionContext = mock(ConditionContext.class);
        when(conditionContext.getBean(DataSpaceEventsProperties.class)).thenReturn(properties);

        assertEquals(expected, new DataSpaceEventsProperties.ParticipantRemovedCondition().matches(conditionContext));
    }

    static Stream<Arguments> participantRemovedConditions() {
        return Stream.of(
                Arguments.of(false, List.of(REMOVED), false),
                Arguments.of(true, List.of(TYPE_A), false),
                Arguments.of(true, List.of(TYPE_A, REMOVED), true));
    }

    @ParameterizedTest(name = "mode {0} -> tag {1}, writes {2}")
    @MethodSource("modeTags")
    void modeTagValue(RemovalMode mode, String tag, boolean writes) {
        assertEquals(tag, mode.tagValue());
        assertEquals(writes, mode.writes());
    }

    static Stream<Arguments> modeTags() {
        return Stream.of(
                Arguments.of(RemovalMode.REPORT, "report", false),
                Arguments.of(RemovalMode.DATASPACE, "dataspace", true),
                Arguments.of(RemovalMode.GRANTED, "granted", true),
                Arguments.of(RemovalMode.FULL, "full", true));
    }

    @ParameterizedTest(name = "mode value {0} -> {1}")
    @MethodSource("modeValues")
    void parsesModeCaseInsensitively(String value, RemovalMode expected) {
        assertEquals(expected, RemovalMode.fromValue(value));
    }

    static Stream<Arguments> modeValues() {
        return Stream.of(
                Arguments.of("dataspace", RemovalMode.DATASPACE),
                Arguments.of("GRANTED", RemovalMode.GRANTED),
                Arguments.of(" Full ", RemovalMode.FULL));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> RemovalMode.fromValue("delete"));
    }

    @Test
    void bindsParticipantRemovedPolicy() {
        Map<String, Object> policy = Map.of(
                "mode", "granted",
                "keep", List.of(OTHER_DID),
                "overrides", List.of(Map.of("id", REMOVED_DID, "mode", "full")),
                "max-removals-per-run", MAX_REMOVALS_PER_RUN,
                "adopt-unattributed", true,
                "max-adoptions-per-run", MAX_ADOPTIONS_PER_RUN,
                "registry-url", REGISTRY_URL);
        Map<String, Object> removedConfig = Map.of(
                PREFIX + "publishers", List.of(
                        Map.of("id", ANCHOR_1, "origin", PROVIDER_ORIGIN, "types", List.of(REMOVED), "on-participant-removed", policy),
                        Map.of("id", ANCHOR_2, "origin", CONSUMER_ORIGIN, "types", List.of(REMOVED))),
                PREFIX + "participant-removed.page-size", REMOVED_PAGE_SIZE,
                PREFIX + "participant-removed.max-pages", REMOVED_MAX_PAGES,
                PREFIX + "participant-removed.reconcile.initial-delay", SWEEP_INITIAL_DELAY.toString(),
                PREFIX + "participant-removed.reconcile.interval", SWEEP_INTERVAL.toString());
        try (ApplicationContext ctx = ApplicationContext.builder()
                .properties(ISOLATED)
                .propertySources(PropertySource.of("test", removedConfig))
                .start()) {
            DataSpaceEventsProperties properties = ctx.getBean(DataSpaceEventsProperties.class);
            DataSpaceEventsProperties.OnParticipantRemoved bound = properties.getPublishers().get(0).getOnParticipantRemoved();
            DataSpaceEventsProperties.OnParticipantRemoved defaults = properties.getPublishers().get(1).getOnParticipantRemoved();
            DataSpaceEventsProperties.ParticipantRemovedSettings settings = properties.getParticipantRemoved();

            assertEquals(RemovalMode.GRANTED, bound.getMode());
            assertEquals(List.of(OTHER_DID), bound.getKeep());
            assertEquals(List.of(override(REMOVED_DID, RemovalMode.FULL)), bound.getOverrides());
            assertEquals(MAX_REMOVALS_PER_RUN, bound.getMaxRemovalsPerRun());
            assertTrue(bound.isAdoptUnattributed());
            assertEquals(MAX_ADOPTIONS_PER_RUN, bound.getMaxAdoptionsPerRun());
            assertEquals(REGISTRY_URL, bound.getRegistryUrl());
            assertEquals(Optional.of(RemovalMode.FULL), properties.removalMode(ANCHOR_1, REMOVED_DID, OWN_ID));
            assertEquals(Optional.empty(), properties.removalMode(ANCHOR_2, OTHER_DID, OWN_ID));

            assertEquals(RemovalMode.REPORT, defaults.getMode());
            assertTrue(defaults.getKeep().isEmpty());
            assertTrue(defaults.getOverrides().isEmpty());
            assertEquals(DataSpaceEventsProperties.DEFAULT_MAX_REMOVALS_PER_RUN, defaults.getMaxRemovalsPerRun());
            assertFalse(defaults.isAdoptUnattributed());
            assertEquals(DataSpaceEventsProperties.DEFAULT_MAX_ADOPTIONS_PER_RUN, defaults.getMaxAdoptionsPerRun());
            assertNull(defaults.getRegistryUrl());

            assertEquals(REMOVED_PAGE_SIZE, settings.getPageSize());
            assertEquals(REMOVED_MAX_PAGES, settings.getMaxPages());
            assertEquals(SWEEP_INITIAL_DELAY, settings.getReconcile().getInitialDelay());
            assertEquals(SWEEP_INTERVAL, settings.getReconcile().getInterval());
        }
    }

    @Test
    void bindsParticipantRemovedDefaults() {
        DataSpaceEventsProperties.ParticipantRemovedSettings settings = properties().getParticipantRemoved();

        assertEquals(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_REMOVED_PAGE_SIZE, settings.getPageSize());
        assertEquals(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_REMOVED_MAX_PAGES, settings.getMaxPages());
        assertEquals(DataSpaceEventsProperties.DEFAULT_RECONCILE_INITIAL_DELAY, settings.getReconcile().getInitialDelay());
        assertEquals(DataSpaceEventsProperties.DEFAULT_RECONCILE_INTERVAL, settings.getReconcile().getInterval());
        assertTrue(properties().removalPublishers().isEmpty());
        assertEquals(AdoptionPolicy.OFF, properties().adoption());
        assertFalse(properties().removalWritesConfigured());
    }

    // Pins the binding gap that the Step 7 publisher-entries guard detects from the raw property list.
    @ParameterizedTest(name = "{0} drops the publisher entry")
    @MethodSource("unconvertiblePolicies")
    void unconvertiblePolicyDropsPublisherEntryButKeepsRawEntry(String name, Map<String, Object> policy) {
        config.put(PREFIX + "publishers", List.of(
                Map.of("id", ANCHOR_1, "origin", PROVIDER_ORIGIN, "types", List.of(REMOVED), "on-participant-removed", policy),
                Map.of("id", ANCHOR_2, "origin", CONSUMER_ORIGIN, "types", List.of(REMOVED))));
        refresh();

        assertEquals(List.of(ANCHOR_2), properties().getPublishers().stream().map(DataSpaceEventsProperties.Publisher::getId).toList());
        assertEquals(2, context.getEnvironment().getProperty(PREFIX + "publishers", List.class).orElseThrow().size());
    }

    static Stream<Arguments> unconvertiblePolicies() {
        return Stream.of(
                Arguments.of("misspelt mode", Map.of("mode", "dataspce")),
                Arguments.of("misspelt override mode", Map.of("overrides", List.of(Map.of("id", REMOVED_DID, "mode", "ful")))),
                Arguments.of("non-numeric max-removals-per-run", Map.of("max-removals-per-run", "many")));
    }
}
