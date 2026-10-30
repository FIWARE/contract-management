package org.fiware.iam.dataspace;

import com.sun.net.httpserver.HttpServer;
import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import org.fiware.iam.http.AuthHandler;
import org.fiware.iam.http.Oid4VpAuthHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventDeliveryTest {

    private static final Instant START = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration LEASE_TTL = Duration.ofHours(1);
    private static final String ORIGIN = "cm.provider.org";
    private static final String SUBSCRIBER = "did:web:subscriber.org";
    private static final String OTHER_SUBSCRIBER = "did:web:other.org";
    private static final URI SINK = URI.create("https://subscriber.org/events");
    private static final URI OTHER_SINK = URI.create("https://other.org/events");
    private static final String TYPE = "org.fiware.dataspace.offering.created";
    private static final String CLIENT_ID = "events-client";
    private static final Set<String> SCOPES = Set.of("events");
    private static final String TOKEN = "access-token";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(50);
    private static final int QUEUE_SIZE = 10;
    private static final int SINGLE_QUEUE_SLOT = 1;
    // 600 requests per minute: one request every 100 ms
    private static final int ALLOWED_RATE = 600;
    private static final Duration PACING_INTERVAL = Duration.ofMinutes(1).dividedBy(ALLOWED_RATE);
    // timer granularity of the scheduler; a request may come a little early compared to the measured send time
    private static final Duration PACING_TOLERANCE = Duration.ofMillis(10);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    // long enough for an asynchronously queued delivery to reach the sink
    private static final Duration QUIET_PERIOD = Duration.ofMillis(300);
    private static final int EVENTS = 3;
    private static final Duration SHORT_ATTEMPT_TIMEOUT = Duration.ofMillis(200);
    private static final String RETURNED = "returned";
    private static final String THROWN = "thrown";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final int NO_CONTENT = 204;
    private static final int NO_BODY = -1;

    private static final int OK = 200;
    private static final int FOUND = 302;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int GONE = 410;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;
    private static final int SERVICE_UNAVAILABLE = 503;

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Map<URI, Deque<Answer>> scripts = new ConcurrentHashMap<>();
    private final Map<URI, Function<HttpRequest<?>, Answer>> fallbacks = new ConcurrentHashMap<>();

    private DataSpaceEventsProperties properties;
    private MutableClock clock;
    private SubscriptionRegistry registry;
    private HttpClient httpClient;
    private OID4VPClient oid4VPClient;
    private SimpleMeterRegistry meterRegistry;
    private EventDelivery delivery;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        properties.getPublisher().setEnabled(true);
        properties.getPublisher().setOrigin(ORIGIN);
        properties.getPublisher().setLeaseTtl(LEASE_TTL);
        DataSpaceEventsProperties.PublisherSettings.Delivery settings = properties.getPublisher().getDelivery();
        settings.setMaxAttempts(MAX_ATTEMPTS);
        settings.setInitialBackoff(INITIAL_BACKOFF);
        settings.setMaxBackoff(MAX_BACKOFF);
        settings.setQueueSize(QUEUE_SIZE);
        clock = new MutableClock(START);
        registry = new SubscriptionRegistry(properties, clock);
        meterRegistry = new SimpleMeterRegistry();
        httpClient = mock(HttpClient.class);
        doAnswer(invocation -> respond((HttpRequest<?>) invocation.getArguments()[0]))
                .when(httpClient).exchange(any(HttpRequest.class), eq(String.class));
        oid4VPClient = mock(OID4VPClient.class);
        when(oid4VPClient.getAccessToken(any())).thenAnswer(invocation -> token());
    }

    @AfterEach
    void tearDown() {
        if (delivery != null) {
            delivery.close();
        }
    }

    @Test
    void fetchesOneTokenOnUnauthorized() {
        withOid4Vp();
        Subscription subscription = subscribe(SUBSCRIBER, SINK, null);
        script(SINK, status(UNAUTHORIZED), status(OK));

        delivery.publish(event());

        awaitCount(EventDelivery.Outcome.DELIVERED, 1);
        verify(oid4VPClient, times(1)).getAccessToken(any());
        assertEquals(List.of(new Sent(SINK, null), new Sent(SINK, BEARER)), withoutTime(sent));
        assertEquals(Optional.of(subscription), registry.get(subscription.id()));
    }

    @Test
    void sendsTheEventAsStructuredCloudEvent() {
        withoutAuthHandler();
        Subscription subscription = subscribe(SUBSCRIBER, SINK, null);
        List<HttpRequest<?>> requests = new CopyOnWriteArrayList<>();
        fallbacks.put(SINK, request -> {
            requests.add(request);
            return status(OK);
        });
        DataSpaceEventVO event = event();

        delivery.publish(event);

        awaitCount(EventDelivery.Outcome.DELIVERED, 1);
        HttpRequest<?> request = requests.get(0);
        assertEquals(SINK, request.getUri());
        assertEquals(EventDelivery.CLOUDEVENTS_JSON, request.getContentType().orElseThrow().toString());
        assertEquals(ORIGIN, request.getHeaders().get(WebhookHeaders.REQUEST_ORIGIN));
        assertEquals(Optional.of(event), request.getBody());
        assertEquals(Optional.of(CLIENT_ID), request.getAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE));
        assertEquals(Optional.of(subscription.scopes()), request.getAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE));
    }

    @Test
    void deliversOverTheWire() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<Received> received = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst(CONTENT_TYPE),
                    exchange.getRequestHeaders().getFirst(WebhookHeaders.REQUEST_ORIGIN),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(NO_CONTENT, NO_BODY);
            exchange.close();
        });
        server.start();
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "dataspace.events.listener.verifier.audience", "contract-management",
                "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks",
                "dataspace.events.publisher.enabled", true,
                "dataspace.events.publisher.origin", ORIGIN))) {
            URI sink = URI.create("http://127.0.0.1:%d/events".formatted(server.getAddress().getPort()));
            context.getBean(SubscriptionRegistry.class).register(SUBSCRIBER, sink, List.of(TYPE), CLIENT_ID, SCOPES, null);
            DataSpaceEventVO event = event();

            context.getBean(EventDelivery.class).publish(event);

            await().atMost(DELIVERY_TIMEOUT).until(() -> received.size() == 1);
            Received request = received.get(0);
            assertEquals("POST", request.method());
            assertEquals(EventDelivery.CLOUDEVENTS_JSON, request.contentType());
            assertEquals(ORIGIN, request.origin());
            assertEquals(event, context.getBean(JsonMapper.class).readValue(request.body(), DataSpaceEventVO.class));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusOutcomes")
    void mapsStatusesToOutcomes(String description, boolean withAuthHandler, Function<Integer, Answer> shape, int status,
                                EventDelivery.Outcome expectedOutcome, int expectedRequests) {
        if (withAuthHandler) {
            withOid4Vp();
        } else {
            withoutAuthHandler();
        }
        Subscription subscription = subscribe(SUBSCRIBER, SINK, null);
        fallbacks.put(SINK, request -> shape.apply(status));

        delivery.publish(event());

        assertOutcome(description, subscription, expectedOutcome, expectedRequests);
        verify(oid4VPClient, times(0)).getAccessToken(any());
    }

    static Stream<Arguments> statusOutcomes() {
        List<Arguments> arguments = new ArrayList<>();
        Map<String, Function<Integer, Answer>> shapes = new LinkedHashMap<>();
        shapes.put(RETURNED, EventDeliveryTest::returned);
        shapes.put(THROWN, EventDeliveryTest::thrown);
        for (boolean withAuthHandler : List.of(true, false)) {
            shapes.forEach((shapeName, shape) -> Stream.of(
                    Arguments.of(OK, EventDelivery.Outcome.DELIVERED, 1),
                    Arguments.of(FOUND, EventDelivery.Outcome.FAILED, 1),
                    Arguments.of(BAD_REQUEST, EventDelivery.Outcome.FAILED, 1),
                    Arguments.of(NOT_FOUND, EventDelivery.Outcome.FAILED, 1),
                    Arguments.of(GONE, EventDelivery.Outcome.GONE, 1),
                    Arguments.of(REQUEST_TIMEOUT, EventDelivery.Outcome.FAILED, MAX_ATTEMPTS),
                    Arguments.of(TOO_MANY_REQUESTS, EventDelivery.Outcome.FAILED, MAX_ATTEMPTS),
                    Arguments.of(SERVER_ERROR, EventDelivery.Outcome.FAILED, MAX_ATTEMPTS),
                    Arguments.of(SERVICE_UNAVAILABLE, EventDelivery.Outcome.FAILED, MAX_ATTEMPTS)
            ).forEach(statusCase -> {
                Object[] values = statusCase.get();
                // clients only throw for error statuses
                if (shapeName.equals(THROWN) && (int) values[0] < BAD_REQUEST) {
                    return;
                }
                arguments.add(Arguments.of(
                        "%s %s %s".formatted(withAuthHandler ? "auth handler" : "no auth handler", shapeName, values[0]),
                        withAuthHandler, shape, values[0], values[1], values[2]));
            }));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scriptedOutcomes")
    void mapsAnswerSequencesToOutcomes(String description, boolean withAuthHandler, List<Answer> answers, int tokenFailures,
                                       EventDelivery.Outcome expectedOutcome, int expectedRequests, int expectedTokenFetches) {
        if (withAuthHandler) {
            withOid4Vp();
        } else {
            withoutAuthHandler();
        }
        failTokenFetches(tokenFailures);
        Subscription subscription = subscribe(SUBSCRIBER, SINK, null);
        script(SINK, answers.toArray(Answer[]::new));

        delivery.publish(event());

        assertOutcome(description, subscription, expectedOutcome, expectedRequests);
        verify(oid4VPClient, times(expectedTokenFetches)).getAccessToken(any());
    }

    static Stream<Arguments> scriptedOutcomes() {
        return Stream.of(
                Arguments.of("503 retried, then delivered", true, List.of(status(SERVICE_UNAVAILABLE), status(OK)), 0,
                        EventDelivery.Outcome.DELIVERED, 2, 0),
                Arguments.of("connection error retried, then delivered", true, List.of(CONNECTION_ERROR, status(OK)), 0,
                        EventDelivery.Outcome.DELIVERED, 2, 0),
                Arguments.of("connection errors until attempts are exhausted", true,
                        List.of(CONNECTION_ERROR, CONNECTION_ERROR, CONNECTION_ERROR), 0, EventDelivery.Outcome.FAILED, MAX_ATTEMPTS, 0),
                Arguments.of("401 -> token -> 503 retried with a new token", true,
                        List.of(status(UNAUTHORIZED), status(SERVICE_UNAVAILABLE), status(UNAUTHORIZED), status(OK)), 0,
                        EventDelivery.Outcome.DELIVERED, 4, 2),
                Arguments.of("401 -> token -> 410 removes", true, List.of(status(UNAUTHORIZED), status(GONE)), 0,
                        EventDelivery.Outcome.GONE, 2, 1),
                Arguments.of("401 -> token -> 401 fails without retry", true,
                        List.of(status(UNAUTHORIZED), status(UNAUTHORIZED)), 0, EventDelivery.Outcome.FAILED, 2, 1),
                Arguments.of("failed token fetch retried", true,
                        List.of(status(UNAUTHORIZED), status(UNAUTHORIZED), status(OK)), 1, EventDelivery.Outcome.DELIVERED, 3, 2),
                Arguments.of("no auth handler: 401 fails without retry", false, List.of(status(UNAUTHORIZED)), 0,
                        EventDelivery.Outcome.FAILED, 1, 0),
                Arguments.of("no auth handler: 500 retried, then delivered", false, List.of(status(SERVER_ERROR), status(OK)), 0,
                        EventDelivery.Outcome.DELIVERED, 2, 0),
                Arguments.of("no auth handler: 500 then 410 removes", false, List.of(status(SERVER_ERROR), status(GONE)), 0,
                        EventDelivery.Outcome.GONE, 2, 0));
    }

    @Test
    void stopsDeliveryWhenTheLeaseExpires() {
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, null);
        // the lease runs out while the sink fails, so no retry may follow
        script(SINK, request -> {
            clock.advance(LEASE_TTL);
            return status(SERVICE_UNAVAILABLE);
        });

        delivery.publish(event());

        awaitCount(EventDelivery.Outcome.EXPIRED, 1);
        assertEquals(1, sent.size());
        assertEquals(0, count(EventDelivery.Outcome.FAILED));
    }

    @Test
    void doesNotDeliverQueuedEventsOfAnExpiredLease() {
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, null);
        CompletableFuture<HttpResponse<String>> firstAnswer = new CompletableFuture<>();
        script(SINK, request -> () -> Mono.fromFuture(firstAnswer));
        delivery.publish(event());
        delivery.publish(event());
        await().atMost(DELIVERY_TIMEOUT).until(() -> sent.size() == 1);

        clock.advance(LEASE_TTL);
        firstAnswer.complete(HttpResponse.ok());

        awaitCount(EventDelivery.Outcome.EXPIRED, 1);
        assertEquals(1, sent.size());
        assertEquals(1, count(EventDelivery.Outcome.DELIVERED));
    }

    @Test
    void doesNotQueueForSubscriptionsOfOtherTypesOrExpiredLeases() {
        withOid4Vp();
        registry.register(SUBSCRIBER, SINK, List.of("org.fiware.dataspace.other"), CLIENT_ID, SCOPES, null);
        subscribe(OTHER_SUBSCRIBER, OTHER_SINK, null);
        clock.advance(LEASE_TTL);

        delivery.publish(event());

        // channels are created synchronously in publish, so none means nothing was queued
        assertEquals(0, delivery.channelCount());
        await().during(QUIET_PERIOD).atMost(DELIVERY_TIMEOUT).until(() -> sent.isEmpty());
        for (EventDelivery.Outcome outcome : EventDelivery.Outcome.values()) {
            assertEquals(0, count(outcome), outcome.tag());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenSinks")
    void aBrokenSinkDoesNotDelayOthers(String description, Answer brokenAnswer) {
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, null);
        subscribe(OTHER_SUBSCRIBER, OTHER_SINK, null);
        fallbacks.put(SINK, request -> brokenAnswer);

        for (int i = 0; i < EVENTS; i++) {
            delivery.publish(event());
        }

        await().atMost(DELIVERY_TIMEOUT).until(() -> sentTo(OTHER_SINK).size() == EVENTS);
    }

    static Stream<Arguments> brokenSinks() {
        return Stream.of(
                Arguments.of("hanging sink", HANG),
                Arguments.of("failing sink", status(SERVER_ERROR)));
    }

    @Test
    void pacesRequestsNotEvents() {
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, ALLOWED_RATE);
        fallbacks.put(SINK, EventDeliveryTest::protectedSink);

        for (int i = 0; i < EVENTS; i++) {
            delivery.publish(event());
        }

        awaitCount(EventDelivery.Outcome.DELIVERED, EVENTS);
        // two requests per event: unauthenticated, then with the token
        assertEquals(2 * EVENTS, sent.size());
        assertPaced(sent);
    }

    @Test
    void renewalKeepsQueueAndPacing() {
        withOid4Vp();
        Subscription subscription = subscribe(SUBSCRIBER, SINK, ALLOWED_RATE);
        fallbacks.put(SINK, request -> status(OK));

        for (int i = 0; i < EVENTS; i++) {
            delivery.publish(event());
        }
        Subscription renewed = subscribe(SUBSCRIBER, SINK, ALLOWED_RATE);
        // queued behind the earlier ones: a fresh pacer after the renewal would send these in a burst
        for (int i = 0; i < EVENTS; i++) {
            delivery.publish(event());
        }

        awaitCount(EventDelivery.Outcome.DELIVERED, 2 * EVENTS);
        assertEquals(subscription.id(), renewed.id());
        assertEquals(1, delivery.channelCount());
        assertEquals(2 * EVENTS, sent.size());
        assertPaced(sent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stalledTokenFetches")
    void boundsAStalledTokenFetch(String description, int stalledFetches, EventDelivery.Outcome expectedOutcome) {
        properties.getPublisher().getDelivery().setAttemptTimeout(SHORT_ATTEMPT_TIMEOUT);
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, null);
        fallbacks.put(SINK, EventDeliveryTest::protectedSink);
        stallTokenFetches(stalledFetches);

        delivery.publish(event());

        awaitCount(expectedOutcome, 1);
        verify(oid4VPClient, times(Math.min(stalledFetches + 1, MAX_ATTEMPTS))).getAccessToken(any());
        // the channel is not stuck behind the stalled fetch
        delivery.publish(event());
        awaitCount(EventDelivery.Outcome.DELIVERED, expectedOutcome == EventDelivery.Outcome.DELIVERED ? 2 : 1);
    }

    static Stream<Arguments> stalledTokenFetches() {
        return Stream.of(
                Arguments.of("one stalled fetch is retried", 1, EventDelivery.Outcome.DELIVERED),
                Arguments.of("stalled on every attempt", MAX_ATTEMPTS, EventDelivery.Outcome.FAILED));
    }

    @Test
    void slotWaitsDoNotCountAgainstTheAttemptTimeout() {
        // shorter than the two slot waits of an attempt against a protected sink
        properties.getPublisher().getDelivery().setAttemptTimeout(PACING_INTERVAL);
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, ALLOWED_RATE);
        fallbacks.put(SINK, EventDeliveryTest::protectedSink);

        for (int i = 0; i < EVENTS; i++) {
            delivery.publish(event());
        }

        awaitCount(EventDelivery.Outcome.DELIVERED, EVENTS);
        assertEquals(2 * EVENTS, sent.size());
        assertEquals(0, count(EventDelivery.Outcome.FAILED));
    }

    @Test
    void countsOverflowingEvents() {
        properties.getPublisher().getDelivery().setQueueSize(SINGLE_QUEUE_SLOT);
        withOid4Vp();
        subscribe(SUBSCRIBER, SINK, null);
        fallbacks.put(SINK, request -> HANG);

        // the first is in flight, the second fills the only slot
        delivery.publish(event());
        await().atMost(DELIVERY_TIMEOUT).until(() -> sent.size() == 1);
        delivery.publish(event());
        delivery.publish(event());
        delivery.publish(event());

        assertEquals(2, count(EventDelivery.Outcome.OVERFLOW));
    }

    @ParameterizedTest(name = "pruned before the sink answers: {0}")
    @ValueSource(booleans = {false, true})
    void dropsQueuedEventsOfARemovedSubscription(boolean pruned) {
        withOid4Vp();
        Subscription subscription = subscribe(SUBSCRIBER, SINK, null);
        CompletableFuture<HttpResponse<String>> firstAnswer = new CompletableFuture<>();
        script(SINK, request -> () -> Mono.fromFuture(firstAnswer));
        delivery.publish(event());
        delivery.publish(event());
        await().atMost(DELIVERY_TIMEOUT).until(() -> sent.size() == 1);

        registry.remove(subscription.id(), SUBSCRIBER);
        if (pruned) {
            delivery.pruneChannels();
        }
        firstAnswer.complete(HttpResponse.ok());

        awaitCount(EventDelivery.Outcome.EXPIRED, 1);
        assertEquals(1, sent.size());
        assertEquals(1, count(EventDelivery.Outcome.DELIVERED));
    }

    private void assertOutcome(String description, Subscription subscription, EventDelivery.Outcome expectedOutcome,
                               int expectedRequests) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> count(expectedOutcome) == 1);
        assertEquals(expectedRequests, sent.size(), description);
        boolean removed = registry.get(subscription.id()).isEmpty();
        assertEquals(expectedOutcome == EventDelivery.Outcome.GONE, removed, description);
        for (EventDelivery.Outcome other : EventDelivery.Outcome.values()) {
            if (other != expectedOutcome) {
                assertEquals(0, count(other), description + ": " + other);
            }
        }
    }

    private void assertPaced(List<Sent> requests) {
        for (int i = 1; i < requests.size(); i++) {
            Duration gap = Duration.ofNanos(requests.get(i).nanos() - requests.get(i - 1).nanos());
            assertTrue(gap.plus(PACING_TOLERANCE).compareTo(PACING_INTERVAL) >= 0,
                    "Request %d followed after %s, faster than the allowed rate.".formatted(i, gap));
        }
    }

    private void withOid4Vp() {
        createDelivery(Optional.of(new Oid4VpAuthHandler(oid4VPClient)));
    }

    private void withoutAuthHandler() {
        createDelivery(Optional.empty());
    }

    private void createDelivery(Optional<AuthHandler> authHandler) {
        delivery = new EventDelivery(httpClient, authHandler, registry, properties, Optional.of(meterRegistry));
    }

    private void failTokenFetches(int failures) {
        Deque<Boolean> remaining = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < failures; i++) {
            remaining.add(true);
        }
        doAnswer(invocation -> remaining.poll() != null
                ? CompletableFuture.failedFuture(new IllegalStateException("verifier down"))
                : token()).when(oid4VPClient).getAccessToken(any());
    }

    private void stallTokenFetches(int stalls) {
        Deque<Boolean> remaining = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < stalls; i++) {
            remaining.add(true);
        }
        doAnswer(invocation -> remaining.poll() != null ? new CompletableFuture<TokenResponse>() : token())
                .when(oid4VPClient).getAccessToken(any());
    }

    private Subscription subscribe(String subscriber, URI sink, @Nullable Integer allowedRate) {
        return registry.register(subscriber, sink, List.of(TYPE), CLIENT_ID, SCOPES, allowedRate);
    }

    private void script(URI sink, Answer... answers) {
        scripts.computeIfAbsent(sink, key -> new ConcurrentLinkedDeque<>()).addAll(List.of(answers));
    }

    private void script(URI sink, Function<HttpRequest<?>, Answer> answer) {
        fallbacks.put(sink, answer);
    }

    private Mono<HttpResponse<String>> respond(HttpRequest<?> request) {
        URI sink = request.getUri();
        sent.add(new Sent(sink, request.getHeaders().getAuthorization().orElse(null), System.nanoTime()));
        Answer answer = Optional.ofNullable(scripts.get(sink))
                .map(Deque::poll)
                .orElseGet(() -> fallbacks.getOrDefault(sink, any -> status(OK)).apply(request));
        return answer.answer();
    }

    private List<Sent> sentTo(URI sink) {
        return sent.stream().filter(request -> request.sink().equals(sink)).toList();
    }

    private void awaitCount(EventDelivery.Outcome outcome, int expected) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> count(outcome) == expected);
    }

    private double count(EventDelivery.Outcome outcome) {
        Counter counter = meterRegistry.find(EventDelivery.METRIC_NAME)
                .tags(EventDelivery.TYPE_TAG, TYPE, EventDelivery.OUTCOME_TAG, outcome.tag())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static List<Sent> withoutTime(List<Sent> requests) {
        return requests.stream().map(request -> new Sent(request.sink(), request.authorization())).toList();
    }

    private static Answer protectedSink(HttpRequest<?> request) {
        return request.getHeaders().getAuthorization().isPresent() ? status(OK) : status(UNAUTHORIZED);
    }

    private static CompletableFuture<TokenResponse> token() {
        return CompletableFuture.completedFuture(new TokenResponse().setAccessToken(TOKEN));
    }

    private static DataSpaceEventVO event() {
        return new DataSpaceEventVO()
                .specversion(DataSpaceEventVO.Specversion._1_0)
                .id(UUID.randomUUID().toString())
                .source(ORIGIN)
                .type(TYPE);
    }

    /**
     * Like the real client: error statuses are thrown, others returned.
     */
    private static Answer status(int status) {
        return status >= BAD_REQUEST ? thrown(status) : returned(status);
    }

    private static Answer returned(int status) {
        return () -> Mono.just(HttpResponse.status(HttpStatus.valueOf(status)));
    }

    private static Answer thrown(int status) {
        return () -> Mono.error(new HttpClientResponseException("answered " + status, HttpResponse.status(HttpStatus.valueOf(status))));
    }

    private static final Answer CONNECTION_ERROR = () -> Mono.error(new HttpClientException("Connect Error: connection refused"));
    private static final Answer HANG = Mono::never;

    @FunctionalInterface
    private interface Answer {
        Mono<HttpResponse<String>> answer();
    }

    private record Received(String method, String contentType, String origin, String body) {
    }

    private record Sent(URI sink, @Nullable String authorization, long nanos) {
        Sent(URI sink, @Nullable String authorization) {
            this(sink, authorization, 0);
        }
    }
}
