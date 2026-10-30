package org.fiware.iam.dataspace;

import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.dataspace.PublisherSubscriptionClient.Registration;
import org.fiware.iam.events.model.HttpProtocolSettingsVO;
import org.fiware.iam.events.model.SubscriptionRequestVO;
import org.fiware.iam.events.model.SubscriptionVO;
import org.fiware.iam.exception.EventSubscriptionException;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.http.AuthHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.fiware.iam.http.Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE;
import static org.fiware.iam.http.Oid4VpAuthHandler.SCOPE_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Checks the requests sent to a publisher's subscriptions API and the mapping of its answers.
 */
class PublisherSubscriptionClientTest {

    private static final String PUBLISHER_ID = "did:web:provider.org";
    private static final String SUBSCRIPTIONS_URL = "https://cm.provider.org/subscriptions";
    private static final String OWN_ID = "did:web:consumer.org";
    private static final String SINK = "https://cm.consumer.org/events";
    private static final String TYPE = "org.fiware.dataspace.offering.created";
    private static final String CLIENT_ID = "subscriber-client";
    private static final Set<String> SCOPES = Set.of("subscriptions");
    private static final String PROTOCOL_CLIENT_ID = "contract-management";
    private static final List<String> PROTOCOL_SCOPES = List.of("events");
    private static final String SUBSCRIPTION_ID = "9b2e1f6a-0c41-4d55-8a3e-7d2f5c1b0e88";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-30T13:00:00Z");
    private static final SubscriptionTarget TARGET = new SubscriptionTarget(PUBLISHER_ID, SUBSCRIPTIONS_URL, List.of(TYPE),
            "cm.provider.org", CLIENT_ID, SCOPES, OWN_ID, SINK, PROTOCOL_CLIENT_ID, PROTOCOL_SCOPES);

    // mirrors Oid4VpAuthHandler: the first answer is returned, not thrown
    private static final AuthHandler RETURNING_HANDLER = (request, executor) -> executor.apply(request)
            .onErrorResume(HttpClientResponseException.class, e -> Mono.just(e.getResponse()));

