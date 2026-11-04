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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a publisher only starts with a management port separate from the server port.
 */
class ManagementPortGuardTest {

    private static final String MANAGEMENT_PORT = "endpoints.all.port";
    private static final String SERVER_PORT = "micronaut.server.port";
    private static final String PUBLISHER_ENABLED = "dataspace.events.publisher.enabled";

    static Stream<Arguments> startingConfigurations() {
        return Stream.of(
                Arguments.of("distinct ports", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "9090", SERVER_PORT, "8080")),
                Arguments.of("distinct from the default server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "9090")),
                Arguments.of("publisher disabled, no management port", Map.of(PUBLISHER_ENABLED, "false")),
                Arguments.of("publisher disabled, same ports", Map.of(PUBLISHER_ENABLED, "false", MANAGEMENT_PORT, "8080")));
    }

    static Stream<Arguments> failingConfigurations() {
        return Stream.of(
                Arguments.of("no management port", Map.of(PUBLISHER_ENABLED, "true", SERVER_PORT, "8080")),
                Arguments.of("same as the server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "8081", SERVER_PORT, "8081")),
                Arguments.of("same as the default server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "8080")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startingConfigurations")
    void starts(String description, Map<String, Object> ports) {
        try (ApplicationContext context = run(ports)) {
            assertTrue(context.isRunning());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingConfigurations")
    void failsStartup(String description, Map<String, Object> ports) {
        BeanInstantiationException exception = assertThrows(BeanInstantiationException.class, () -> run(ports).close());
        ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
        assertTrue(cause.getMessage().contains(MANAGEMENT_PORT) && cause.getMessage().contains(SERVER_PORT), cause.getMessage());
    }

    private static ApplicationContext run(Map<String, Object> ports) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "dataspace.events.listener.verifier.audience", "contract-management",
                "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks",
                "dataspace.events.publisher.origin", "cm.provider.org"));
        properties.putAll(ports);
        // without the test application.yaml, which sets a management port
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
