package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.events.model.SubscriptionErrorVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.WebhookHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Checks the subscriptions API end to end over HTTP, with real bearer verification and a mocked webhook handshake.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionControllerHttpTest implements TestPropertyProvider {

    private static final String SUBSCRIPTIONS_PATH = "/subscriptions";
    private static final String AUDIENCE = "contract-management";
    private static final String SUBSCRIBER = "did:web:consumer.org";
    private static final String OTHER_PARTICIPANT = "did:web:someone.org";
    private static final String CLIENT_ID = "consumer-events";
    private static final String SINK = "https://events.consumer.org/hook";
    private static final String INTERNAL_SINK = "https://10.0.0.1:6443/hook";

    private final int port = freePort();

    @Inject
    @Client("/")
    private HttpClient client;

    @Inject
    private WebhookHandshake handshake;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(port));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put(TestEventFixtures.ENABLED_PROPERTY, "true");
        properties.put("dataspace.events.publisher.enabled", "true");
        properties.put("dataspace.events.publisher.origin", "cm.provider.org");
        properties.put("dataspace.events.listener.verifier.audience", AUDIENCE);
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://localhost:" + port + TestEventFixtures.JWKS_PATH);
        properties.put("dataspace.events.own-participant-id", "did:web:provider.org");
        return properties;
    }

    @MockBean(WebhookHandshake.class)
    WebhookHandshake handshake() {
        return mock(WebhookHandshake.class);
    }

    @BeforeEach
    void resetHandshake() {
        reset(handshake);
        when(handshake.validate(any())).thenReturn(Mono.just(Optional.empty()));
    }

    @Test
    void createsAndDeletesASubscription() {
        HttpResponse<SubscriptionVO> created = exchange(post(SUBSCRIBER, SINK), SubscriptionVO.class);

        assertEquals(HttpStatus.CREATED, created.getStatus());
        SubscriptionVO subscription = created.getBody().orElseThrow();
        assertEquals(SUBSCRIBER, subscription.getSubscriberId());
        assertEquals(URI.create(SINK), subscription.getSink());
        assertNotNull(subscription.getExpiresAt());

        HttpRequest<?> foreignDelete = HttpRequest.DELETE(SUBSCRIPTIONS_PATH + "/" + subscription.getId())
                .bearerAuth(TestEventFixtures.token(OTHER_PARTICIPANT, AUDIENCE));
        assertEquals(HttpStatus.NOT_FOUND, exchange(foreignDelete, Object.class).getStatus());

        HttpRequest<?> delete = HttpRequest.DELETE(SUBSCRIPTIONS_PATH + "/" + subscription.getId())
                .bearerAuth(TestEventFixtures.token(SUBSCRIBER, AUDIENCE));
        assertEquals(HttpStatus.NO_CONTENT, exchange(delete, Object.class).getStatus());
    }

    @Test
    void refusesAnotherSubscriberId() {
        assertEquals(HttpStatus.FORBIDDEN, exchange(post(OTHER_PARTICIPANT, SINK), Object.class).getStatus());
    }

    @Test
    void refusesWithoutBearer() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchange(post(null, SINK), Object.class).getStatus());
    }

    @Test
    void doesNotDiscloseWhyTheHandshakeFailed() {
        URI sink = URI.create(INTERNAL_SINK);
        when(handshake.validate(sink)).thenReturn(Mono.error(
                new WebhookHandshakeException(sink, "connection failed", new ConnectException("Connection refused"))));

        HttpResponse<SubscriptionErrorVO> response = exchange(post(SUBSCRIBER, INTERNAL_SINK), SubscriptionErrorVO.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertEquals(SubscriptionController.HANDSHAKE_FAILED,
                response.getBody(SubscriptionErrorVO.class).map(SubscriptionErrorVO::getReason).orElse(null),
                "Only the generic reason may be answered, never the connection error.");
    }

    private MutableHttpRequest<Map<String, Object>> post(@Nullable String participant, String sink) {
        Map<String, Object> body = Map.of(
                "subscriberId", SUBSCRIBER,
                "sink", sink,
                "types", List.of(TestEventFixtures.KNOWN_TYPE),
                "protocol", "HTTP",
                "protocolsettings", Map.of("clientId", CLIENT_ID));
        MutableHttpRequest<Map<String, Object>> request = HttpRequest.POST(SUBSCRIPTIONS_PATH, body);
        return participant == null ? request : request.bearerAuth(TestEventFixtures.token(participant, AUDIENCE));
    }

    private <T> HttpResponse<T> exchange(HttpRequest<?> request, Class<T> bodyType) {
        try {
            return client.toBlocking().exchange(request, bodyType);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) e.getResponse();
            return response;
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
