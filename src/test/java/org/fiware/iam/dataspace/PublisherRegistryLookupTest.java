package org.fiware.iam.dataspace;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.http.client.HttpClient;
import org.fiware.iam.exception.TirLookupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the lookup against a stub registry over real HTTP.
 */
class PublisherRegistryLookupTest {

    private static final String DID = "did:web:participant.org";
    private static final String DID_WITH_PORT = "did:web:participant.org%3A8443:path";
    private static final String ISSUER_BODY = "{\"did\":\"" + DID + "\",\"attributes\":[]}";
    private static final int ANY_PORT = 0;
    private static final int NO_BACKLOG = 0;
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int SERVER_ERROR = 500;
    private static final int UNAVAILABLE = 503;

    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private volatile int status;
    private HttpServer server;
    private HttpClient httpClient;
    private PublisherRegistryLookup lookup;

    @BeforeEach
    void startRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", ANY_PORT), NO_BACKLOG);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getRawPath());
            byte[] body = ISSUER_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        httpClient = HttpClient.create((URL) null);
        lookup = new PublisherRegistryLookup(httpClient);
    }

    @AfterEach
    void stopRegistry() {
        httpClient.close();
        server.stop(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    void mapsTheRegistryAnswer(int answer, Boolean expected) {
        status = answer;

        if (expected == null) {
            TirLookupException e = assertThrows(TirLookupException.class, () -> lookup.isListed(registryUrl(), DID).block());
            assertEquals(answer, e.getStatus());
        } else {
            assertEquals(expected, lookup.isListed(registryUrl(), DID).block());
        }
        assertEquals(List.of("/v4/issuers/did%3Aweb%3Aparticipant.org"), requestedPaths);
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of(OK, true),
                Arguments.of(NOT_FOUND, false),
                Arguments.of(SERVER_ERROR, null),
                Arguments.of(UNAVAILABLE, null));
    }

    @Test
    void encodesTheDidAsOnePathSegment() {
        status = OK;

        lookup.isListed(registryUrl() + "/", DID_WITH_PORT).block();

        assertEquals(List.of("/v4/issuers/did%3Aweb%3Aparticipant.org%253A8443%3Apath"), requestedPaths);
    }

    @Test
    void connectionErrorFailsTheLookup() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(ANY_PORT)) {
            closedPort = socket.getLocalPort();
        }

        TirLookupException e = assertThrows(TirLookupException.class,
                () -> lookup.isListed("http://localhost:" + closedPort, DID).block());

        assertNull(e.getStatus());
    }

    private String registryUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
