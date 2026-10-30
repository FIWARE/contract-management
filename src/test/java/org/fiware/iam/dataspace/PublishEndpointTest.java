package org.fiware.iam.dataspace;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.MalformedURLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks the internal publish endpoint on the management port: the envelope CM fills in and the rejection of a missing type.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishEndpointTest implements TestPropertyProvider {

    private static final String EVENTS_PATH = "/events";
    private static final String OWN_ID = "did:web:provider.org";
    private static final String DATASPACE_ID = "test-dataspace";
    private static final String TYPE = "org.fiware.dataspace.example";
    private static final String SUBJECT = "did:web:consumer.org";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final int MATCHING_SUBSCRIPTIONS = 3;

    private final int serverPort = freePort();
    private final int managementPort = freePort();
    private final HttpClient client = HttpClient.create(url(managementPort));

    @Inject
    private EventDelivery eventDelivery;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(serverPort));
        properties.put("endpoints.all.port", String.valueOf(managementPort));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("dataspace.events.publisher.enabled", "true");
        properties.put("dataspace.events.publisher.origin", "cm.provider.org");
        properties.put("dataspace.events.listener.verifier.audience", "contract-management");
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks");
        properties.put("dataspace.events.own-participant-id", OWN_ID);
        properties.put("dataspace.events.dataspace-id", DATASPACE_ID);
        return properties;
    }

    @MockBean(EventDelivery.class)
    EventDelivery eventDelivery() {
        return mock(EventDelivery.class);
    }

    @MockBean(Clock.class)
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @BeforeEach
    void resetDelivery() {
        reset(eventDelivery);
        when(eventDelivery.publish(any())).thenReturn(MATCHING_SUBSCRIPTIONS);
    }

    @AfterAll
    void closeClient() {
        client.close();
    }

    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of("object", new Payload(Map.of("orderId", "urn:ngsi-ld:order:1", "count", 2))),
                Arguments.of("nested", new Payload(Map.of("order", Map.of("items", List.of(Map.of("id", "a")))))),
                Arguments.of("array", new Payload(List.of("a", "b"))),
                Arguments.of("string", new Payload("plain")),
                Arguments.of("number", new Payload(42)),
                Arguments.of("none", new Payload(null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloads")
    void fillsTheEnvelope(String description, Payload payload) {
        Map<String, Object> body = new HashMap<>(Map.of("type", TYPE, "subject", SUBJECT));
        if (payload.data() != null) {
            body.put("data", payload.data());
        }

        HttpResponse<PublishEndpoint.PublishedEvent> response = post(body);

        DataSpaceEventVO event = published();
        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        assertEquals(DataSpaceEventVO.Specversion._1_0, event.getSpecversion());
        assertDoesNotThrow(() -> UUID.fromString(event.getId()), "A missing id is generated.");
        assertEquals(OWN_ID, event.getSource());
        assertEquals(TYPE, event.getType());
        assertEquals(SUBJECT, event.getSubject());
        assertEquals(NOW, event.getTime());
        assertEquals(DATASPACE_ID, event.getDataspaceid());
        assertEquals(PublishEndpoint.CONTENT_TYPE, event.getDatacontenttype());
        assertEquals(payload.data(), event.getData());
        assertEquals(new PublishEndpoint.PublishedEvent(event.getId(), MATCHING_SUBSCRIPTIONS), response.body());
    }

    @Test
    void keepsAnExplicitId() {
        String id = "urn:event:4711";

        HttpResponse<PublishEndpoint.PublishedEvent> response = post(Map.of("type", TYPE, "id", id));

        assertEquals(id, published().getId());
        assertEquals(id, response.body().id());
    }

    static Stream<Arguments> missingTypes() {
        return Stream.of(
                Arguments.of("no type", Map.of("subject", SUBJECT)),
                Arguments.of("blank type", Map.of("type", " ", "subject", SUBJECT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingTypes")
    void rejectsAMissingType(String description, Map<String, Object> body) {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> post(body));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(eventDelivery, never()).publish(any());
    }

    // a bare Object parameter would be resolved as a bean by the Micronaut extension
    record Payload(Object data) {
    }

    private HttpResponse<PublishEndpoint.PublishedEvent> post(Map<String, Object> body) {
        return client.toBlocking().exchange(HttpRequest.POST(EVENTS_PATH, body), PublishEndpoint.PublishedEvent.class);
    }

    private DataSpaceEventVO published() {
        ArgumentCaptor<DataSpaceEventVO> captor = ArgumentCaptor.forClass(DataSpaceEventVO.class);
        verify(eventDelivery).publish(captor.capture());
        return captor.getValue();
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
