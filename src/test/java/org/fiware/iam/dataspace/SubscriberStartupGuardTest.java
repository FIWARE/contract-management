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
 * Checks that an enabled subscriber only starts with the listener, an absolute http(s) sink and a protocol client id.
 */
class SubscriberStartupGuardTest {

    private static final String SUBSCRIBER_ENABLED = "dataspace.events.subscriber.enabled";
    private static final String SINK = "https://cm.consumer.org/events";
    private static final String PROTOCOL_CLIENT_ID = "contract-management";

    static Stream<Arguments> startingConfigurations() {
        return Stream.of(
                Arguments.of("valid", valid(), true),
                Arguments.of("http sink", with(SubscriberStartupGuard.SINK_KEY, "http://cm.consumer.org/events"), true),
                Arguments.of("subscriber disabled, nothing set", Map.of(SUBSCRIBER_ENABLED, "false"), false));
    }

    static Stream<Arguments> failingConfigurations() {
        return Stream.of(
                Arguments.of("listener disabled", with(SubscriberStartupGuard.LISTENER_ENABLED_KEY, "false"),
                        SubscriberStartupGuard.LISTENER_ENABLED_KEY),
                Arguments.of("no sink", without(SubscriberStartupGuard.SINK_KEY), SubscriberStartupGuard.SINK_KEY),
                Arguments.of("blank sink", with(SubscriberStartupGuard.SINK_KEY, " "), SubscriberStartupGuard.SINK_KEY),
                Arguments.of("relative sink", with(SubscriberStartupGuard.SINK_KEY, "/events"), SubscriberStartupGuard.SINK_KEY),
                Arguments.of("sink without host", with(SubscriberStartupGuard.SINK_KEY, "https:/events"), SubscriberStartupGuard.SINK_KEY),
                Arguments.of("non-http sink", with(SubscriberStartupGuard.SINK_KEY, "ftp://cm.consumer.org/events"),
                        SubscriberStartupGuard.SINK_KEY),
                Arguments.of("malformed sink", with(SubscriberStartupGuard.SINK_KEY, "https://cm consumer/events"),
                        SubscriberStartupGuard.SINK_KEY),
                Arguments.of("no protocol client id", without(SubscriberStartupGuard.PROTOCOL_CLIENT_ID_KEY),
                        SubscriberStartupGuard.PROTOCOL_CLIENT_ID_KEY),
                Arguments.of("blank protocol client id", with(SubscriberStartupGuard.PROTOCOL_CLIENT_ID_KEY, ""),
                        SubscriberStartupGuard.PROTOCOL_CLIENT_ID_KEY));
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
                SubscriberStartupGuard.SINK_KEY, SINK,
                SubscriberStartupGuard.PROTOCOL_CLIENT_ID_KEY, PROTOCOL_CLIENT_ID);
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
