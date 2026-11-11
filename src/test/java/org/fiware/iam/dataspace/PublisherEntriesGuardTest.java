package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.PublisherEntryCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that publisher entries silently losing their participant.removed handling fail startup, and others only warn.
 */
class PublisherEntriesGuardTest {

    private static final String ANCHOR = "did:web:anchor.org";
    private static final String OTHER_ANCHOR = "did:web:other-anchor.org";
    private static final String PEER = "did:web:peer.org";
    private static final String PEER_TYPE = "org.fiware.dataspace.example";
    private static final String POLICY = "on-participant-removed";
    // unreachable, so the participant.removed start-up guard leaves the list UNVERIFIED instead of failing
    private static final String UNREACHABLE_TIL = "http://localhost:1";

    private final Logger logger = (Logger) LoggerFactory.getLogger(PublisherEntriesGuard.class);
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

    static Stream<Arguments> failingEntries() {
        return Stream.of(
                Arguments.of("misspelt mode, only removal publisher",
                        List.of(anchor(ANCHOR, Map.of("mode", "dataspce")), peer(Map.of())), "publishers[0] (id " + ANCHOR + ")"),
                Arguments.of("misspelt override mode, only removal publisher",
                        List.of(peer(Map.of()), anchor(ANCHOR, Map.of("overrides", List.of(Map.of("id", PEER, "mode", "ful"))))),
                        "publishers[1] (id " + ANCHOR + ")"),
                Arguments.of("non-numeric max-removals-per-run, only removal publisher",
                        List.of(anchor(ANCHOR, Map.of("max-removals-per-run", "five"))), "publishers[0] (id " + ANCHOR + ")"),
                Arguments.of("all-lower-case policy key",
                        List.of(anchor(ANCHOR, Map.of("maxremovalsperrun", 0))),
                        "publishers[0].on-participant-removed.maxremovalsperrun (id " + ANCHOR + ")"),
                Arguments.of("misspelt policy key holding a keep list",
                        List.of(peer(Map.of()), Map.of("id", ANCHOR, "origin", "cm.anchor.org",
                                "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-remove", Map.of("keep", List.of(PEER)))),
                        "publishers[1].on-participant-remove (id " + ANCHOR + ") is not a publisher setting"),
                Arguments.of("misspelt mode next to a valid removal publisher",
                        List.of(anchor(OTHER_ANCHOR, Map.of()), anchor(ANCHOR, Map.of("mode", "dataspce"))),
                        "publishers[1] (id " + ANCHOR + ")"),
                Arguments.of("policy under the camel-case key without the type",
                        List.of(Map.of("id", ANCHOR, "origin", "cm.anchor.org", "types", List.of(PEER_TYPE),
                                "onParticipantRemoved", Map.of("mode", "dataspce"))), "publishers[0] (id " + ANCHOR + ")"),
                Arguments.of("repeated removal publisher with different policies",
                        List.of(anchor(ANCHOR, Map.of()), anchor(ANCHOR, Map.of("keep", List.of(PEER)))),
                        "publishers[0] and dataspace.events.publishers[1] both publish participant removals as " + ANCHOR),
                Arguments.of("misspelt policy key of a removal publisher",
                        List.of(peer(Map.of()), anchor(ANCHOR, Map.of("mod", "dataspace"))),
                        "publishers[1].on-participant-removed.mod (id " + ANCHOR + ") is not an on-participant-removed setting"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingEntries")
    void failsStartup(String description, List<Map<String, Object>> publishers, String expected) {
        BeanInstantiationException exception = assertThrows(BeanInstantiationException.class, () -> run(publishers).close());
        ConfigurationException cause = assertInstanceOf(ConfigurationException.class, rootCause(exception));
        assertTrue(cause.getMessage().contains(expected), cause.getMessage());
    }

    static Stream<Arguments> startingEntries() {
        return Stream.of(
                Arguments.of("valid entries", List.of(anchor(ANCHOR, Map.of("mode", "report")), peer(Map.of())), null),
                Arguments.of("peer dropped for another field",
                        List.of(anchor(ANCHOR, Map.of()), peer(Map.of("scopes", Map.of("nested", Map.of("key", "value"))))),
                        "publishers[1] (id " + PEER + ") has an invalid value"),
                Arguments.of("non-default policy without the type",
                        List.of(anchor(ANCHOR, Map.of()), peer(Map.of(POLICY, Map.of("mode", "full")))),
                        "on-participant-removed of " + PEER + " is ignored"),
                Arguments.of("unknown publisher key",
                        List.of(anchor(ANCHOR, Map.of()), peer(Map.of("client_id", PEER))),
                        "publishers[1].client_id (id " + PEER + ") is not a publisher setting and is ignored"),
                Arguments.of("default policy without the type",
                        List.of(anchor(ANCHOR, Map.of()), peer(Map.of(POLICY, Map.of("mode", "report")))), null),
                Arguments.of("repeated removal publisher with identical policies",
                        List.of(anchor(ANCHOR, Map.of("keep", List.of(PEER))), anchor(ANCHOR, Map.of("keep", List.of(PEER)))),
                        null),
                Arguments.of("repeated removal publisher with default policies",
                        List.of(anchor(ANCHOR, Map.of()), anchor(ANCHOR, Map.of())), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startingEntries")
    void starts(String description, List<Map<String, Object>> publishers, String expectedWarning) {
        try (ApplicationContext context = run(publishers)) {
            assertTrue(context.isRunning());
        }
        List<String> warnings = logged.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        if (expectedWarning == null) {
            assertTrue(warnings.isEmpty(), warnings.toString());
            return;
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(expectedWarning), warnings.get(0));
        assertFalse(logged.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    private static Map<String, Object> anchor(String id, Map<String, Object> policy) {
        return Map.of("id", id, "origin", "cm.anchor.org", "types", List.of(PARTICIPANT_REMOVED_TYPE), POLICY, policy);
    }

    private static Map<String, Object> peer(Map<String, Object> extra) {
        Map<String, Object> peer = new HashMap<>(Map.of("id", PEER, "origin", "cm.peer.org", "types", List.of(PEER_TYPE)));
        peer.putAll(extra);
        return peer;
    }

    private static ApplicationContext run(List<Map<String, Object>> publishers) {
        Map<String, Object> config = new HashMap<>(Map.of(
                "notification.enabled", false,
                "general.enable-rainbow", false,
                "micronaut.server.port", 8080,
                "endpoints.all.port", 9090,
                "micronaut.http.services.trusted-issuers-list.url", UNREACHABLE_TIL,
                "dataspace.events.listener.enabled", true,
                PublisherEntryCheck.PUBLISHERS_KEY, publishers));
        // without the test application.yaml, which enables further features
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .propertySources(PropertySource.of("test", config))
                .start();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
