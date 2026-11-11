package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.health.HealthStatus;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.ModeOverride;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.fiware.iam.til.TilCapability.State;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that the participant.removed handling starts only with a valid policy and a list new enough for its writes.
 */
class ParticipantRemovedStartupGuardTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final String REGISTRY_URL = "http://registry.p1.org";
    private static final String DATASPACE_ID = "my-dataspace";
    private static final int NOT_FOUND = 404;
    // without unconverted entries, messages fall back to the bound index
    private static final List<?> NO_RAW_ENTRIES = List.of();

    private final Logger logger = (Logger) LoggerFactory.getLogger(ParticipantRemovedStartupGuard.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logged);
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of("override with blank id", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setOverrides(List.of(override(" ", RemovalMode.REPORT)))), "overrides[0].id"),
                Arguments.of("override without mode", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setOverrides(List.of(override(X, null)))), "overrides[0].mode"),
                Arguments.of("relative registry-url", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setRegistryUrl("registry.p1.org")), "registry-url must be an absolute http(s) URL"),
                Arguments.of("ftp registry-url", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setRegistryUrl("ftp://registry.p1.org")), "registry-url must be an absolute http(s) URL"),
                Arguments.of("max-removals-per-run -1", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setMaxRemovalsPerRun(-1)), "publishers[0].on-participant-removed.max-removals-per-run"),
                Arguments.of("max-adoptions-per-run -1", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setMaxAdoptionsPerRun(-1)), "publishers[0].on-participant-removed.max-adoptions-per-run"),
                Arguments.of("page-size 0", settings(properties -> properties.getParticipantRemoved().setPageSize(0)),
                        ParticipantRemovedStartupGuard.PAGE_SIZE_KEY),
                Arguments.of("page-size 101", settings(properties -> properties.getParticipantRemoved().setPageSize(101)),
                        ParticipantRemovedStartupGuard.PAGE_SIZE_KEY),
                Arguments.of("max-pages 0", settings(properties -> properties.getParticipantRemoved().setMaxPages(0)),
                        ParticipantRemovedStartupGuard.MAX_PAGES_KEY),
                Arguments.of("interval PT0S", settings(properties -> properties.getParticipantRemoved().getReconcile()
                        .setInterval(Duration.ZERO)), ParticipantRemovedStartupGuard.INTERVAL_KEY),
                Arguments.of("initial-delay negative", settings(properties -> properties.getParticipantRemoved().getReconcile()
                        .setInitialDelay(Duration.ofMinutes(-1))), ParticipantRemovedStartupGuard.INITIAL_DELAY_KEY),
                Arguments.of("writing mode without registry-url", properties(publisher(P1, RemovalMode.DATASPACE, null)),
                        ParticipantRemovedStartupGuard.NO_REGISTRY_URL_CONSEQUENCE),
                Arguments.of("writing override without registry-url", configured(publisher -> {
                    publisher.getOnParticipantRemoved().setRegistryUrl(null);
                    publisher.getOnParticipantRemoved().setOverrides(List.of(override(X, RemovalMode.FULL)));
                }), ParticipantRemovedStartupGuard.NO_REGISTRY_URL_CONSEQUENCE),
                Arguments.of("adoption without registry-url", adoption(null, DATASPACE_ID),
                        "adopt-unattributed requires registry-url: adoption lists " + P1 + "'s participants from it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void failsForAnInvalidPolicy(String description, DataSpaceEventsProperties properties, String expected) {
        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> new ParticipantRemovedStartupGuard(properties, capabilities(State.SUPPORTED), NO_RAW_ENTRIES));
        assertTrue(exception.getMessage().contains(expected), exception.getMessage());
    }

    static Stream<Arguments> validConfigurations() {
        return Stream.of(
                Arguments.of("report without registry-url", properties(publisher(P1, RemovalMode.REPORT, null)), false),
                Arguments.of("writing mode with registry-url", properties(publisher(P1, RemovalMode.FULL, REGISTRY_URL)), false),
                Arguments.of("max caps 0", configured(publisher -> {
                    publisher.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
                    publisher.getOnParticipantRemoved().setMaxAdoptionsPerRun(0);
                }), false),
                Arguments.of("adoption refused for two publishers", twoPublishersAdopting(), true),
                Arguments.of("adoption refused without dataspace-id", adoption(null, null), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validConfigurations")
    void startsForAValidPolicy(String description, DataSpaceEventsProperties properties, boolean refusalWarned) {
        new ParticipantRemovedStartupGuard(properties, capabilities(State.SUPPORTED), NO_RAW_ENTRIES);

        assertEquals(refusalWarned, logs(Level.WARN).stream()
                .anyMatch(event -> event.getFormattedMessage().contains("adopt-unattributed")));
    }

    @Test
    void namesTheConfiguredIndexWhenAnEarlierEntryWasDropped() {
        DataSpaceEventsProperties properties = configured(publisher -> publisher.getOnParticipantRemoved()
                .setOverrides(List.of(override(X, null))));
        // the peer entry failed conversion, so the anchor is bound at 0 but configured at 1
        List<?> raw = List.of(Map.of("id", P2, "types", List.of("org.fiware.dataspace.example")),
                Map.of("id", P1, "types", List.of(PARTICIPANT_REMOVED_TYPE)));

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> new ParticipantRemovedStartupGuard(properties, capabilities(State.SUPPORTED), raw));

        assertTrue(exception.getMessage().contains("publishers[1].on-participant-removed.overrides[0].mode"),
                exception.getMessage());
    }

    @Test
    void warnsForAKeptIdentifierThatAnotherPublisherOverrides() {
        Publisher keeping = publisher(P1, RemovalMode.REPORT, null);
        keeping.getOnParticipantRemoved().setKeep(List.of(X));
        Publisher overriding = publisher(P2, RemovalMode.REPORT, REGISTRY_URL);
        overriding.getOnParticipantRemoved().setOverrides(List.of(override(X, RemovalMode.FULL)));

        new ParticipantRemovedStartupGuard(properties(keeping, overriding), capabilities(State.SUPPORTED),
                NO_RAW_ENTRIES);

        assertEquals(1, logs(Level.WARN).size());
        assertTrue(logs(Level.WARN).get(0).getFormattedMessage().contains(X + " is on a keep list and has an override at " + P2));
    }

    static Stream<Arguments> capabilityStates() {
        List<Arguments> arguments = new ArrayList<>();
        Map<String, DataSpaceEventsProperties> configurations = Map.of(
                "report only", properties(publisher(P1, RemovalMode.REPORT, REGISTRY_URL)),
                "writing mode", properties(publisher(P1, RemovalMode.DATASPACE, REGISTRY_URL)),
                "writing override", configured(publisher -> publisher.getOnParticipantRemoved()
                        .setOverrides(List.of(override(X, RemovalMode.GRANTED)))),
                "allowed adoption", adoption(REGISTRY_URL, DATASPACE_ID),
                "refused adoption", twoPublishersAdopting());
        Map<String, Boolean> writes = Map.of("report only", false, "writing mode", true, "writing override", true,
                "allowed adoption", true, "refused adoption", false);
        for (State state : State.values()) {
            configurations.forEach((name, properties) -> arguments.add(Arguments.of(state, name, properties,
                    state == State.TOO_OLD && writes.get(name))));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("capabilityStates")
    void failsOnlyForATooOldListThatAWriteNeeds(State state, String configuration, DataSpaceEventsProperties properties,
                                                boolean fails) {
        TilCapabilities capabilities = capabilities(state);
        if (fails) {
            ConfigurationException exception = assertThrows(ConfigurationException.class,
                    () -> new ParticipantRemovedStartupGuard(properties, capabilities, NO_RAW_ENTRIES));
            assertTrue(exception.getMessage().contains("scopes-endpoint (TIL-2)"), exception.getMessage());
            assertTrue(exception.getMessage().contains(configuration.equals("allowed adoption") ? "adopt-unattributed"
                    : P1), exception.getMessage());
            return;
        }
        new ParticipantRemovedStartupGuard(properties, capabilities, NO_RAW_ENTRIES);

        Level expected = switch (state) {
            case SUPPORTED -> null;
            case TOO_OLD, UNVERIFIED -> Level.WARN;
            case PENDING -> Level.INFO;
        };
        if (expected != null) {
            assertTrue(logs(expected).stream().anyMatch(event -> event.getFormattedMessage().contains("scopes-endpoint")),
                    "expected a " + expected + " naming the capability");
        }
        // TilCapabilities logs the ERROR for a too-old list, the guard only its consequence
        assertTrue(logs(Level.ERROR).isEmpty());
    }

    @Test
    void namesTheTicketOfEveryTooOldCapabilityThatAdoptionNeeds() {
        TilCapabilities capabilities = mock(TilCapabilities.class);
        Map<TilCapability, State> states = new EnumMap<>(TilCapability.class);
        states.put(TilCapability.TYPED_SCOPES, State.TOO_OLD);
        states.put(TilCapability.SCOPES_ENDPOINT, State.SUPPORTED);
        states.put(TilCapability.UNSCOPED, State.TOO_OLD);
        when(capabilities.probeRequired()).thenReturn(Mono.just(states));

        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> new ParticipantRemovedStartupGuard(adoption(REGISTRY_URL, DATASPACE_ID), capabilities, NO_RAW_ENTRIES));

        assertTrue(exception.getMessage().contains("typed-scopes (TIL-1)"), exception.getMessage());
        assertTrue(exception.getMessage().contains("unscoped (TIL-3)"), exception.getMessage());
        assertFalse(exception.getMessage().contains("TIL-2"), exception.getMessage());
    }

    @Test
    void aHangingListDelaysStartupByOneProbeTimeoutAtMost() {
        TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
        when(adapter.probeTypedScopes()).thenReturn(Mono.never());
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.never());
        DataSpaceEventsProperties properties = properties(publisher(P1, RemovalMode.FULL, REGISTRY_URL));
        TilCapabilities capabilities = new TilCapabilities(adapter, properties, new MutableClock(Instant.EPOCH));

        long started = System.nanoTime();
        new ParticipantRemovedStartupGuard(properties, capabilities, NO_RAW_ENTRIES);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(took.compareTo(TilCapabilities.PROBE_TIMEOUT.multipliedBy(2)) < 0, "took " + took);
        assertEquals(State.UNVERIFIED, capabilities.state().get(TilCapability.TYPED_SCOPES));
        assertEquals(State.UNVERIFIED, capabilities.state().get(TilCapability.SCOPES_ENDPOINT));
    }

    @Test
    void anUnreachableListDegradesAndBlocksWritesUntilALaterProbeSucceeds() {
        TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
        when(adapter.probeTypedScopes()).thenReturn(Mono.error(new ConnectException("refused")));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.error(new ConnectException("refused")));
        DataSpaceEventsProperties properties = properties(publisher(P1, RemovalMode.DATASPACE, REGISTRY_URL));
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TilCapabilities capabilities = new TilCapabilities(adapter, properties, clock);
        ParticipantReconciler reconciler = mock(ParticipantReconciler.class);
        ParticipantRemovedHealthIndicator health = new ParticipantRemovedHealthIndicator(capabilities, reconciler);

        new ParticipantRemovedStartupGuard(properties, capabilities, NO_RAW_ENTRIES);

        assertEquals(DataSpaceHealth.DEGRADED, health.result().getStatus());
        assertFalse(health.result().getStatus().equals(HealthStatus.DOWN));
        TrustedIssuersException blocked = assertThrows(TrustedIssuersException.class,
                () -> capabilities.requireSupported().block());
        assertEquals(FailureReason.TIL_CAPABILITY_UNVERIFIED, blocked.getReason());

        when(adapter.probeTypedScopes()).thenReturn(Mono.just(State.SUPPORTED));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(State.SUPPORTED));
        clock.advance(TilCapabilities.PROBE_RETRY_INTERVAL);
        capabilities.requireSupported().block();

        assertEquals(HealthStatus.UP, health.result().getStatus());
    }

    static Stream<Arguments> contextConfigurations() {
        return Stream.of(
                Arguments.of("dataspace mode", Map.of("mode", "dataspace", "registry-url", REGISTRY_URL), true, true),
                Arguments.of("allowed adoption", Map.of("adopt-unattributed", "true", "registry-url", REGISTRY_URL), true,
                        true),
                Arguments.of("report only", Map.of("mode", "report"), true, false),
                Arguments.of("adoption refused without dataspace-id", Map.of("adopt-unattributed", "true"), false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contextConfigurations")
    void startupWithATooOldList(String description, Map<String, String> policy, boolean withDataspaceId, boolean fails)
            throws IOException {
        HttpServer til = tooOldList();
        try {
            Map<String, Object> config = contextConfig(til, policy, withDataspaceId);
            if (fails) {
                BeanInstantiationException exception = assertThrows(BeanInstantiationException.class,
                        () -> ApplicationContext.builder().enableDefaultPropertySources(false).properties(config).start().close());
                ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
                assertTrue(cause.getMessage().contains("too old"), cause.getMessage());
                return;
            }
            try (ApplicationContext context = ApplicationContext.builder().enableDefaultPropertySources(false)
                    .properties(config).start()) {
                assertTrue(context.isRunning());
                assertTrue(context.containsBean(ParticipantRemovedStartupGuard.class));
            }
        } finally {
            til.stop(0);
        }
    }

    private static Map<String, Object> contextConfig(HttpServer til, Map<String, String> policy, boolean withDataspaceId) {
        Map<String, Object> config = new HashMap<>(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "micronaut.server.port", 8080,
                "endpoints.all.port", 9090,
                "micronaut.http.services.trusted-issuers-list.url", "http://localhost:" + til.getAddress().getPort(),
                "micronaut.http.services.trusted-issuers-list.path", "/",
                "dataspace.events.listener.enabled", "true",
                "dataspace.events.publishers[0].id", P1,
                "dataspace.events.publishers[0].origin", "cm.p1.org",
                "dataspace.events.publishers[0].types[0]", PARTICIPANT_REMOVED_TYPE));
        if (withDataspaceId) {
            config.put("dataspace.events.dataspace-id", DATASPACE_ID);
        }
        policy.forEach((key, value) -> config.put("dataspace.events.publishers[0].on-participant-removed." + key, value));
        return config;
    }

    // a list without TIL-1 and TIL-2 answers every probe with a bare 404
    private static HttpServer tooOldList() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(NOT_FOUND, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static TilCapabilities capabilities(State state) {
        TilCapabilities capabilities = mock(TilCapabilities.class);
        Map<TilCapability, State> states = new EnumMap<>(TilCapability.class);
        // PENDING exists for the scopes endpoint only
        states.put(TilCapability.TYPED_SCOPES, state == State.PENDING ? State.SUPPORTED : state);
        states.put(TilCapability.SCOPES_ENDPOINT, state);
        when(capabilities.probeRequired()).thenReturn(Mono.just(states));
        return capabilities;
    }

    private static DataSpaceEventsProperties properties(Publisher... publishers) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getListener().setEnabled(true);
        properties.setPublishers(new ArrayList<>(Arrays.asList(publishers)));
        return properties;
    }

    private static DataSpaceEventsProperties configured(Consumer<Publisher> change) {
        Publisher publisher = publisher(P1, RemovalMode.REPORT, REGISTRY_URL);
        change.accept(publisher);
        return properties(publisher);
    }

    private static DataSpaceEventsProperties settings(Consumer<DataSpaceEventsProperties> change) {
        DataSpaceEventsProperties properties = properties(publisher(P1, RemovalMode.REPORT, REGISTRY_URL));
        change.accept(properties);
        return properties;
    }

    private static DataSpaceEventsProperties adoption(String registryUrl, String dataspaceId) {
        Publisher publisher = publisher(P1, RemovalMode.REPORT, registryUrl);
        publisher.getOnParticipantRemoved().setAdoptUnattributed(true);
        DataSpaceEventsProperties properties = properties(publisher);
        properties.setDataspaceId(dataspaceId);
        return properties;
    }

    private static DataSpaceEventsProperties twoPublishersAdopting() {
        Publisher adopting = publisher(P1, RemovalMode.REPORT, null);
        adopting.getOnParticipantRemoved().setAdoptUnattributed(true);
        DataSpaceEventsProperties properties = properties(adopting, publisher(P2, RemovalMode.REPORT, null));
        properties.setDataspaceId(DATASPACE_ID);
        return properties;
    }

    private static Publisher publisher(String id, RemovalMode mode, String registryUrl) {
        Publisher publisher = new Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(PARTICIPANT_REMOVED_TYPE));
        publisher.getOnParticipantRemoved().setMode(mode);
        publisher.getOnParticipantRemoved().setRegistryUrl(registryUrl);
        return publisher;
    }

    private static ModeOverride override(String id, RemovalMode mode) {
        ModeOverride override = new ModeOverride();
        override.setId(id);
        override.setMode(mode);
        return override;
    }

    private List<ILoggingEvent> logs(Level level) {
        return logged.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
