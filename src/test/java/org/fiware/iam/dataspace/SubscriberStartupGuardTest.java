package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that an enabled subscriber only starts with the listener, an own id, an absolute http(s) sink and a protocol client id.
 */
class SubscriberStartupGuardTest {

    private static final String SUBSCRIBER_ENABLED = "dataspace.events.subscriber.enabled";
    private static final String SINK = "https://cm.consumer.org/events";
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final String OWN_ID = "did:web:consumer.org";

    static Stream<Arguments> startingConfigurations() {
        return Stream.of(
                Arguments.of("valid", valid(), true),
                Arguments.of("subscriber disabled, nothing set", Map.of(SUBSCRIBER_ENABLED, "false"), false));
    }

    static Stream<Arguments> failingConfigurations() {
        return Stream.of(
                Arguments.of("listener disabled", with(SubscriberStartupGuard.LISTENER_ENABLED_KEY, "false"),
                        SubscriberStartupGuard.LISTENER_ENABLED_KEY),
                // the URL rules live in SubscriberSettingsValidatorTest; one case per key proves the delegation
                Arguments.of("no sink", without(SubscriberSettingsValidator.SINK_KEY), SubscriberSettingsValidator.SINK_KEY),
                Arguments.of("no protocol client id", without(SubscriberSettingsValidator.PROTOCOL_CLIENT_ID_KEY),
                        SubscriberSettingsValidator.PROTOCOL_CLIENT_ID_KEY),
                Arguments.of("no own id", without(SubscriberSettingsValidator.OWN_ID_KEY), SubscriberSettingsValidator.OWN_ID_KEY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startingConfigurations")
    void starts(String description, Map<String, Object> subscriber, boolean guardExpected) {
        try (ApplicationContext context = run(subscriber)) {
            assertTrue(context.isRunning());
            assertEquals(guardExpected, context.containsBean(SubscriberStartupGuard.class));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingConfigurations")
    void failsStartup(String description, Map<String, Object> subscriber, String offendingKey) {
        BeanInstantiationException exception = assertThrows(BeanInstantiationException.class, () -> run(subscriber).close());
        ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
        assertTrue(cause.getMessage().contains(offendingKey), cause.getMessage());
    }

    private static Map<String, Object> valid() {
        return Map.of(
                SUBSCRIBER_ENABLED, "true",
                SubscriberStartupGuard.LISTENER_ENABLED_KEY, "true",
                SubscriberSettingsValidator.SINK_KEY, SINK,
                SubscriberSettingsValidator.PROTOCOL_CLIENT_ID_KEY, PROTOCOL_CLIENT_ID,
                SubscriberSettingsValidator.OWN_ID_KEY, OWN_ID);
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> config = new HashMap<>(valid());
        config.put(key, value);
        return config;
    }

    private static Map<String, Object> without(String key) {
        Map<String, Object> config = new HashMap<>(valid());
        config.remove(key);
        return config;
    }

    private static ApplicationContext run(Map<String, Object> subscriber) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "dataspace.events.listener.verifier.audience", "contract-management",
                "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks"));
        properties.putAll(subscriber);
        // without the test application.yaml, which enables further features
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(properties)
                .start();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
