package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
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
 * Checks that a publisher or the participant.removed handling only starts with a management port separate from the
 * server port.
 */
class ManagementPortGuardTest {

    private static final String MANAGEMENT_PORT = "endpoints.all.port";
    private static final String SERVER_PORT = "micronaut.server.port";
    private static final String PUBLISHER_ENABLED = "dataspace.events.publisher.enabled";
    private static final String LISTENER_ENABLED = "dataspace.events.listener.enabled";
    private static final String PUBLISHER_ID = "dataspace.events.publishers[0].id";
    private static final String REMOVAL_PUBLISHER = "did:web:anchor.org";
    private static final String ALL_ENDPOINTS_ENABLED = "endpoints.all.enabled";
    private static final String PUBLISH_ENDPOINT_ENABLED = "endpoints." + PublishEndpoint.ID + ".enabled";
    private static final String REPORT_ENDPOINT_ENABLED = "endpoints." + RemovedParticipantsEndpoint.ID + ".enabled";

    static Stream<Arguments> startingConfigurations() {
        return Stream.of(
                Arguments.of("distinct ports", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "9090", SERVER_PORT, "8080")),
                Arguments.of("distinct from the default server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "9090")),
                Arguments.of("publisher disabled, no management port", Map.of(PUBLISHER_ENABLED, "false")),
                Arguments.of("publisher disabled, same ports", Map.of(PUBLISHER_ENABLED, "false", MANAGEMENT_PORT, "8080")),
                Arguments.of("removal handling, distinct ports", removalHandling(Map.of(MANAGEMENT_PORT, "9090"))),
                Arguments.of("listener without removal publisher, no management port",
                        Map.of(LISTENER_ENABLED, "true", PUBLISHER_ID, REMOVAL_PUBLISHER)),
                Arguments.of("publish endpoint disabled, same ports",
                        Map.of(PUBLISHER_ENABLED, "true", PUBLISH_ENDPOINT_ENABLED, "false", MANAGEMENT_PORT, "8080")),
                Arguments.of("removal handling, report endpoint disabled, same ports",
                        removalHandling(Map.of(REPORT_ENDPOINT_ENABLED, "false", MANAGEMENT_PORT, "8080"))),
                Arguments.of("removal handling, report endpoint disabled, no management port",
                        removalHandling(Map.of(REPORT_ENDPOINT_ENABLED, "false"))),
                Arguments.of("removal handling, all endpoints disabled, no management port",
                        removalHandling(Map.of(ALL_ENDPOINTS_ENABLED, "false"))));
    }

    static Stream<Arguments> failingConfigurations() {
        return Stream.of(
                Arguments.of("no management port", Map.of(PUBLISHER_ENABLED, "true", SERVER_PORT, "8080"),
                        ManagementPortGuard.PUBLISH_ENDPOINT),
                Arguments.of("same as the server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "8081", SERVER_PORT, "8081"),
                        ManagementPortGuard.PUBLISH_ENDPOINT),
                Arguments.of("same as the default server port", Map.of(PUBLISHER_ENABLED, "true", MANAGEMENT_PORT, "8080"),
                        ManagementPortGuard.PUBLISH_ENDPOINT),
                Arguments.of("removal handling, no management port", removalHandling(Map.of()),
                        ManagementPortGuard.REPORT_ENDPOINT),
                Arguments.of("removal handling, same as the default server port", removalHandling(Map.of(MANAGEMENT_PORT, "8080")),
                        ManagementPortGuard.REPORT_ENDPOINT),
                Arguments.of("removal handling, report endpoint enabled although all are disabled",
                        removalHandling(Map.of(ALL_ENDPOINTS_ENABLED, "false", REPORT_ENDPOINT_ENABLED, "true")),
                        ManagementPortGuard.REPORT_ENDPOINT),
                Arguments.of("publish endpoint disabled, removal handling, no management port",
                        removalHandling(Map.of(PUBLISHER_ENABLED, "true", PUBLISH_ENDPOINT_ENABLED, "false")),
                        ManagementPortGuard.REPORT_ENDPOINT));
    }

    // the listener with a publisher of participant.removed enables the removed-participants report
    private static Map<String, Object> removalHandling(Map<String, Object> ports) {
        Map<String, Object> properties = new HashMap<>(ports);
        properties.put(LISTENER_ENABLED, "true");
        properties.put(PUBLISHER_ID, REMOVAL_PUBLISHER);
        properties.put("dataspace.events.publishers[0].types[0]", DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE);
        return properties;
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
    void failsStartup(String description, Map<String, Object> ports, String endpoint) {
        BeanInstantiationException exception = assertThrows(BeanInstantiationException.class, () -> run(ports).close());
        ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
        assertTrue(cause.getMessage().contains(MANAGEMENT_PORT) && cause.getMessage().contains(SERVER_PORT)
                && cause.getMessage().contains(endpoint), cause.getMessage());
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
