package org.fiware.iam;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishers added to the contract-management-provider ConfigMap are accepted without a restart, through the file watch.
 * The deployed CM has no event handler, so whether a publisher is configured is observed through the OPTIONS origin grant.
 * The watch is enabled here, so /refresh is only checked for reachability; DataSpaceEventsRefreshTest proves it applies changes.
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
    private static final String VERIFIER_KEY = "dataspace/events-verifier-key.json";
    private static final String AUDIENCE = "contract-management";
    private static final String TYPE = "org.fiware.dataspace.test";
    private static final String CLOUDEVENTS_JSON = "application/cloudevents+json";
    private static final String REQUEST_ORIGIN_HEADER = "WebHook-Request-Origin";
    private static final String ALLOWED_ORIGIN_HEADER = "WebHook-Allowed-Origin";
    private static final String CONFIGURED_PUBLISHER = "did:web:publisher-a.io";
    private static final Publisher PUBLISHER_B = new Publisher("did:web:publisher-b.io", "events.publisher-b.io");
    private static final Publisher PUBLISHER_C = new Publisher("did:web:publisher-c.io", "events.publisher-c.io");
    private static final int HTTP_OK = 200;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    // kubelet propagates ConfigMap changes after its sync period (1 min) plus the ConfigMap cache TTL
    private static final Duration KUBELET_PROPAGATION = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final HttpClient http = HttpClient.newHttpClient();
    private KubernetesClient kubernetes;
    private String originalConfig;

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

    @Test
    void refreshEndpointIsReachable() {
        assertEquals(HTTP_OK, refresh());
    }

    @Test
    void listenerVerifiesParticipantTokens() {
        assertEquals(HTTP_UNAUTHORIZED, postEvent(CONFIGURED_PUBLISHER, null));
        assertEquals(HTTP_ACCEPTED, postEvent(CONFIGURED_PUBLISHER, token(CONFIGURED_PUBLISHER)));
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

    private int postEvent(String participant, String token) {
        String event = """
                {"specversion": "1.0", "id": "%s", "source": "%s", "type": "%s"}
                """.formatted(UUID.randomUUID(), participant, TYPE);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(EVENTS_URL))
                .header("Content-Type", CLOUDEVENTS_JSON);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(event))).statusCode();
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

    private static String token(String participant) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                .claim(DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM, participant)
                .build();
        ECKey key = verifierKey();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static ECKey verifierKey() {
        try (InputStream in = InContainerDataSpaceEventsIT.class.getClassLoader().getResourceAsStream(VERIFIER_KEY)) {
            return ECKey.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
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
