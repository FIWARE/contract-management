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
import org.fiware.iam.exception.TirEnumerationException;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that a DEGRADED trust anchor watcher keeps every health probe on the management port at 200.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TirWatcherHealthProbeTest implements TestPropertyProvider {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final String CYCLE_DELAY = "PT0.1S";
    private static final String STATUS_FIELD = "status";
    private static final String HEALTH_PATH = "/health";

    private final int serverPort = freePort();
    private final int managementPort = freePort();
    private final HttpClient client = HttpClient.create(url(managementPort));

    @Inject
    private TirWatcherHealthIndicator indicator;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(serverPort));
        properties.put("endpoints.all.port", String.valueOf(managementPort));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("dataspace.events.own-participant-id", "did:web:trust-anchor.org");
        properties.put("dataspace.events.publisher.enabled", "true");
        properties.put("dataspace.events.publisher.origin", "cm.trust-anchor.org");
        properties.put("dataspace.events.tir-watcher.enabled", "true");
        properties.put("dataspace.events.tir-watcher.initial-delay", CYCLE_DELAY);
        properties.put("dataspace.events.tir-watcher.interval", CYCLE_DELAY);
        return properties;
    }

    @MockBean(TrustedIssuersRegistryReader.class)
    TrustedIssuersRegistryReader unreachableRegistry() {
        TrustedIssuersRegistryReader unreachable = mock(TrustedIssuersRegistryReader.class);
        when(unreachable.enumerate()).thenThrow(new TirEnumerationException("unreachable", 1, null, null));
        return unreachable;
    }

    @BeforeAll
    void awaitDegraded() {
        await().atMost(AWAIT_TIMEOUT).until(() -> indicator.result().getStatus().equals(DataSpaceHealth.DEGRADED));
    }

    @AfterAll
    void closeClient() {
        client.close();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/health", "/health/readiness", "/health/liveness"})
    void degradedWatcherDoesNotFailTheProbe(String path) {
        assertEquals(HttpStatus.OK, get(path).getStatus());
    }

    @Test
    void healthShowsTheDegradedAggregateAndTheReason() {
        assertEquals(DataSpaceHealth.DEGRADED.getName(), get(HEALTH_PATH).body().get(STATUS_FIELD));
        HealthResult result = indicator.result();
        assertEquals(TirWatcherHealthIndicator.NO_SUCCESSFUL_CYCLE_REASON,
                ((Map<?, ?>) result.getDetails()).get(TirWatcherHealthIndicator.REASON_DETAIL));
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
