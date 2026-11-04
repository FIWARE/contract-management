package org.fiware.iam.dataspace;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.exception.EventSubscriptionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Mono.error;

/**
 * Checks that a DEGRADED subscriber keeps every health probe on the management port at 200 (Decision 8 of the plan).
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSpaceSubscriptionHealthProbeTest implements TestPropertyProvider {

    private static final String PUBLISHER_ID = "did:web:provider.org";
    private static final String SUBSCRIPTIONS_URL = "https://cm.provider.org/subscriptions";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String STATUS_FIELD = "status";
    private static final String HEALTH_PATH = "/health";

    private final int serverPort = freePort();
    private final int managementPort = freePort();
    private final HttpClient client = HttpClient.create(url(managementPort));

    // counted by the mock itself: Mockito.verify rejects the injected bean with NotAMock
    private final AtomicInteger subscribeCalls = new AtomicInteger();

    @Inject
    private DataSpaceSubscriptionHealthIndicator indicator;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(serverPort));
        properties.put("endpoints.all.port", String.valueOf(managementPort));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("dataspace.events.listener.enabled", "true");
        properties.put("dataspace.events.listener.verifier.audience", "contract-management");
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks");
        properties.put("dataspace.events.own-participant-id", "did:web:consumer.org");
        properties.put("dataspace.events.subscriber.enabled", "true");
        properties.put("dataspace.events.subscriber.sink", "https://cm.consumer.org/events");
        properties.put("dataspace.events.subscriber.protocol-settings.client-id", "contract-management");
        properties.put("dataspace.events.subscriber.initial-delay", "PT0.1S");
        properties.put("dataspace.events.publishers[0].id", PUBLISHER_ID);
        properties.put("dataspace.events.publishers[0].origin", "cm.provider.org");
        properties.put("dataspace.events.publishers[0].types[0]", "org.fiware.dataspace.example");
        properties.put("dataspace.events.publishers[0].subscriptions-url", SUBSCRIPTIONS_URL);
        return properties;
    }

    @MockBean(PublisherSubscriptionClient.class)
    PublisherSubscriptionClient subscriptionClient() {
        PublisherSubscriptionClient failing = mock(PublisherSubscriptionClient.class);
        when(failing.subscribe(any())).thenAnswer(invocation -> {
            subscribeCalls.incrementAndGet();
            return error(new EventSubscriptionException(PUBLISHER_ID, SUBSCRIPTIONS_URL, "connection refused", null, null));
        });
        return failing;
    }

    @BeforeAll
    void awaitFailedRegistration() {
        await().atMost(AWAIT_TIMEOUT).until(() -> subscribeCalls.get() > 0);
    }

    @AfterAll
    void closeClient() {
        client.close();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/health", "/health/readiness", "/health/liveness"})
    void degradedSubscriberDoesNotFailTheProbe(String path) {
        assertEquals(HttpStatus.OK, get(path).getStatus());
    }

    @Test
    void healthShowsTheDegradedAggregateAndTheIndicatorThePendingPublisher() {
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED.getName(), get(HEALTH_PATH).body().get(STATUS_FIELD));
        HealthResult result = indicator.result();
        assertEquals(DataSpaceSubscriptionHealthIndicator.DEGRADED, result.getStatus());
        assertEquals("PENDING", ((Map<?, ?>) ((Map<?, ?>) result.getDetails()).get(PUBLISHER_ID))
                .get(DataSpaceSubscriptionHealthIndicator.STATE_DETAIL));
    }

    private HttpResponse<Map<String, Object>> get(String path) {
        return client.toBlocking().exchange(HttpRequest.GET(path), Argument.mapOf(String.class, Object.class));
    }

    private static URL url(int port) {
        try {
            return URI.create("http://localhost:" + port).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
