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
    private static final String AUDIENCE = "contract-management";
    private static final String JWKS_URL = "http://verifier/.well-known/jwks";
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
                PREFIX + "listener.verifier.audience", AUDIENCE,
                PREFIX + "listener.verifier.jwks-url", JWKS_URL,
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
        assertEquals(AUDIENCE, listener.getVerifier().getAudience());
        assertEquals(JWKS_URL, listener.getVerifier().getJwksUrl());
        assertNull(listener.getVerifier().getIssuer());
        assertEquals(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM, listener.getVerifier().getParticipantClaim());
        assertEquals(DataSpaceEventsProperties.DEFAULT_ALGORITHMS, listener.getVerifier().getAlgorithms());
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
                // an enabled publisher starts the bearer verifier, which needs its configuration
                .properties(Map.of(PREFIX + "listener.verifier.audience", AUDIENCE, PREFIX + "listener.verifier.jwks-url", JWKS_URL))
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

    @ParameterizedTest(name = "listener {0}, publisher {1} -> verifier {2}, publisher beans {3}")
    @MethodSource("conditions")
    void conditions(boolean listenerEnabled, boolean publisherEnabled, boolean verifierExpected, boolean publisherExpected) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getListener().setEnabled(listenerEnabled);
        properties.getPublisher().setEnabled(publisherEnabled);
        ConditionContext conditionContext = mock(ConditionContext.class);
        when(conditionContext.getBean(DataSpaceEventsProperties.class)).thenReturn(properties);

        assertEquals(verifierExpected, new DataSpaceEventsProperties.VerifierCondition().matches(conditionContext));
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
        config.put(PREFIX + "listener.verifier.algorithms", List.of("ES384"));
        config.put(PREFIX + "listener.verifier.audience", "");
        refresh();

        assertTrue(properties.isAuthorized(CONSUMER_ID, TYPE_B));
        assertFalse(properties.isAuthorized(PROVIDER_ID, TYPE_A));
        assertEquals(Optional.empty(), properties.allowedOrigin(PROVIDER_ORIGIN));
        assertEquals(MAX_ENTRIES + 1, properties.getListener().getDedup().getMaxEntries());
        assertEquals(Set.of("ES384"), properties.getListener().getVerifier().getAlgorithms());
        assertTrue(properties.getListener().getVerifier().getAudience().isBlank());
    }
}
