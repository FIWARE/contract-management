package org.fiware.iam.dataspace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.scheduling.io.watch.event.FileChangedEvent;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.fiware.iam.dataspace.TestEventFixtures.KNOWN_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishers added to the configuration file are accepted without a restart, through /refresh and through the file watch.
 * The config directory is laid out like a directory-mounted ConfigMap, updated by kubelet's atomic ..data symlink swap.
 */
class DataSpaceEventsRefreshTest {

    private static final String CONFIG_FILES_PROPERTY = "micronaut.config.files";
    private static final String CONFIG_FILE = "application.yaml";
    private static final String DATA_LINK = "..data";
    private static final String DATA_LINK_TMP = "..data_tmp";
    private static final String EVENTS_PATH = "/events";
    private static final String REFRESH_PATH = "/refresh";
    private static final String CLOUDEVENTS_JSON = "application/cloudevents+json";
    private static final String AUDIENCE = "contract-management";
    private static final Publisher PUBLISHER_A = new Publisher("did:web:a.org", "events.a.org");
    private static final Publisher PUBLISHER_B = new Publisher("did:web:b.org", "events.b.org");
    private static final Publisher PUBLISHER_C = new Publisher("did:web:c.org", "events.c.org");
    private static final Duration WATCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration QUIET_PERIOD = ConfigFileRefresher.DEBOUNCE.multipliedBy(4);

    @TempDir
    private Path configDir;

    private Path currentVersion;
    private ApplicationContext context;
    private BlockingHttpClient client;
    private BlockingHttpClient managementClient;
    private JsonMapper jsonMapper;