    private HttpClient httpClient;
    private HttpRequest<?> sent;
    private Function<HttpRequest<?>, Mono<HttpResponse<?>>> answer;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        doAnswer(invocation -> {
            sent = (HttpRequest<?>) invocation.getArguments()[0];
            return answer.apply(sent);
        }).when(httpClient).exchange(any(HttpRequest.class), any(Class.class));
    }

    /**
     * How a non-2xx answer reaches the client: returned by the auth handler, or thrown without one.
     */
    enum Shape {
        RETURNED, THROWN;

        Optional<AuthHandler> handler() {
            return this == RETURNED ? Optional.of(RETURNING_HANDLER) : Optional.empty();
        }
    }

    @Test
    void sendsTheTargetAsSubscriptionRequest() {
        answer = request -> Mono.just(HttpResponse.created(subscription()));

        client(Shape.RETURNED).subscribe(TARGET).block();

        assertEquals(HttpMethod.POST, sent.getMethod());
        assertEquals(URI.create(SUBSCRIPTIONS_URL), sent.getUri());
        assertEquals(Optional.of(CLIENT_ID), sent.getAttribute(CLIENT_ID_ATTRIBUTE, String.class));
        assertEquals(Optional.of(SCOPES), sent.getAttribute(SCOPE_ATTRIBUTE, Set.class));
        assertEquals(new SubscriptionRequestVO()
                .subscriberId(OWN_ID)
                .sink(URI.create(SINK))
                .types(List.of(TYPE))
                .protocol(SubscriptionRequestVO.Protocol.HTTP)
                .protocolsettings(new HttpProtocolSettingsVO().clientId(PROTOCOL_CLIENT_ID).scopes(PROTOCOL_SCOPES)),
                sent.getBody(SubscriptionRequestVO.class).orElseThrow());
    }

    static Stream<Arguments> shapes() {
        return Stream.of(Shape.values()).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void mapsACreatedSubscriptionToARegistration(Shape shape) {
        answer = request -> Mono.just(HttpResponse.created(subscription()));

        assertEquals(new Registration(SUBSCRIPTION_ID, EXPIRES_AT), client(shape).subscribe(TARGET).block());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void mapsAConflictToAnEmptyRegistration(Shape shape) {
        answer = failing(HttpStatus.CONFLICT.getCode());

        assertEquals(new Registration(null, null), client(shape).subscribe(TARGET).block());
    }

    static Stream<Arguments> failedRegistrations() {
        return Stream.of(Shape.values()).flatMap(shape -> Stream.of(
                        HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                        HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)
                .map(status -> Arguments.of(shape, status.getCode())));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("failedRegistrations")
    void failsWithTheStatusOfARefusedRegistration(Shape shape, int status) {
        answer = failing(status);

        EventSubscriptionException e = assertThrows(EventSubscriptionException.class,
                () -> client(shape).subscribe(TARGET).block());

        assertEquals(status, e.getStatus());
        assertEquals(FailureReason.EVENT_SUBSCRIPTION_FAILED, e.getReason());
        assertEquals(PUBLISHER_ID, e.getPublisherId());
        assertEquals(SUBSCRIPTIONS_URL, e.getSubscriptionsUrl());
        assertTrue(e.getCause() instanceof HttpClientResponseException, "The downstream answer is kept as cause.");
    }

    @Test
    void failsOnASuccessWithoutSubscription() {
        answer = request -> Mono.just(HttpResponse.ok());

        EventSubscriptionException e = assertThrows(EventSubscriptionException.class,
                () -> client(Shape.THROWN).subscribe(TARGET).block());

        assertEquals(HttpStatus.OK.getCode(), e.getStatus());
        assertNull(e.getCause());
    }

    static Stream<Arguments> errorsWithoutStatus() {
        return Stream.of(
                Arguments.of("bad gateway", Optional.<AuthHandler>of((request, executor) ->
                                Mono.error(new BadGatewayException("Could not get an OID4VP access token."))),
                        BadGatewayException.class),
                Arguments.of("connection error", Optional.<AuthHandler>empty(), HttpClientException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorsWithoutStatus")
    void failsWithoutStatusOnOtherErrors(String description, Optional<AuthHandler> handler,
                                         Class<? extends Throwable> causeType) {
        answer = request -> Mono.error(new HttpClientException("Connection refused"));

        EventSubscriptionException e = assertThrows(EventSubscriptionException.class,
                () -> new PublisherSubscriptionClient(httpClient, handler).subscribe(TARGET).block());

        assertNull(e.getStatus());
        assertTrue(causeType.isInstance(e.getCause()), String.valueOf(e.getCause()));
    }

    @Test
    void deletesTheSubscriptionWithTheTargetsCredentials() {
        answer = request -> Mono.just(HttpResponse.noContent());

        client(Shape.RETURNED).unsubscribe(TARGET, SUBSCRIPTION_ID).block();

        assertEquals(HttpMethod.DELETE, sent.getMethod());
        assertEquals(URI.create(SUBSCRIPTIONS_URL + "/" + SUBSCRIPTION_ID), sent.getUri());
        assertEquals(Optional.of(CLIENT_ID), sent.getAttribute(CLIENT_ID_ATTRIBUTE, String.class));
        assertEquals(Optional.of(SCOPES), sent.getAttribute(SCOPE_ATTRIBUTE, Set.class));
    }

    static Stream<Arguments> removals() {
        return Stream.of(Shape.values()).flatMap(shape -> Stream.of(
                Arguments.of(shape, HttpStatus.NO_CONTENT.getCode(), null),
                Arguments.of(shape, HttpStatus.NOT_FOUND.getCode(), null),
                Arguments.of(shape, HttpStatus.INTERNAL_SERVER_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR.getCode())));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("removals")
    void unsubscribeTreatsAMissingSubscriptionAsRemoved(Shape shape, int status, Integer expectedFailure) {
        answer = DownstreamStatus.isSuccess(status)
                ? request -> Mono.just(HttpResponse.status(HttpStatus.valueOf(status)))
                : failing(status);
        Mono<Void> removal = client(shape).unsubscribe(TARGET, SUBSCRIPTION_ID);

        if (expectedFailure == null) {
            assertNull(removal.block());
        } else {
            EventSubscriptionException e = assertThrows(EventSubscriptionException.class, removal::block);
            assertEquals(expectedFailure, e.getStatus());
            assertEquals(SUBSCRIPTIONS_URL + "/" + SUBSCRIPTION_ID, e.getSubscriptionsUrl());
        }
    }

    private PublisherSubscriptionClient client(Shape shape) {
        return new PublisherSubscriptionClient(httpClient, shape.handler());
    }

    private static Function<HttpRequest<?>, Mono<HttpResponse<?>>> failing(int status) {
        return request -> Mono.error(new HttpClientResponseException("answered " + status,
                HttpResponse.status(HttpStatus.valueOf(status))));
    }

    private static SubscriptionVO subscription() {
        return new SubscriptionVO()
                .id(SUBSCRIPTION_ID)
                .subscriberId(OWN_ID)
                .sink(URI.create(SINK))
                .types(List.of(TYPE))
                .protocol(SubscriptionVO.Protocol.HTTP)
                .protocolsettings(new HttpProtocolSettingsVO().clientId(PROTOCOL_CLIENT_ID).scopes(PROTOCOL_SCOPES))
                .expiresAt(EXPIRES_AT);
    }
}
