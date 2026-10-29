package org.fiware.iam.dataspace;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.fiware.iam.dataspace.TestEventFixtures.KNOWN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventListenerTest implements TestPropertyProvider {

    private static final String EVENTS_PATH = "/events";
    private static final String CLOUDEVENTS_JSON = "application/cloudevents+json";
    private static final String SPEC_VERSION = "1.0";
    private static final String AUDIENCE = "contract-management";
    private static final String PUBLISHER = "did:web:provider.org";
    private static final String UNCONFIGURED_PUBLISHER = "did:web:stranger.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String OTHER_SUBJECT = "did:web:someone.org";
    private static final String DATASPACE = "test-dataspace";
    private static final String OTHER_DATASPACE = "other-dataspace";
    private static final String PUBLISHER_ORIGIN = "events.provider.org";
    private static final String UNCONFIGURED_ORIGIN = "events.stranger.org";
    private static final String REQUEST_ORIGIN_HEADER = "WebHook-Request-Origin";
    private static final String UNKNOWN_TYPE = "org.fiware.dataspace.unknown";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final int RANDOM_TYPES = 5;

    private final int port = freePort();

    @Inject
    @Client("/")
    private HttpClient client;

    @Inject
    private JsonMapper jsonMapper;

    @Inject
    private MeterRegistry meterRegistry;

    @Inject
    private TestEventFixtures.CountingHandler handler;

    @Inject
    private EventListener listener;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", String.valueOf(port));
        properties.put("micronaut.metrics.enabled", "true");
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put(TestEventFixtures.ENABLED_PROPERTY, "true");
        properties.put("dataspace.events.listener.enabled", "true");
        properties.put("dataspace.events.listener.verifier.audience", AUDIENCE);
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://localhost:" + port + TestEventFixtures.JWKS_PATH);
        properties.put("dataspace.events.own-participant-id", OWN_ID);
        properties.put("dataspace.events.dataspace-id", DATASPACE);
        properties.put("dataspace.events.publishers[0].id", PUBLISHER);
        properties.put("dataspace.events.publishers[0].types[0]", KNOWN_TYPE);
        properties.put("dataspace.events.publishers[0].origin", PUBLISHER_ORIGIN);
        return properties;
    }

    @BeforeEach
    void resetHandler() {
        handler.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisions")
    void answersAndCountsEachDecision(String description, @Nullable String participant, Event event, HttpStatus expectedStatus,
                                      int expectedHandled, String expectedMetricType, String expectedOutcome) {
        double before = count(expectedMetricType, expectedOutcome);

        assertEquals(expectedStatus, post(participant, event).getStatus(), description);

        assertEquals(expectedHandled, handler.handled(), description);
        assertEquals(before + 1, count(expectedMetricType, expectedOutcome), description);
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of("configured publisher is handled", PUBLISHER, Event.of(PUBLISHER, KNOWN_TYPE),
                        HttpStatus.ACCEPTED, 1, KNOWN_TYPE, "accepted"),
                Arguments.of("event about another participant is handled", PUBLISHER,
                        Event.of(PUBLISHER, KNOWN_TYPE).withSubject(OTHER_SUBJECT), HttpStatus.ACCEPTED, 1, KNOWN_TYPE, "accepted"),
                Arguments.of("unknown type is accepted but not handled", PUBLISHER, Event.of(PUBLISHER, UNKNOWN_TYPE),
                        HttpStatus.ACCEPTED, 0, EventListener.OTHER_TYPE, "unknown_type"),
                Arguments.of("known type from an unconfigured publisher", UNCONFIGURED_PUBLISHER,
                        Event.of(UNCONFIGURED_PUBLISHER, KNOWN_TYPE), HttpStatus.FORBIDDEN, 0, KNOWN_TYPE, "publisher_not_authorized"),
                Arguments.of("source differs from the participant", PUBLISHER, Event.of(UNCONFIGURED_PUBLISHER, KNOWN_TYPE),
                        HttpStatus.FORBIDDEN, 0, KNOWN_TYPE, "source_mismatch"),
                Arguments.of("other data space", PUBLISHER, Event.of(PUBLISHER, KNOWN_TYPE).withDataspace(OTHER_DATASPACE),
                        HttpStatus.FORBIDDEN, 0, KNOWN_TYPE, "dataspace_mismatch"),
                Arguments.of("same data space is handled", PUBLISHER, Event.of(PUBLISHER, KNOWN_TYPE).withDataspace(DATASPACE),
                        HttpStatus.ACCEPTED, 1, KNOWN_TYPE, "accepted"),
                Arguments.of("no bearer", null, Event.of(PUBLISHER, KNOWN_TYPE),
                        HttpStatus.UNAUTHORIZED, 0, KNOWN_TYPE, "unauthenticated"),
                Arguments.of("event about this participant is not handled", PUBLISHER,
                        Event.of(PUBLISHER, KNOWN_TYPE).withSubject(OWN_ID), HttpStatus.ACCEPTED, 0, KNOWN_TYPE, "self"));
    }

    @Test
    void replayIsHandledOnce() {
        Event event = Event.of(PUBLISHER, KNOWN_TYPE);
        double duplicates = count(KNOWN_TYPE, EventListener.DUPLICATE);

        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, event).getStatus());
        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, event).getStatus());

        assertEquals(1, handler.handled());
        assertEquals(duplicates + 1, count(KNOWN_TYPE, EventListener.DUPLICATE));
    }

    @Test
    void retryWhileInFlightIsAskedToComeBack() {
        Event event = Event.of(PUBLISHER, KNOWN_TYPE);
        CompletableFuture<Void> gate = handler.hold();
        CompletableFuture<HttpResponse<?>> first = CompletableFuture.supplyAsync(() -> post(PUBLISHER, event));
        await().atMost(AWAIT).until(() -> handler.started() == 1);

        HttpResponse<?> retry = post(PUBLISHER, event);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, retry.getStatus());
        assertEquals(String.valueOf(EventListener.RETRY_AFTER.toSeconds()), retry.getHeaders().get(HttpHeaders.RETRY_AFTER));
        gate.complete(null);
        assertEquals(HttpStatus.ACCEPTED, first.join().getStatus());
        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, event).getStatus());
        assertEquals(1, handler.handled());
    }

    @Test
    void randomTypesShareOneMetricTag() {
        double unknown = count(EventListener.OTHER_TYPE, "unknown_type");
        double mismatched = count(EventListener.OTHER_TYPE, "source_mismatch");

        for (int i = 0; i < RANDOM_TYPES; i++) {
            String type = "random." + UUID.randomUUID();
            assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, Event.of(PUBLISHER, type)).getStatus());
            assertEquals(HttpStatus.FORBIDDEN, post(PUBLISHER, Event.of(UNCONFIGURED_PUBLISHER, type)).getStatus());
            assertNull(meterRegistry.find(EventListener.METRIC_NAME).tag(EventListener.TYPE_TAG, type).counter());
        }

        assertEquals(unknown + RANDOM_TYPES, count(EventListener.OTHER_TYPE, "unknown_type"));
        assertEquals(mismatched + RANDOM_TYPES, count(EventListener.OTHER_TYPE, "source_mismatch"));
    }

    @Test
    void failedEventIsHandledAgainOnRetry() {
        Event event = Event.of(PUBLISHER, KNOWN_TYPE);
        double failures = count(KNOWN_TYPE, EventListener.HANDLER_FAILED);
        handler.failNext();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, post(PUBLISHER, event).getStatus());
        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, event).getStatus());

        assertEquals(2, handler.started());
        assertEquals(1, handler.handled());
        assertEquals(failures + 1, count(KNOWN_TYPE, EventListener.HANDLER_FAILED));
    }

    @Test
    void cancelledEventIsHandledOnRetry() {
        Event event = Event.of(PUBLISHER, KNOWN_TYPE);
        handler.hold();
        Disposable request = listener.receive("Bearer " + token(PUBLISHER), event.toVO(jsonMapper)).subscribe();
        await().atMost(AWAIT).until(() -> handler.started() == 1);

        request.dispose();
        handler.reset();

        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER, event).getStatus());
        assertEquals(1, handler.handled());
    }

    @Test
    void plainJsonIsUnsupported() {
        MutableHttpRequest<String> request = request(PUBLISHER, Event.of(PUBLISHER, KNOWN_TYPE))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exchange(request).getStatus());
        assertEquals(0, handler.started());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("webhookOrigins")
    void validatesWebhookOrigins(String description, @Nullable String requestOrigin, @Nullable String expectedAllowedOrigin) {
        MutableHttpRequest<?> request = HttpRequest.OPTIONS(EVENTS_PATH);
        if (requestOrigin != null) {
            request.header(REQUEST_ORIGIN_HEADER, requestOrigin);
        }

        HttpResponse<?> response = exchange(request);

        assertEquals(HttpStatus.OK, response.getStatus(), description);
        assertEquals("POST", response.getHeaders().get(HttpHeaders.ALLOW), description);
        assertEquals(expectedAllowedOrigin, response.getHeaders().get(EventListener.WEBHOOK_ALLOWED_ORIGIN), description);
    }

    static Stream<Arguments> webhookOrigins() {
        return Stream.of(
                Arguments.of("configured origin is granted", PUBLISHER_ORIGIN, PUBLISHER_ORIGIN),
                Arguments.of("case variant is granted with the configured spelling", PUBLISHER_ORIGIN.toUpperCase(), PUBLISHER_ORIGIN),
                Arguments.of("unconfigured origin is not granted", UNCONFIGURED_ORIGIN, null),
                Arguments.of("publisher id is not an origin", PUBLISHER, null),
                Arguments.of("missing origin is not granted", null, null));
    }

    private HttpResponse<?> post(@Nullable String participant, Event event) {
        return exchange(request(participant, event));
    }

    private MutableHttpRequest<String> request(@Nullable String participant, Event event) {
        MutableHttpRequest<String> request = HttpRequest.POST(EVENTS_PATH, event.toJson(jsonMapper)).contentType(CLOUDEVENTS_JSON);
        return participant == null ? request : request.bearerAuth(token(participant));
    }

    private HttpResponse<?> exchange(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request);
        } catch (HttpClientResponseException e) {
            return e.getResponse();
        }
    }

    private double count(String type, String outcome) {
        Counter counter = meterRegistry.find(EventListener.METRIC_NAME)
                .tags(EventListener.TYPE_TAG, type, EventListener.OUTCOME_TAG, outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static String token(String participant) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                .claim(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM, participant)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(TestEventFixtures.KID).build(), claims);
        try {
            jwt.sign(new ECDSASigner(TestEventFixtures.SIGNING_KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Event(String id, String source, String type, @Nullable String subject, @Nullable String dataspace) {

        static Event of(String source, String type) {
            return new Event(UUID.randomUUID().toString(), source, type, null, null);
        }

        Event withSubject(String newSubject) {
            return new Event(id, source, type, newSubject, dataspace);
        }

        Event withDataspace(String newDataspace) {
            return new Event(id, source, type, subject, newDataspace);
        }

        Map<String, Object> toMap() {
            Map<String, Object> json = new HashMap<>(Map.of("specversion", SPEC_VERSION, "id", id, "source", source, "type", type));
            if (subject != null) {
                json.put("subject", subject);
            }
            if (dataspace != null) {
                json.put("dataspaceid", dataspace);
            }
            return json;
        }

        String toJson(JsonMapper mapper) {
            try {
                return mapper.writeValueAsString(toMap());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        DataSpaceEventVO toVO(JsonMapper mapper) {
            try {
                return mapper.readValue(toJson(mapper), DataSpaceEventVO.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
