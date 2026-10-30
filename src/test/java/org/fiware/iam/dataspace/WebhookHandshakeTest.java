package org.fiware.iam.dataspace;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.WebhookHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookHandshakeTest {

    private static final String ORIGIN = "cm.provider.org";
    private static final String OTHER_ORIGIN = "cm.other.org";
    private static final String SINK_PATH = "/sink";
    private static final String REDIRECT_TARGET_PATH = "/target";
    private static final String OPTIONS = "OPTIONS";
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;
    private static final int TEMPORARY_REDIRECT = 307;
    private static final int NOT_FOUND = 404;
    private static final int SERVER_ERROR = 500;
    private static final int NO_BODY = -1;
    private static final Map<String, Object> CONFIG = Map.of(
            "notification.enabled", false,
            "general.enable-rainbow", false,
            "dataspace.events.listener.verifier.audience", "contract-management",
            "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks",
            "dataspace.events.publisher.enabled", true,
            "dataspace.events.publisher.origin", ORIGIN);

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ApplicationContext context;
    private volatile int status;
    private volatile Map<String, String> responseHeaders;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
        context = ApplicationContext.run(CONFIG);
    }

    @AfterEach
    void tearDown() {
        context.close();
        server.stop(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedHandshakes")
    void acceptsAllowedOrigins(String description, int responseStatus, Map<String, String> headers,
                               Optional<Integer> expectedRate) {
        answer(responseStatus, headers);

        assertEquals(expectedRate, handshake().validate(sink(SINK_PATH)).block(), description);
        assertEquals(List.of(new Request(OPTIONS, SINK_PATH, ORIGIN)), requests, description);
    }

    static Stream<Arguments> acceptedHandshakes() {
        return Stream.of(
                Arguments.of("matching origin", OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN), Optional.empty()),
                Arguments.of("case-differing origin", OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN.toUpperCase()),
                        Optional.empty()),
                Arguments.of("any origin", OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, WebhookHandshake.ANY), Optional.empty()),
                Arguments.of("no content", NO_CONTENT, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN), Optional.empty()),
                Arguments.of("numeric rate", OK,
                        Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN, WebhookHeaders.ALLOWED_RATE, "120"), Optional.of(120)),
                Arguments.of("unlimited rate", OK,
                        Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN, WebhookHeaders.ALLOWED_RATE, WebhookHandshake.ANY),
                        Optional.empty()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedHandshakes")
    void rejectsSinksNotAllowingTheOrigin(String description, int responseStatus, Map<String, String> headers) {
        answer(responseStatus, headers);

        assertHandshakeFails(sink(SINK_PATH), description);
    }

    static Stream<Arguments> rejectedHandshakes() {
        return Stream.of(
                Arguments.of("missing origin", OK, Map.of()),
                Arguments.of("non-matching origin", OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, OTHER_ORIGIN)),
                Arguments.of("not found", NOT_FOUND, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN)),
                Arguments.of("server error", SERVER_ERROR, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN)),
                Arguments.of("garbage rate", OK,
                        Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN, WebhookHeaders.ALLOWED_RATE, "fast")),
                Arguments.of("zero rate", OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN, WebhookHeaders.ALLOWED_RATE, "0")),
                Arguments.of("negative rate", OK,
                        Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN, WebhookHeaders.ALLOWED_RATE, "-5")));
    }

    @Test
    void doesNotFollowRedirects() {
        answer(TEMPORARY_REDIRECT, Map.of(
                "Location", sink(REDIRECT_TARGET_PATH).toString(),
                WebhookHeaders.ALLOWED_ORIGIN, ORIGIN));

        assertHandshakeFails(sink(SINK_PATH), "A redirect must fail the handshake.");
        assertTrue(requests.stream().noneMatch(request -> request.path().equals(REDIRECT_TARGET_PATH)),
                "The redirect target must never be called.");
    }

    @Test
    void failsOnConnectionErrors() throws IOException {
        URI unreachable;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            unreachable = URI.create("http://127.0.0.1:%d%s".formatted(socket.getLocalPort(), SINK_PATH));
        }

        WebhookHandshakeException thrown = assertHandshakeFails(unreachable, "An unreachable sink must fail the handshake.");

        assertNotNull(thrown.getCause(), "The connection error has to be kept.");
    }

    @Test
    void failsWithoutConfiguredOrigin() {
        answer(OK, Map.of(WebhookHeaders.ALLOWED_ORIGIN, ORIGIN));
        context.getBean(DataSpaceEventsProperties.class).getPublisher().setOrigin(null);

        assertHandshakeFails(sink(SINK_PATH), "Without an origin there is nothing to validate.");
        assertEquals(List.of(), requests);
    }

    private WebhookHandshakeException assertHandshakeFails(URI sink, String description) {
        Exception thrown = assertThrows(Exception.class, () -> handshake().validate(sink).block(), description);
        WebhookHandshakeException handshakeException = assertInstanceOf(WebhookHandshakeException.class, thrown, description);
        assertEquals(sink, handshakeException.getSink(), description);
        return handshakeException;
    }

    private WebhookHandshake handshake() {
        return context.getBean(WebhookHandshake.class);
    }

    private void answer(int responseStatus, Map<String, String> headers) {
        status = responseStatus;
        responseHeaders = headers;
    }

    private URI sink(String path) {
        return URI.create("http://127.0.0.1:%d%s".formatted(server.getAddress().getPort(), path));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(WebhookHeaders.REQUEST_ORIGIN)));
        responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, NO_BODY);
        exchange.close();
    }

    private record Request(String method, String path, @Nullable String origin) {
    }
}
