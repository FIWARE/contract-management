package org.fiware.iam.dataspace;

import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.DataSpaceEventsProperties.SubscriberSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Checks the resolution of the effective subscription values and that the snapshot is detached from the properties.
 */
class SubscriptionTargetTest {

    private static final String OWN_ID = "did:web:consumer.org";
    private static final String PUBLISHER_ID = "did:web:provider.org";
    private static final String SUBSCRIPTIONS_URL = "https://cm.provider.org/subscriptions";
    private static final String ORIGIN = "cm.provider.org";
    private static final String TYPE = "org.fiware.dataspace.offering.created";
    private static final String SINK = "https://cm.consumer.org/events";
    private static final String DEFAULT_CLIENT_ID = "subscriber-client";
    private static final String DEFAULT_SCOPE = "subscriber-scope";
    private static final String OVERRIDE_CLIENT_ID = "publisher-client";
    private static final String OVERRIDE_SCOPE = "publisher-scope";
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final String PROTOCOL_SCOPE = "events";
    private static final String CHANGED = "changed";

    static Stream<Arguments> credentials() {
        return Stream.of(
                Arguments.of("no override", null, null, DEFAULT_CLIENT_ID, Set.of(DEFAULT_SCOPE)),
                Arguments.of("override", OVERRIDE_CLIENT_ID, List.of(OVERRIDE_SCOPE), OVERRIDE_CLIENT_ID, Set.of(OVERRIDE_SCOPE)),
                Arguments.of("blank override client id", "", List.of(OVERRIDE_SCOPE), DEFAULT_CLIENT_ID, Set.of(OVERRIDE_SCOPE)),
                Arguments.of("empty override scopes", OVERRIDE_CLIENT_ID, List.of(), OVERRIDE_CLIENT_ID, Set.of(DEFAULT_SCOPE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentials")
    void resolvesTheCredentials(String description, String overrideClientId, List<String> overrideScopes,
                                String expectedClientId, Set<String> expectedScopes) {
        Publisher publisher = publisher();
        publisher.setClientId(overrideClientId);
        publisher.setScopes(overrideScopes);

        SubscriptionTarget target = SubscriptionTarget.of(publisher, settings(), OWN_ID);

        assertEquals(expectedClientId, target.clientId());
        assertEquals(expectedScopes, target.scopes());
    }

    @Test
    void takesTheRemainingValuesFromThePropertiesAndTheOwnId() {
        assertEquals(new SubscriptionTarget(PUBLISHER_ID, SUBSCRIPTIONS_URL, List.of(TYPE), ORIGIN, DEFAULT_CLIENT_ID,
                        Set.of(DEFAULT_SCOPE), OWN_ID, SINK, PROTOCOL_CLIENT_ID, List.of(PROTOCOL_SCOPE)),
                SubscriptionTarget.of(publisher(), settings(), OWN_ID));
    }

    static Stream<Arguments> changes() {
        return Stream.of(
                Arguments.of("id", publisherChange(p -> p.setId(CHANGED))),
                Arguments.of("subscriptions-url", publisherChange(p -> p.setSubscriptionsUrl(CHANGED))),
                Arguments.of("types", publisherChange(p -> p.setTypes(List.of(TYPE, CHANGED)))),
                Arguments.of("origin", publisherChange(p -> p.setOrigin(CHANGED))),
                Arguments.of("override client id", publisherChange(p -> p.setClientId(CHANGED))),
                Arguments.of("override scopes", publisherChange(p -> p.setScopes(List.of(CHANGED)))),
                Arguments.of("default client id", settingsChange(s -> s.setClientId(CHANGED))),
                Arguments.of("default scopes", settingsChange(s -> s.setScopes(List.of(CHANGED)))),
                Arguments.of("sink", settingsChange(s -> s.setSink(CHANGED))),
                Arguments.of("protocol client id", settingsChange(s -> s.getProtocolSettings().setClientId(CHANGED))),
                Arguments.of("protocol scopes", settingsChange(s -> s.getProtocolSettings().setScopes(List.of(CHANGED)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    void changesWithEveryEffectiveValue(String description, BiConsumer<Publisher, SubscriberSettings> change) {
        SubscriptionTarget original = SubscriptionTarget.of(publisher(), settings(), OWN_ID);
        Publisher publisher = publisher();
        SubscriberSettings settings = settings();
        change.accept(publisher, settings);

        assertNotEquals(original, SubscriptionTarget.of(publisher, settings, OWN_ID));
    }

    @Test
    void changesWithTheOwnId() {
        assertNotEquals(SubscriptionTarget.of(publisher(), settings(), OWN_ID),
                SubscriptionTarget.of(publisher(), settings(), CHANGED));
    }

    @Test
    void isDetachedFromTheProperties() {
        Publisher publisher = publisher();
        publisher.setScopes(new ArrayList<>(List.of(OVERRIDE_SCOPE)));
        SubscriberSettings settings = settings();
        SubscriptionTarget target = SubscriptionTarget.of(publisher, settings, OWN_ID);
        SubscriptionTarget copy = SubscriptionTarget.of(publisher, settings, OWN_ID);

        publisher.getTypes().add(CHANGED);
        publisher.getScopes().add(CHANGED);
        settings.getProtocolSettings().getScopes().add(CHANGED);

        assertEquals(copy, target);
        assertEquals(List.of(TYPE), target.types());
        assertEquals(Set.of(OVERRIDE_SCOPE), target.scopes());
        assertEquals(List.of(PROTOCOL_SCOPE), target.protocolScopes());
    }

    private static BiConsumer<Publisher, SubscriberSettings> publisherChange(Consumer<Publisher> change) {
        return (publisher, settings) -> change.accept(publisher);
    }

    private static BiConsumer<Publisher, SubscriberSettings> settingsChange(Consumer<SubscriberSettings> change) {
        return (publisher, settings) -> change.accept(settings);
    }

    private static Publisher publisher() {
        Publisher publisher = new Publisher();
        publisher.setId(PUBLISHER_ID);
        publisher.setSubscriptionsUrl(SUBSCRIPTIONS_URL);
        publisher.setOrigin(ORIGIN);
        publisher.setTypes(new ArrayList<>(List.of(TYPE)));
        return publisher;
    }

    private static SubscriberSettings settings() {
        SubscriberSettings settings = new SubscriberSettings();
        settings.setSink(SINK);
        settings.setClientId(DEFAULT_CLIENT_ID);
        settings.setScopes(new ArrayList<>(List.of(DEFAULT_SCOPE)));
        settings.getProtocolSettings().setClientId(PROTOCOL_CLIENT_ID);
        settings.getProtocolSettings().setScopes(new ArrayList<>(List.of(PROTOCOL_SCOPE)));
        return settings;
    }
}
