package org.fiware.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishers added to the contract-management-provider ConfigMap are accepted without a restart, through the file watch.
 * The deployment has no gateway in front, so the identity header is set directly, as APISIX would from a verified token.
 * The deployed CM has no handler for the test event type, so whether a publisher is configured is observed through the
 * OPTIONS origin grant.
 * The watch is enabled here, so /refresh is only checked for reachability; DataSpaceEventsRefreshTest proves it applies changes.
 * The anchor publisher adopts and revokes dataspace-membership grants at the provider TIL; the consumer TIL is its registry.
 */
@EnabledIfSystemProperty(named = "inContainer", matches = "true")
class InContainerDataSpaceEventsIT {

    private static final String KUBECONFIG_PROPERTY = "k3s.kubeconfig";
    private static final String NAMESPACE = "default";
    private static final String CONFIG_MAP = "contract-management-provider";
    private static final String CONFIG_KEY = "application.yaml";
    private static final List<String> PUBLISHERS_PATH = List.of("dataspace", "events", "publishers");
    private static final String EVENTS_URL = "http://localhost:8089/events";
    private static final String REFRESH_URL = "http://localhost:9090/refresh";
    private static final String IDENTITY_HEADER = DataSpaceEventsProperties.DEFAULT_IDENTITY_HEADER;
    private static final String TYPE = "org.fiware.dataspace.test";
    private static final String CLOUDEVENTS_JSON = "application/cloudevents+json";
    private static final String REQUEST_ORIGIN_HEADER = "WebHook-Request-Origin";
    private static final String ALLOWED_ORIGIN_HEADER = "WebHook-Allowed-Origin";
    private static final String CONFIGURED_PUBLISHER = "did:web:publisher-a.io";
    private static final Publisher PUBLISHER_B = new Publisher("did:web:publisher-b.io", "events.publisher-b.io");
    private static final Publisher PUBLISHER_C = new Publisher("did:web:publisher-c.io", "events.publisher-c.io");
    private static final String HEALTH_URL = "http://localhost:9090/health";
    private static final String PROVIDER_TIL_URL = "http://localhost:7085";
    // the anchor's registry-url in the ConfigMap, as the host reaches it
    private static final String REGISTRY_TIL_URL = "http://localhost:8085";
    private static final String ANCHOR = "did:web:anchor.io";
    // dataspace.events.dataspace-id of the ConfigMap
    private static final String DATASPACE_ID = "test-dataspace";
    private static final String MEMBERSHIP_SCOPE_TYPE = "dataspace-membership";
    private static final String CREDENTIALS_TYPE = "VerifiableCredential";
    private static final int TIL_PAGE_SIZE = 100;
    // ParticipantRemovedHealthIndicator.NAME and TIL_DETAIL
    private static final String PARTICIPANT_REMOVED_HEALTH = "dataspace-participant-removed";
    private static final String TIL_HEALTH_DETAIL = "til";
    private static final String STATUS_UP = "UP";
    private static final String SUPPORTED = TilCapability.State.SUPPORTED.name().toLowerCase(Locale.ROOT);
    private static final String PENDING = TilCapability.State.PENDING.name().toLowerCase(Locale.ROOT);
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_MULTIPLE_CHOICES = 300;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    // kubelet propagates ConfigMap changes after its sync period (1 min) plus the ConfigMap cache TTL
    private static final Duration KUBELET_PROPAGATION = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    // participant-removed.reconcile.interval of the ConfigMap
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(5);
    private static final Duration SWEEP_MARGIN = Duration.ofSeconds(25);
    // an UNVERIFIED or PENDING capability blocks sweeps and removals until its next re-probe, at most one retry interval away
    private static final Duration SWEEP_TIMEOUT = TilCapabilities.PROBE_RETRY_INTERVAL.plus(RECONCILE_INTERVAL).plus(SWEEP_MARGIN);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private KubernetesClient kubernetes;
    private String originalConfig;
    private String member;

