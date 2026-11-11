package org.fiware.iam.configuration;

import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;
import org.fiware.iam.configuration.PublisherEntryCheck.Finding;
import org.fiware.iam.configuration.PublisherEntryCheck.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the publisher entry findings on unconverted entries in the shapes the property sources produce.
 */
class PublisherEntryCheckTest {

    private static final String ANCHOR = "did:web:anchor.org";
    private static final String PEER = "did:web:peer.org";
    private static final String PEER_TYPE = "org.fiware.dataspace.example";
    private static final String KEPT = "did:web:kept.org";
    private static final int MAX_REMOVALS = 3;

    static Stream<Arguments> entries() {
        return Stream.of(
                Arguments.of("nothing dropped",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)), List.of()),
                Arguments.of("unset list", null, List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)), List.of()),
                Arguments.of("flattened policy key of a dropped peer-typed entry",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PEER_TYPE), "on-participant-removed.mode", "dataspce")),
                        List.of(), List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))),
                Arguments.of("dropped peer",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE)),
                                Map.of("id", PEER, "types", List.of(PEER_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)),
                        List.of(new Finding(Severity.WARN, dropped(1, PEER, false)))),
                Arguments.of("first of a repeated id dropped, matched by its types",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                        Map.of("mode", "dataspce")),
                                Map.of("id", ANCHOR, "types", List.of(PEER_TYPE))),
                        List.of(publisher(ANCHOR, PEER_TYPE)),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))),
                Arguments.of("first of a repeated id with equal types dropped, matched by its policy",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                        Map.of("mode", "dataspce")),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                        Map.of("keep", List.of(KEPT)))),
                        List.of(keeping(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE))),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))),
                Arguments.of("first of a repeated id with equal types dropped, matched by a flattened policy value",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                        "on-participant-removed.max-removals-per-run", "many"),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                        "on-participant-removed.max-removals-per-run", MAX_REMOVALS)),
                        List.of(capped(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE))),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))),
                Arguments.of("first of a repeated id with comma-separated types dropped",
                        List.of(Map.of("id", ANCHOR, "types", PARTICIPANT_REMOVED_TYPE, "on-participant-removed",
                                        Map.of("mode", "dataspce")),
                                Map.of("id", ANCHOR, "types", PEER_TYPE + ", " + PARTICIPANT_REMOVED_TYPE)),
                        List.of(publisher(ANCHOR, PEER_TYPE, PARTICIPANT_REMOVED_TYPE)),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))));
    }

    static Stream<Arguments> rawIndices() {
        return Stream.of(
                Arguments.of("earlier entry dropped",
                        List.of(Map.of("id", PEER, "types", List.of(PEER_TYPE)),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)), new int[]{1}),
                Arguments.of("no unconverted entries", null,
                        List.of(publisher(PEER, PEER_TYPE), publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)), new int[]{0, 1}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rawIndices")
    void mapsBoundEntriesToTheirConfiguredIndex(String description, List<?> raw, List<Publisher> bound, int[] expected) {
        assertArrayEquals(expected, PublisherEntryCheck.rawIndices(raw, bound));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void findsDroppedEntries(String description, List<?> raw, List<Publisher> bound, List<Finding> expected) {
        assertEquals(expected, PublisherEntryCheck.check(raw, bound));
    }

    private static String dropped(int index, String id, boolean removal) {
        String start = "%s[%s] (id %s) has an invalid value and is dropped; ".formatted(PublisherEntryCheck.PUBLISHERS_KEY, index, id);
        return removal
                ? start + "an invalid value drops the whole publisher entry, so it would neither be authorized for its event "
                + "types nor handle participant removals."
                : start + "it is not authorized for any event type.";
    }

    private static Publisher publisher(String id, String... types) {
        Publisher publisher = new Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(types));
        return publisher;
    }

    private static Publisher keeping(Publisher publisher) {
        publisher.getOnParticipantRemoved().setKeep(List.of(KEPT));
        return publisher;
    }

    private static Publisher capped(Publisher publisher) {
        publisher.getOnParticipantRemoved().setMaxRemovalsPerRun(MAX_REMOVALS);
        return publisher;
    }
}
