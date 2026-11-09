package org.fiware.iam.configuration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.env.PropertySource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
