package org.fiware.iam.dataspace;

import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.SubscriptionLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionRegistryTest {

    private static final Instant START = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration LEASE_TTL = Duration.ofHours(1);
    private static final String SUBSCRIBER = "did:web:subscriber.org";
    private static final String OTHER_SUBSCRIBER = "did:web:other.org";
    private static final URI SINK = URI.create("https://subscriber.org/events");
    private static final URI OTHER_SINK = URI.create("https://subscriber.org/other-events");
    private static final String TYPE = "org.fiware.dataspace.offering.created";
    private static final String OTHER_TYPE = "org.fiware.dataspace.offering.deleted";
    private static final String CLIENT_ID = "events-client";
    private static final Set<String> SCOPES = Set.of("events");
    private static final int RATE = 120;
    private static final int OTHER_RATE = 60;

    private DataSpaceEventsProperties properties;
    private MutableClock clock;
    private SubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        properties.getPublisher().setLeaseTtl(LEASE_TTL);
        clock = new MutableClock(START);
        registry = new SubscriptionRegistry(properties, clock);
    }

    @Test
    void registersWithTheConfiguredLease() {
        Subscription subscription = register(SUBSCRIBER, SINK, List.of(TYPE));

        assertEquals(START.plus(LEASE_TTL), subscription.expiresAt());
        assertEquals(Optional.of(subscription), registry.get(subscription.id()));
        assertEquals(List.of(subscription), registry.active(TYPE));
        assertEquals(List.of(), registry.active(OTHER_TYPE));
    }

    @Test
    void renewsTheSameSinkInPlace() {
        Subscription original = register(SUBSCRIBER, SINK, List.of(TYPE));
        clock.advance(Duration.ofMinutes(30));

        Subscription renewed = registry.register(SUBSCRIBER, SINK, List.of(OTHER_TYPE), CLIENT_ID, SCOPES, OTHER_RATE);

        assertEquals(original.id(), renewed.id());
        assertEquals(clock.instant().plus(LEASE_TTL), renewed.expiresAt());
        assertEquals(List.of(OTHER_TYPE), renewed.types());
        assertEquals(OTHER_RATE, renewed.allowedRate());
        assertEquals(Optional.of(renewed), registry.get(original.id()));
        assertEquals(List.of(), registry.active(TYPE));
    }

    @Test
    void treatsMissingScopesAsNone() {
        Subscription subscription = registry.register(SUBSCRIBER, SINK, List.of(TYPE), CLIENT_ID, null, RATE);

        assertEquals(Set.of(), subscription.scopes());
    }

    @Test
    void createsSeparateSubscriptionsPerSinkAndSubscriber() {
        Subscription first = register(SUBSCRIBER, SINK, List.of(TYPE));
        Subscription otherSink = register(SUBSCRIBER, OTHER_SINK, List.of(TYPE));
        Subscription otherSubscriber = register(OTHER_SUBSCRIBER, SINK, List.of(TYPE));

        assertEquals(3, Set.of(first.id(), otherSink.id(), otherSubscriber.id()).size());
        assertEquals(Set.of(first, otherSink, otherSubscriber), Set.copyOf(registry.active(TYPE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caps")
    void enforcesCaps(String description, int maxPerSubscriber, int maxTotal, List<String> existingSubscribers) {
        properties.getPublisher().setMaxSubscriptionsPerSubscriber(maxPerSubscriber);
        properties.getPublisher().setMaxSubscriptions(maxTotal);
        for (int i = 0; i < existingSubscribers.size(); i++) {
            register(existingSubscribers.get(i), sink(i), List.of(TYPE));
        }

        assertThrows(SubscriptionLimitException.class,
                () -> register(SUBSCRIBER, OTHER_SINK, List.of(TYPE)), description);
        // a renewal adds nothing, so it is accepted even at the cap
        if (existingSubscribers.contains(SUBSCRIBER)) {
            int index = existingSubscribers.indexOf(SUBSCRIBER);
            register(SUBSCRIBER, sink(index), List.of(OTHER_TYPE));
        }
    }

    static Stream<Arguments> caps() {
        return Stream.of(
                Arguments.of("per subscriber", 2, 10, List.of(SUBSCRIBER, SUBSCRIBER)),
                Arguments.of("per subscriber, others do not count", 1, 10, List.of(OTHER_SUBSCRIBER, SUBSCRIBER)),
                Arguments.of("total", 10, 2, List.of(OTHER_SUBSCRIBER, OTHER_SUBSCRIBER)),
                Arguments.of("total including own", 10, 2, List.of(OTHER_SUBSCRIBER, SUBSCRIBER)));
    }

    @ParameterizedTest(name = "per subscriber {0}, total {1}")
    @MethodSource("invalidCaps")
    void fallsBackToTheDefaultCapsWhenInvalid(int maxPerSubscriber, int maxTotal) {
        properties.getPublisher().setMaxSubscriptionsPerSubscriber(maxPerSubscriber);
        properties.getPublisher().setMaxSubscriptions(maxTotal);

        register(SUBSCRIBER, SINK, List.of(TYPE));

        assertEquals(1, registry.active(TYPE).size());
    }

    static Stream<Arguments> invalidCaps() {
        return Stream.of(Arguments.of(0, 0), Arguments.of(-1, -1));
    }

    @Test
    void appliesChangedCapsOnTheNextRegistration() {
        register(SUBSCRIBER, SINK, List.of(TYPE));
        properties.getPublisher().setMaxSubscriptionsPerSubscriber(1);

        assertThrows(SubscriptionLimitException.class, () -> register(SUBSCRIBER, OTHER_SINK, List.of(TYPE)));
    }

    @Test
    void doesNotCountExpiredSubscriptions() {
        properties.getPublisher().setMaxSubscriptionsPerSubscriber(1);
        properties.getPublisher().setMaxSubscriptions(1);
        Subscription expired = register(SUBSCRIBER, SINK, List.of(TYPE));
        clock.advance(LEASE_TTL);

        Subscription fresh = register(SUBSCRIBER, OTHER_SINK, List.of(TYPE));

        assertNotEquals(expired.id(), fresh.id());
        assertEquals(List.of(fresh), registry.active(TYPE));
    }

    @Test
    void neitherReturnsNorDeliversExpiredSubscriptions() {
        Subscription subscription = register(SUBSCRIBER, SINK, List.of(TYPE));
        clock.advance(LEASE_TTL);

        assertEquals(Optional.empty(), registry.get(subscription.id()));
        assertEquals(List.of(), registry.active(TYPE));
        assertFalse(registry.remove(subscription.id(), SUBSCRIBER));
    }

    @Test
    void renewsAnExpiredSubscriptionUnderANewId() {
        Subscription expired = register(SUBSCRIBER, SINK, List.of(TYPE));
        clock.advance(LEASE_TTL);

        assertNotEquals(expired.id(), register(SUBSCRIBER, SINK, List.of(TYPE)).id());
    }

    @Test
    void evictsExpiredSubscriptionsPeriodically() {
        Subscription subscription = register(SUBSCRIBER, SINK, List.of(TYPE));
        clock.advance(LEASE_TTL);

        registry.evictExpired();
        clock.set(START);

        assertEquals(Optional.empty(), registry.get(subscription.id()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removals")
    void removesOnlyOwnSubscriptions(String description, String caller, boolean removed) {
        Subscription subscription = register(SUBSCRIBER, SINK, List.of(TYPE));

        assertEquals(removed, registry.remove(subscription.id(), caller), description);
        assertEquals(!removed, registry.get(subscription.id()).isPresent(), description);
    }

    static Stream<Arguments> removals() {
        return Stream.of(
                Arguments.of("owner", SUBSCRIBER, true),
                Arguments.of("non-owner", OTHER_SUBSCRIBER, false));
    }

    @Test
    void doesNotRemoveUnknownSubscriptions() {
        assertFalse(registry.remove("unknown", SUBSCRIBER));
        assertFalse(registry.remove("unknown"));
    }

    @Test
    void removesRegardlessOfTheOwner() {
        Subscription subscription = register(SUBSCRIBER, SINK, List.of(TYPE));

        assertTrue(registry.remove(subscription.id()));
        assertEquals(Optional.empty(), registry.get(subscription.id()));
    }

    private Subscription register(String subscriberId, URI sink, List<String> types) {
        return registry.register(subscriberId, sink, types, CLIENT_ID, SCOPES, RATE);
    }

    private static URI sink(int index) {
        return URI.create("https://subscriber.org/sink-" + index);
    }
}
