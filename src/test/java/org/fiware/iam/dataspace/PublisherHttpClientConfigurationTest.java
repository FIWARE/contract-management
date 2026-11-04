package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PublisherHttpClientConfigurationTest {

    private static final String PUBLISHER = "dataspace.events.publisher.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(4);
    private static final Map<String, Object> CONFIG = Map.of(
            "notification.enabled", false,
            "general.enable-rainbow", false,
            "dataspace.events.listener.verifier.audience", "contract-management",
            "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks",
            PUBLISHER + "enabled", true,
            // a global redirect setting must not reach the publisher client
            "micronaut.http.client.follow-redirects", true);

    @ParameterizedTest(name = "{0}")
    @MethodSource("timeouts")
    void appliesSinkTimeoutsAndNeverFollowsRedirects(String description, Map<String, Object> timeoutConfig,
                                                    Duration connectTimeout, Duration readTimeout,
                                                    Duration handshakeTimeout) {
        Map<String, Object> config = new HashMap<>(CONFIG);
        config.putAll(timeoutConfig);
        try (ApplicationContext context = ApplicationContext.run(config)) {
            PublisherHttpClientConfiguration clientConfiguration = context.getBean(PublisherHttpClientConfiguration.class);

            assertFalse(clientConfiguration.isFollowRedirects(), description);
            assertEquals(Optional.of(connectTimeout), clientConfiguration.getConnectTimeout(), description);
            assertEquals(Optional.of(readTimeout), clientConfiguration.getReadTimeout(), description);
            assertEquals(handshakeTimeout,
                    context.getBean(DataSpaceEventsProperties.class).getPublisher().getHandshakeTimeout(), description);
        }
    }

    static Stream<Arguments> timeouts() {
        return Stream.of(
                Arguments.of("defaults", Map.of(),
                        DataSpaceEventsProperties.DEFAULT_SINK_CONNECT_TIMEOUT,
                        DataSpaceEventsProperties.DEFAULT_SINK_READ_TIMEOUT,
                        DataSpaceEventsProperties.DEFAULT_HANDSHAKE_TIMEOUT),
                Arguments.of("configured", Map.of(
                                PUBLISHER + "sink-connect-timeout", CONNECT_TIMEOUT.toString(),
                                PUBLISHER + "sink-read-timeout", READ_TIMEOUT.toString(),
                                PUBLISHER + "handshake-timeout", HANDSHAKE_TIMEOUT.toString()),
                        CONNECT_TIMEOUT, READ_TIMEOUT, HANDSHAKE_TIMEOUT));
    }
}