    @BeforeEach
    void connect() throws IOException {
        String kubeconfig = Files.readString(Path.of(System.getProperty(KUBECONFIG_PROPERTY)));
        kubernetes = new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kubeconfig)).build();
        originalConfig = configMap().get().getData().get(CONFIG_KEY);
    }

    /**
     * Restores the ConfigMap and waits until the watch applied it, so later tests see the original publishers.
     */
    @AfterEach
    void restore() {
        if (kubernetes == null) {
            return;
        }
        try {
            if (originalConfig != null) {
                setConfig(originalConfig);
                await().atMost(KUBELET_PROPAGATION).pollInterval(POLL_INTERVAL)
                        .until(() -> grantedOrigin(PUBLISHER_B.origin()).isEmpty()
                                && grantedOrigin(PUBLISHER_C.origin()).isEmpty());
            }
        } finally {
            kubernetes.close();
        }
    }

    /**
     * Removes the member from both TILs, so reruns and the other ITs sharing them are not affected.
     */
    @AfterEach
    void removeMember() {
        if (member != null) {
            deleteIssuer(PROVIDER_TIL_URL, member);
            deleteIssuer(REGISTRY_TIL_URL, member);
        }
    }

    @Test
    void healthReportsTheRequiredTilCapabilities() {
        awaitTypedScopesSupported();
        String scopesEndpoint = tilCapabilities(health()).get(TilCapability.SCOPES_ENDPOINT.getId());
        // pending as long as the provider TIL holds no issuer to probe with
        assertTrue(SUPPORTED.equals(scopesEndpoint) || PENDING.equals(scopesEndpoint),
                "Unexpected scopes-endpoint state " + scopesEndpoint);
    }

    @Test
    void adoptionGrantsTheMembershipAndRemovalRevokesIt() {
        // the sweep only runs once the required capabilities are supported
        awaitTypedScopesSupported();
        member = "did:web:member-%s.io".formatted(UUID.randomUUID());

        assertEquals(HTTP_CREATED, createUnattributedIssuer(PROVIDER_TIL_URL, member));
        assertEquals(HTTP_CREATED, createUnattributedIssuer(REGISTRY_TIL_URL, member));
        await().atMost(SWEEP_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> membershipGrantees().contains(member));
        assertEquals(1, credentialCount(member), "The unattributed credential should have been moved, not copied.");
        JsonNode scopes = scopesOf(member).orElseThrow();
        assertEquals(1, scopes.size(), "Only the membership grant should remain: " + scopes);
        assertEquals(ANCHOR, scopes.get(0).path("scope").asText());
        assertEquals(MEMBERSHIP_SCOPE_TYPE, scopes.get(0).path("scopeType").asText());
        await().atMost(SWEEP_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> SUPPORTED.equals(tilCapabilities(health()).get(TilCapability.SCOPES_ENDPOINT.getId())));

        int registryDelete = deleteIssuer(REGISTRY_TIL_URL, member);
        // still listed, the removal would be answered STILL_LISTED and the await below would time out
        assertTrue(isSuccess(registryDelete), "The registry did not delist the member: " + registryDelete);
        assertEquals(HTTP_ACCEPTED, postParticipantRemoved(member));
        // the event or the sweep, whichever comes first, revokes the grant
        await().atMost(SWEEP_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> !membershipGrantees().contains(member));
        assertFalse(scopesOf(member).stream()
                        .flatMap(list -> StreamSupport.stream(list.spliterator(), false))
                        .anyMatch(scope -> MEMBERSHIP_SCOPE_TYPE.equals(scope.path("scopeType").asText())),
                "No membership grant should remain.");
    }

    @Test
    void refreshEndpointIsReachable() {
        assertEquals(HTTP_OK, refresh());
    }

    @Test
    void listenerTakesTheParticipantFromTheGatewayHeader() {
        assertEquals(HTTP_UNAUTHORIZED, postEvent(CONFIGURED_PUBLISHER, null));
        assertEquals(HTTP_ACCEPTED, postEvent(CONFIGURED_PUBLISHER, userinfo(CONFIGURED_PUBLISHER)));
    }

    @Test
    void addedPublishersAreAcceptedWithoutRestart() {
        assertTrue(grantedOrigin(PUBLISHER_B.origin()).isEmpty());

        // no /refresh: the watch has to pick up kubelet's ..data symlink swap on its own, also a second time
        setConfig(withPublishers(originalConfig, PUBLISHER_B));
        await().atMost(KUBELET_PROPAGATION).pollInterval(POLL_INTERVAL)
                .until(() -> grantedOrigin(PUBLISHER_B.origin()).equals(Optional.of(PUBLISHER_B.origin())));

        setConfig(withPublishers(originalConfig, PUBLISHER_B, PUBLISHER_C));
        await().atMost(KUBELET_PROPAGATION).pollInterval(POLL_INTERVAL)
                .until(() -> grantedOrigin(PUBLISHER_C.origin()).equals(Optional.of(PUBLISHER_C.origin())));
    }

    /**
     * Appends the publishers to the dataspace.events.publishers list of the (multi-document) config, wherever it is.
     */
    private static String withPublishers(String config, Publisher... publishers) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        List<Object> documents = new ArrayList<>();
        yaml.loadAll(config).forEach(documents::add);
        List<Map<String, Object>> publisherList = documents.stream()
                .map(document -> publisherList(document, PUBLISHERS_PATH))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The CM config has no " + String.join(".", PUBLISHERS_PATH) + " list."));
        Arrays.stream(publishers).map(Publisher::asConfig).forEach(publisherList::add);
        return yaml.dumpAll(documents.iterator());
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<Map<String, Object>>> publisherList(Object node, List<String> path) {
        if (path.isEmpty()) {
            return node instanceof List<?> list ? Optional.of((List<Map<String, Object>>) list) : Optional.empty();
        }
        if (node instanceof Map<?, ?> map) {
            return publisherList(map.get(path.get(0)), path.subList(1, path.size()));
        }
        return Optional.empty();
    }

    private Resource<ConfigMap> configMap() {
        return kubernetes.configMaps().inNamespace(NAMESPACE).withName(CONFIG_MAP);
    }

    private void setConfig(String config) {
        configMap().edit(configMap -> {
            configMap.getData().put(CONFIG_KEY, config);
            return configMap;
        });
    }

    private int postEvent(String participant, String identity) {
        String event = """
                {"specversion": "1.0", "id": "%s", "source": "%s", "type": "%s"}
                """.formatted(UUID.randomUUID(), participant, TYPE);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .header("Content-Type", CLOUDEVENTS_JSON);
        if (identity != null) {
            request.header(IDENTITY_HEADER, identity);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(event))).statusCode();
    }

    private int postParticipantRemoved(String did) {
        String event = """
                {"specversion": "1.0", "id": "%s", "source": "%s", "type": "%s", "subject": "%s", "dataspaceid": "%s"}
                """.formatted(UUID.randomUUID(), ANCHOR, DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE, did, DATASPACE_ID);
        return send(HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .header("Content-Type", CLOUDEVENTS_JSON)
                .header(IDENTITY_HEADER, userinfo(ANCHOR))
                .POST(HttpRequest.BodyPublishers.ofString(event))).statusCode();
    }

    private void awaitTypedScopesSupported() {
        await().atMost(SWEEP_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> {
            JsonNode health = health();
            Map<String, String> capabilities = tilCapabilities(health);
            return STATUS_UP.equals(health.path("status").asText())
                    && SUPPORTED.equals(capabilities.get(TilCapability.TYPED_SCOPES.getId()))
                    && SUPPORTED.equals(capabilities.get(TilCapability.UNSCOPED.getId()));
        });
    }

    private JsonNode health() {
        return json(send(HttpRequest.newBuilder(URI.create(HEALTH_URL)).GET()).body());
    }

    /**
     * The TIL capability states the participant-removed health component reports, by capability id.
     */
    private static Map<String, String> tilCapabilities(JsonNode health) {
        JsonNode til = health.path("details").path(PARTICIPANT_REMOVED_HEALTH).path("details").path(TIL_HEALTH_DETAIL);
        Map<String, String> capabilities = new HashMap<>();
        til.fields().forEachRemaining(field -> capabilities.put(field.getKey(), field.getValue().asText()));
        return capabilities;
    }

    private int createUnattributedIssuer(String tilUrl, String did) {
        String issuer = """
                {"did": "%s", "credentials": [{"credentialsType": "%s"}]}
                """.formatted(did, CREDENTIALS_TYPE);
        return send(HttpRequest.newBuilder(URI.create(tilUrl + "/issuer"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(issuer))).statusCode();
    }

    private int deleteIssuer(String tilUrl, String did) {
        return send(HttpRequest.newBuilder(URI.create(tilUrl + "/issuer/" + did)).DELETE()).statusCode();
    }

    private static boolean isSuccess(int status) {
        return status >= HTTP_OK && status < HTTP_MULTIPLE_CHOICES;
    }

    private List<String> membershipGrantees() {
        String query = "?pageSize=%d&scope=%s&scopeType=%s".formatted(TIL_PAGE_SIZE, ANCHOR, MEMBERSHIP_SCOPE_TYPE);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(PROVIDER_TIL_URL + "/issuer" + query)).GET());
        // an error answer has no items and would let the removal await pass at once
        assertEquals(HTTP_OK, response.statusCode(), "Unexpected issuer list answer " + response.body());
        JsonNode items = json(response.body()).path("items");
        return StreamSupport.stream(items.spliterator(), false).map(JsonNode::asText).toList();
    }

    private int credentialCount(String did) {
        return json(send(HttpRequest.newBuilder(URI.create(PROVIDER_TIL_URL + "/issuer/" + did)).GET()).body())
                .path("credentials").size();
    }

    /**
     * The issuer's grants at the provider TIL; empty if the TIL does not hold the issuer.
     */
    private Optional<JsonNode> scopesOf(String did) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(PROVIDER_TIL_URL + "/issuer/" + did + "/scopes")).GET());
        if (response.statusCode() == HTTP_NOT_FOUND) {
            return Optional.empty();
        }
        assertEquals(HTTP_OK, response.statusCode(), "Unexpected scopes answer " + response.body());
        return Optional.of(json(response.body()));
    }

    private static JsonNode json(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<String> grantedOrigin(String origin) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .header(REQUEST_ORIGIN_HEADER, origin)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        return send(request).headers().firstValue(ALLOWED_ORIGIN_HEADER);
    }

    private int refresh() {
        return send(HttpRequest.newBuilder(URI.create(REFRESH_URL)).POST(HttpRequest.BodyPublishers.noBody())).statusCode();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The identity header an API gateway sets in front of CM: base64 of the claims of the token it verified.
     */
    private static String userinfo(String participant) {
        String claims = "{\"%s\": \"%s\"}".formatted(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM, participant);
        return Base64.getEncoder().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    }

    private record Publisher(String id, String origin) {

        /**
         * The publisher as an entry of the dataspace.events.publishers list in the CM ConfigMap.
         */
        Map<String, Object> asConfig() {
            return Map.of("id", id, "origin", origin, "types", List.of(TYPE));
        }
    }
}
