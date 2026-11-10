package org.fiware.iam.dataspace;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks the removed-participants report over HTTP on the management port, and that the public port never serves it.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemovedParticipantsEndpointHttpTest implements TestPropertyProvider {

    private static final String PATH = "/" + RemovedParticipantsEndpoint.ID;
    private static final String PUBLISHER = "did:web:anchor.org";
    private static final String HELD = "did:web:held.org";
    private static final String GONE = "did:web:gone.org";
    private static final String ORDER_ID = "urn:ngsi-ld:product-order:1";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final int serverPort = freePort();
    private final int managementPort = freePort();
    private final HttpClient client = HttpClient.create(url(managementPort));
    private final HttpClient publicClient = HttpClient.create(url(serverPort));

    @Inject
    private RemovedParticipantsReport report;

    @Inject
    private TilCapabilities capabilities;

    @Inject
    private ParticipantReconciler reconciler;

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
        properties.put("dataspace.events.own-participant-id", "did:web:provider.org");
        properties.put("dataspace.events.publishers[0].id", PUBLISHER);
        properties.put("dataspace.events.publishers[0].origin", "anchor.org");
        properties.put("dataspace.events.publishers[0].types[0]", PARTICIPANT_REMOVED_TYPE);
        return properties;
    }

    @MockBean(TilCapabilities.class)
    TilCapabilities capabilities() {
        return mock(TilCapabilities.class);
    }

    @MockBean(ParticipantReconciler.class)
    ParticipantReconciler reconciler() {
        return mock(ParticipantReconciler.class);
    }

    @MockBean(Clock.class)
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @AfterAll
    void closeClients() {
        client.close();
        publicClient.close();
    }

    @Test
    void reportsWhatRemovedParticipantsStillHoldInReportMode() {
        when(capabilities.required()).thenReturn(Set.of(TilCapability.TYPED_SCOPES, TilCapability.SCOPES_ENDPOINT));
        when(capabilities.state()).thenReturn(Map.of(TilCapability.TYPED_SCOPES, TilCapability.State.SUPPORTED,
                TilCapability.SCOPES_ENDPOINT, TilCapability.State.SUPPORTED, TilCapability.UNSCOPED, TilCapability.State.UNVERIFIED));
        when(reconciler.trippedBreakers()).thenReturn(Map.of());
        report.record(new RemovalOutcome(PUBLISHER, HELD, RemovalTrigger.RECONCILIATION, "report", RemovalAction.REPORTED, false,
                new HeldEntry(HELD, List.of(new HeldEntry.Grant(PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1),
                        new HeldEntry.Grant(ORDER_ID, null, 2)), 1)));
        report.record(new RemovalOutcome(PUBLISHER, GONE, RemovalTrigger.EVENT, "report", RemovalAction.NOT_HELD, false,
                new HeldEntry(GONE, List.of(), 0)));

        List<Map<String, Object>> body = client.toBlocking().retrieve(HttpRequest.GET(PATH), Argument.listOf(Argument.mapOf(String.class, Object.class)));

        Map<String, Object> gone = new HashMap<>(Map.of("id", GONE, "trigger", "event", "lastSeen", NOW.toString(),
                "mode", "report", "action", "not_held", "applied", false, "attributable", true, "held", List.of(),
                "unattributedCount", 0));
        Map<String, Object> held = new HashMap<>(Map.of("id", HELD, "trigger", "reconciliation", "lastSeen", NOW.toString(),
                "mode", "report", "action", "reported", "applied", false, "attributable", false,
                "held", List.of(grant(PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP.getValue(), 1), grant(ORDER_ID, null, 2)),
                "unattributedCount", 1));
        assertEquals(List.of(Map.of("publisher", PUBLISHER, "mode", "report", "til", "supported", "adoption", "off",
                "breaker", "clear", "participants", List.of(gone, held))), body);
    }

    @Test
    void publicPortDoesNotServeTheReport() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
                () -> publicClient.toBlocking().exchange(HttpRequest.GET(PATH)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    // a HashMap, since Map.of rejects the null scope type of an order grant
    private static Map<String, Object> grant(String scope, String scopeType, int credentialCount) {
        Map<String, Object> grant = new HashMap<>();
        grant.put("scope", scope);
        grant.put("scopeType", scopeType);
        grant.put("credentialCount", credentialCount);
        return grant;
    }

    private static URL url(int port) {
        try {
            return URI.create("http://localhost:" + port).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
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