    @BeforeEach
    void startServer() throws IOException {
        writeConfig(List.of(PUBLISHER_A));
        Files.createSymbolicLink(configDir.resolve(CONFIG_FILE), Path.of(DATA_LINK, CONFIG_FILE));

        int port = freePort();
        int managementPort = freePort();
        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.server.port", port);
        properties.put("endpoints.all.port", managementPort);
        properties.put("notification.enabled", false);
        properties.put("general.enable-rainbow", false);
        properties.put(TestEventFixtures.ENABLED_PROPERTY, true);
        properties.put("dataspace.events.listener.enabled", true);
        properties.put("dataspace.events.listener.verifier.audience", AUDIENCE);
        properties.put("dataspace.events.listener.verifier.jwks-url", "http://localhost:" + port + TestEventFixtures.JWKS_PATH);
        properties.put("dataspace.events.listener.watch.enabled", true);
        properties.put("dataspace.events.listener.watch.path", configDir.toString());

        // the environment reads micronaut.config.files from the system properties only, as MICRONAUT_CONFIG_FILES in production
        System.setProperty(CONFIG_FILES_PROPERTY, configDir.resolve(CONFIG_FILE).toString());
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, properties, "test");
        context = server.getApplicationContext();
        client = context.createBean(HttpClient.class, server.getURL()).toBlocking();
        managementClient = context.createBean(HttpClient.class,
                new URL(server.getScheme(), server.getHost(), managementPort, "")).toBlocking();
        jsonMapper = context.getBean(JsonMapper.class);
        // netty sets system properties after startup; absorb them so the first diff only holds the test's own edits
        context.getEnvironment().refreshAndDiff();
    }

    @AfterEach
    void stopServer() {
        System.clearProperty(CONFIG_FILES_PROPERTY);
        if (context != null) {
            context.close();
        }
    }

    @Test
    void addedPublishersAreAcceptedWithoutRestart() throws IOException {
        assertEquals(HttpStatus.FORBIDDEN, post(PUBLISHER_B).getStatus());

        writeConfig(List.of(PUBLISHER_A, PUBLISHER_B));
        assertEquals(HttpStatus.OK, exchange(managementClient, HttpRequest.POST(REFRESH_PATH, Map.of())).getStatus());
        assertEquals(HttpStatus.ACCEPTED, post(PUBLISHER_B).getStatus());

        assertNull(grantedOrigin(PUBLISHER_C.origin()));
        writeConfig(List.of(PUBLISHER_A, PUBLISHER_B, PUBLISHER_C));
        await().atMost(WATCH_TIMEOUT).until(() -> post(PUBLISHER_C).getStatus() == HttpStatus.ACCEPTED);
        assertEquals(PUBLISHER_C.origin(), grantedOrigin(PUBLISHER_C.origin()));
    }

    @Test
    void unchangedFileTriggersNoRefresh() throws IOException {
        RefreshRecorder refreshes = context.getBean(RefreshRecorder.class);
        FileChangeRecorder fileChanges = context.getBean(FileChangeRecorder.class);

        writeConfig(List.of(PUBLISHER_A));
        await().atMost(WATCH_TIMEOUT).until(() -> fileChanges.count() > 0);
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.multipliedBy(2)).until(() -> refreshes.count() == 0);

        writeConfig(List.of(PUBLISHER_A, PUBLISHER_B));
        await().atMost(WATCH_TIMEOUT).until(() -> refreshes.count() == 1);
    }

    @Test
    void refreshEndpointWaitsForARunningRefresh() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConfigurationRefreshLock refreshLock = context.getBean(ConfigurationRefreshLock.class);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> refreshLock.exclusively(() -> {
            holding.countDown();
            return awaitQuietly(release);
        }));
        assertTrue(holding.await(WATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS));

        CompletableFuture<HttpResponse<?>> refresh = CompletableFuture.supplyAsync(
                () -> exchange(managementClient, HttpRequest.POST(REFRESH_PATH, Map.of())));
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.multipliedBy(2)).until(() -> !refresh.isDone());

        release.countDown();
        running.get(WATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK, refresh.get(WATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS).getStatus());
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(WATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Writes the config as kubelet updates a ConfigMap: a new ..<version> directory, then an atomic rename of the ..data symlink.
     */
    private void writeConfig(List<Publisher> publishers) throws IOException {
        Path version = Files.createDirectory(configDir.resolve(".." + UUID.randomUUID()));
        Files.writeString(version.resolve(CONFIG_FILE), yaml(publishers));
        Path tmpLink = Files.createSymbolicLink(configDir.resolve(DATA_LINK_TMP), version.getFileName());
        Files.move(tmpLink, configDir.resolve(DATA_LINK), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (currentVersion != null) {
            deleteRecursively(currentVersion);
        }
        currentVersion = version;
    }

    private static String yaml(List<Publisher> publishers) {
        return publishers.stream()
                .map(publisher -> """
                              - id: %s
                                origin: %s
                                types:
                                  - %s
                        """.formatted(publisher.id(), publisher.origin(), KNOWN_TYPE))
                .collect(Collectors.joining("", "dataspace:\n  events:\n    publishers:\n", ""));
    }

    private HttpResponse<?> post(Publisher publisher) {
        Map<String, Object> event = Map.of("specversion", "1.0", "id", UUID.randomUUID().toString(),
                "source", publisher.id(), "type", KNOWN_TYPE);
        try {
            HttpRequest<String> request = HttpRequest.POST(EVENTS_PATH, jsonMapper.writeValueAsString(event))
                    .contentType(CLOUDEVENTS_JSON)
                    .bearerAuth(TestEventFixtures.token(publisher.id(), AUDIENCE));
            return exchange(client, request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String grantedOrigin(String origin) {
        HttpResponse<?> response = exchange(client, HttpRequest.OPTIONS(EVENTS_PATH).header(WebhookHeaders.REQUEST_ORIGIN, origin));
        return response.getHeaders().get(WebhookHeaders.ALLOWED_ORIGIN);
    }

    private static HttpResponse<?> exchange(BlockingHttpClient httpClient, HttpRequest<?> request) {
        try {
            return httpClient.exchange(request);
        } catch (HttpClientResponseException e) {
            return e.getResponse();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Publisher(String id, String origin) {
    }

    /**
     * Counts published refresh events.
     */
    @Requires(env = "test")
    @Requires(property = TestEventFixtures.ENABLED_PROPERTY, value = "true")
    @Singleton
    static class RefreshRecorder implements ApplicationEventListener<RefreshEvent> {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void onApplicationEvent(RefreshEvent event) {
            count.incrementAndGet();
        }

        int count() {
            return count.get();
        }
    }

    /**
     * Counts file watch events.
     */
    @Requires(env = "test")
    @Requires(property = TestEventFixtures.ENABLED_PROPERTY, value = "true")
    @Singleton
    static class FileChangeRecorder implements ApplicationEventListener<FileChangedEvent> {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void onApplicationEvent(FileChangedEvent event) {
            count.incrementAndGet();
        }

        int count() {
            return count.get();
        }
    }
}
