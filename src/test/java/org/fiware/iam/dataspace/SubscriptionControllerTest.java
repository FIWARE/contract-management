package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.HttpProtocolSettingsVO;
import org.fiware.iam.events.model.SubscriptionRequestVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.VerifierUnavailableException;
import org.fiware.iam.exception.WebhookHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionControllerTest {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Instant START = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration LEASE_TTL = Duration.ofHours(1);
    private static final Duration BEFORE_RENEWAL = Duration.ofMinutes(10);
    private static final String ORIGIN = "cm.provider.org";
    private static final String SUBSCRIBER = "did:web:consumer.org";
    private static final String OTHER_SUBSCRIBER = "did:web:other.org";
    private static final URI SINK = URI.create("https://events.consumer.org/events");
    private static final URI OTHER_SINK = URI.create("https://events.consumer.org/other-events");
    private static final URI INSECURE_SINK = URI.create("http://events.consumer.org/events");
    private static final String TYPE = "org.fiware.dataspace.offering.created";
    private static final String OTHER_TYPE = "org.fiware.dataspace.offering.deleted";
    private static final String CLIENT_ID = "contract-management";
    private static final String SCOPE = "events";
    private static final int RATE = 120;
    private static final String INTERNAL_HOST = "10.0.0.7:6379";
    private static final String UNKNOWN_ID = "unknown";

    private DataSpaceEventsProperties properties;
    private BearerVerifier bearerVerifier;
    private WebhookHandshake handshake;
    private Clock clock;
    private SubscriptionRegistry registry;
    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        properties.getPublisher().setEnabled(true);
        properties.getPublisher().setOrigin(ORIGIN);
        properties.getPublisher().setLeaseTtl(LEASE_TTL);
        bearerVerifier = mock(BearerVerifier.class);
        // the stand-in token is the participant id itself
        when(bearerVerifier.participantIdOf(any())).thenAnswer(invocation -> Optional.ofNullable((String) invocation.getArguments()[0])
                .map(authorization -> authorization.substring(BEARER_PREFIX.length())));
        handshake = mock(WebhookHandshake.class);
        when(handshake.validate(any())).thenReturn(Mono.just(Optional.of(RATE)));
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        registry = new SubscriptionRegistry(properties, clock);
        controller = new SubscriptionController(bearerVerifier, handshake, registry, properties);
    }

    @Test
    void activatesAValidatedSubscription() {
        HttpResponse<SubscriptionVO> response = create(SUBSCRIBER, request());

        assertEquals(HttpStatus.CREATED, response.getStatus());
        SubscriptionVO body = response.body();
        assertNotNull(body);
        assertEquals(SUBSCRIBER, body.getSubscriberId());
        assertEquals(SINK, body.getSink());
        assertEquals(List.of(TYPE), body.getTypes());
        assertEquals(SubscriptionVO.Protocol.HTTP, body.getProtocol());
        assertEquals(CLIENT_ID, body.getProtocolsettings().getClientId());
        assertEquals(List.of(SCOPE), body.getProtocolsettings().getScopes());
        assertEquals(START.plus(LEASE_TTL), body.getExpiresAt());
        Subscription stored = registry.get(body.getId()).orElseThrow();
        assertEquals(RATE, stored.allowedRate());
        assertEquals(Set.of(SCOPE), stored.scopes());
        verify(handshake).validate(SINK);
    }

    @Test
    void acceptsAnInsecureSinkWhenAllowed() {
        properties.getPublisher().setAllowInsecureSinks(true);

        assertEquals(HttpStatus.CREATED, create(SUBSCRIBER, request().sink(INSECURE_SINK)).getStatus());
    }

    @Test
    void storesMissingScopesAsNone() {
        HttpResponse<SubscriptionVO> response = create(SUBSCRIBER,
                request().protocolsettings(new HttpProtocolSettingsVO().clientId(CLIENT_ID).scopes(null)));

        assertEquals(HttpStatus.CREATED, response.getStatus());
        assertEquals(Set.of(), registry.get(response.body().getId()).orElseThrow().scopes());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedRequests")
    void refusesBeforeTheHandshake(String description, @Nullable String participant, Consumer<SubscriptionRequestVO> change,
                                   HttpStatus expectedStatus) {
        SubscriptionRequestVO request = request();
        change.accept(request);

        HttpResponse<SubscriptionVO> response = create(participant, request);

        assertEquals(expectedStatus, response.getStatus(), description);
        assertTrue(response.getBody().isEmpty(), description);
        verify(handshake, never()).validate(any());
        assertTrue(registry.active(TYPE).isEmpty(), description);
    }

    static Stream<Arguments> refusedRequests() {
        Consumer<SubscriptionRequestVO> unchanged = request -> {
        };
        return Stream.of(
                Arguments.of("no bearer token", null, unchanged, HttpStatus.UNAUTHORIZED),
                Arguments.of("subscriberId differs from the participant", OTHER_SUBSCRIBER, unchanged, HttpStatus.FORBIDDEN),
                Arguments.of("missing subscriberId", SUBSCRIBER, change(request -> request.subscriberId(null)), HttpStatus.FORBIDDEN),
                Arguments.of("insecure sink", SUBSCRIBER, change(request -> request.sink(INSECURE_SINK)), HttpStatus.BAD_REQUEST),
                Arguments.of("relative sink", SUBSCRIBER, change(request -> request.sink(URI.create("/events"))), HttpStatus.BAD_REQUEST),
                Arguments.of("opaque sink", SUBSCRIBER, change(request -> request.sink(URI.create("https:events"))), HttpStatus.BAD_REQUEST),
                Arguments.of("other scheme", SUBSCRIBER, change(request -> request.sink(URI.create("ftp://events.consumer.org/"))),
                        HttpStatus.BAD_REQUEST),
                Arguments.of("missing sink", SUBSCRIBER, change(request -> request.sink(null)), HttpStatus.BAD_REQUEST),
                Arguments.of("empty types", SUBSCRIBER, change(request -> request.types(new ArrayList<>())), HttpStatus.BAD_REQUEST),
                Arguments.of("missing types", SUBSCRIBER, change(request -> request.types(null)), HttpStatus.BAD_REQUEST),
                Arguments.of("blank type", SUBSCRIBER, change(request -> request.types(List.of(" "))), HttpStatus.BAD_REQUEST),
                Arguments.of("missing protocol", SUBSCRIBER, change(request -> request.protocol(null)), HttpStatus.BAD_REQUEST),
                Arguments.of("missing protocolsettings", SUBSCRIBER, change(request -> request.protocolsettings(null)),
                        HttpStatus.BAD_REQUEST),
                Arguments.of("missing clientId", SUBSCRIBER,
                        change(request -> request.protocolsettings(new HttpProtocolSettingsVO().scopes(List.of(SCOPE)))),
                        HttpStatus.BAD_REQUEST));
    }

    @Test
    void refusesWithoutAConfiguredOrigin() {
        properties.getPublisher().setOrigin(null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, create(SUBSCRIBER, request()).getStatus());
        verify(handshake, never()).validate(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handshakeFailures")
    void answersAFailedHandshakeGenerically(String description, WebhookHandshakeException failure) {
        when(handshake.validate(any())).thenReturn(Mono.error(failure));

        HttpResponse<SubscriptionVO> response = create(SUBSCRIBER, request());

        assertGenericHandshakeFailure(response, description);
        assertTrue(registry.active(TYPE).isEmpty(), description);
    }

    static Stream<Arguments> handshakeFailures() {
        return Stream.of(
                Arguments.of("sink answers an error", new WebhookHandshakeException(SINK, "answered 500", null)),
                Arguments.of("sink does not allow the origin",
                        new WebhookHandshakeException(SINK, "did not allow origin %s, allowed: other.org".formatted(ORIGIN), null)),
                Arguments.of("sink is unreachable", new WebhookHandshakeException(SINK, "request failed",
                        new ConnectException("Connection refused: " + INTERNAL_HOST))));
    }

    @Test
    void renewsInPlace() {
        String id = create(SUBSCRIBER, request()).body().getId();
        when(clock.instant()).thenReturn(START.plus(BEFORE_RENEWAL));

        HttpResponse<SubscriptionVO> renewed = create(SUBSCRIBER, request().types(List.of(OTHER_TYPE)));

        assertEquals(HttpStatus.CREATED, renewed.getStatus());
        assertEquals(id, renewed.body().getId());
        assertEquals(START.plus(BEFORE_RENEWAL).plus(LEASE_TTL), renewed.body().getExpiresAt());
        assertEquals(List.of(OTHER_TYPE), registry.get(id).orElseThrow().types());
    }

    @Test
    void keepsTheSubscriptionWhenTheRenewalHandshakeFails() {
        String id = create(SUBSCRIBER, request()).body().getId();
        Subscription before = registry.get(id).orElseThrow();
        when(clock.instant()).thenReturn(START.plus(BEFORE_RENEWAL));
        when(handshake.validate(any())).thenReturn(Mono.error(new WebhookHandshakeException(SINK, "answered 503", null)));

        HttpResponse<SubscriptionVO> response = create(SUBSCRIBER, request().types(List.of(OTHER_TYPE)));

        assertGenericHandshakeFailure(response, "failed renewal");
        Subscription after = registry.get(id).orElseThrow();
        assertEquals(before, after);
        assertEquals(START.plus(LEASE_TTL), after.expiresAt());
        assertEquals(List.of(TYPE), after.types());
    }

    @Test
    void refusesSubscriptionsBeyondTheCap() {
        properties.getPublisher().setMaxSubscriptionsPerSubscriber(1);
        assertEquals(HttpStatus.CREATED, create(SUBSCRIBER, request()).getStatus());

        assertEquals(HttpStatus.CONFLICT, create(SUBSCRIBER, request().sink(OTHER_SINK)).getStatus());
        assertEquals(1, registry.active(TYPE).size());
    }

    @Test
    void asksToRetryWhenTheVerifierIsDownOnCreate() {
        verifierDown();

        assertRetryLater(create(SUBSCRIBER, request()));
        verify(handshake, never()).validate(any());
    }

    @Test
    void asksToRetryWhenTheVerifierIsDownOnDelete() {
        String id = create(SUBSCRIBER, request()).body().getId();
        verifierDown();

        assertRetryLater(controller.delete(BEARER_PREFIX + SUBSCRIBER, id).block());
        assertTrue(registry.get(id).isPresent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deletions")
    void deletesOnlyOwnSubscriptions(String description, @Nullable String participant, boolean unknownId, HttpStatus expectedStatus,
                                     boolean expectedRemoved) {
        String id = create(SUBSCRIBER, request()).body().getId();

        HttpResponse<Object> response = controller.delete(authorization(participant), unknownId ? UNKNOWN_ID : id).block();

        assertNotNull(response);
        assertEquals(expectedStatus, response.getStatus(), description);
        assertEquals(expectedRemoved, registry.get(id).isEmpty(), description);
    }

    static Stream<Arguments> deletions() {
        return Stream.of(
                Arguments.of("owner", SUBSCRIBER, false, HttpStatus.NO_CONTENT, true),
                Arguments.of("other participant", OTHER_SUBSCRIBER, false, HttpStatus.NOT_FOUND, false),
                Arguments.of("unknown id", SUBSCRIBER, true, HttpStatus.NOT_FOUND, false),
                Arguments.of("no bearer token", null, false, HttpStatus.UNAUTHORIZED, false));
    }

    private static void assertGenericHandshakeFailure(HttpResponse<SubscriptionVO> response, String description) {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus(), description);
        assertEquals(SubscriptionController.HANDSHAKE_FAILED, response.reason(), description);
        assertTrue(response.getBody().isEmpty(), description);
        assertFalse(response.reason().contains(INTERNAL_HOST), description);
    }

    private static void assertRetryLater(@Nullable HttpResponse<?> response) {
        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
        assertEquals(String.valueOf(EventListener.RETRY_AFTER.toSeconds()), response.getHeaders().get(HttpHeaders.RETRY_AFTER));
    }

    private void verifierDown() {
        doThrow(new VerifierUnavailableException("JWKS unreachable", new ConnectException("refused")))
                .when(bearerVerifier).participantIdOf(anyString());
    }

    private HttpResponse<SubscriptionVO> create(@Nullable String participant, SubscriptionRequestVO request) {
        HttpResponse<SubscriptionVO> response = controller.create(authorization(participant), request).block();
        assertNotNull(response);
        return response;
    }

    @Nullable
    private static String authorization(@Nullable String participant) {
        return participant == null ? null : BEARER_PREFIX + participant;
    }

    private static Consumer<SubscriptionRequestVO> change(Consumer<SubscriptionRequestVO> change) {
        return change;
    }

    private static SubscriptionRequestVO request() {
        return new SubscriptionRequestVO()
                .subscriberId(SUBSCRIBER)
                .sink(SINK)
                .types(new ArrayList<>(List.of(TYPE)))
                .protocol(SubscriptionRequestVO.Protocol.HTTP)
                .protocolsettings(new HttpProtocolSettingsVO().clientId(CLIENT_ID).scopes(new ArrayList<>(List.of(SCOPE))));
    }
}
