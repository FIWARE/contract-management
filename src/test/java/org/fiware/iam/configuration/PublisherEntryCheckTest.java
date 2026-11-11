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
    private static final String OTHER_KEPT = "did:web:other-kept.org";
    private static final int MAX_REMOVALS = 3;
    private static final String REGISTRY_URL = "http://registry.anchor.org";
    private static final String COMMA_TYPES = PEER_TYPE + ", " + PARTICIPANT_REMOVED_TYPE;
    private static final String COMMA_DIDS = KEPT + "," + PEER;

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
                Arguments.of("first of a repeated id with string types dropped, a comma string binds as one type",
                        List.of(Map.of("id", ANCHOR, "types", PARTICIPANT_REMOVED_TYPE, "on-participant-removed",
                                        Map.of("mode", "dataspce")),
                                Map.of("id", ANCHOR, "types", COMMA_TYPES)),
                        List.of(publisher(ANCHOR, COMMA_TYPES)),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)),
                                new Finding(Severity.FATAL, commaSeparated("[1].types", ANCHOR, COMMA_TYPES)))),
                Arguments.of("first of a repeated id dropped, told apart by an override mode",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                        Map.of("overrides", List.of(Map.of("id", KEPT, "mode", "ful")))),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                        Map.of("overrides", List.of(Map.of("id", KEPT, "mode", "full"))))),
                        List.of(overriding(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE))),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))),
                Arguments.of("repeated id differing outside the policy names every plausible entry",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "scopes", Map.of("bad", 1)),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)),
                        List.of(new Finding(Severity.FATAL, dropped("one of %s[0] or %s[1]".formatted(
                                PublisherEntryCheck.PUBLISHERS_KEY, PublisherEntryCheck.PUBLISHERS_KEY), ANCHOR, true)))),
                Arguments.of("publisher bound from another source does not hide a dropped entry at its index",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("mode", "dataspce"))),
                        List.of(publisher(PEER, PARTICIPANT_REMOVED_TYPE)),
                        List.of(new Finding(Severity.FATAL, dropped(0, ANCHOR, true)))));
    }

    static Stream<Arguments> rawIndices() {
        return Stream.of(
                Arguments.of("earlier entry dropped",
                        List.of(Map.of("id", PEER, "types", List.of(PEER_TYPE)),
                                Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE)), new int[]{1}),
                Arguments.of("publisher without an unconverted counterpart keeps its bound index",
                        List.of(Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE))),
                        List.of(publisher(ANCHOR, PARTICIPANT_REMOVED_TYPE), publisher(PEER, PEER_TYPE)), new int[]{0, 1}),
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

    static Stream<Arguments> unknownKeys() {
        return Stream.of(
                Arguments.of("misspelt policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("mod", "dataspace")),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("mod", ANCHOR)))),
                Arguments.of("snake-case policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "onParticipantRemoved",
                                Map.of("registry_url", REGISTRY_URL)),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("registry_url", ANCHOR)))),
                Arguments.of("flattened misspelt policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-removed.max-removal-per-run", MAX_REMOVALS),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("max-removal-per-run", ANCHOR)))),
                Arguments.of("misspelt policy key of a peer entry",
                        Map.of("id", PEER, "types", List.of(PEER_TYPE), "on-participant-removed", Map.of("mod", "full")),
                        List.of(new Finding(Severity.WARN, unknownPolicyKey("mod", PEER)))),
                Arguments.of("all-lower-case policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("maxremovalsperrun", MAX_REMOVALS)),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("maxremovalsperrun", ANCHOR)))),
                Arguments.of("upper-case kebab-case policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("REGISTRY-URL", REGISTRY_URL)),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("REGISTRY-URL", ANCHOR)))),
                Arguments.of("miscased camel-case policy key of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("registryURL", REGISTRY_URL)),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("registryURL", ANCHOR)))),
                Arguments.of("misspelt policy of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-remove", Map.of("keep", List.of(KEPT))),
                        List.of(new Finding(Severity.FATAL, misspeltPolicy("on-participant-remove", ANCHOR)))),
                Arguments.of("flattened misspelt policy of a removal entry",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-remove.keep[0]", KEPT, "on-participant-remove.keep[1]", OTHER_KEPT,
                                "on-participant-remove.mode", "full"),
                        List.of(new Finding(Severity.FATAL, misspeltPolicy("on-participant-remove", ANCHOR)))),
                Arguments.of("flattened misspelt policy of a peer entry",
                        Map.of("id", PEER, "types", List.of(PEER_TYPE),
                                "on-participant-remove.keep[0]", KEPT, "on-participant-remove.keep[1]", OTHER_KEPT),
                        List.of(new Finding(Severity.WARN, unknownPublisherKey("on-participant-remove", PEER)))),
                Arguments.of("flattened misspelt list key inside the policy",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-removed.kep[0]", KEPT, "on-participant-removed.kep[1]", OTHER_KEPT),
                        List.of(new Finding(Severity.FATAL, unknownPolicyKey("kep", ANCHOR)))),
                Arguments.of("misspelt policy of a peer entry",
                        Map.of("id", PEER, "types", List.of(PEER_TYPE), "on-participant-remove", Map.of("mode", "full")),
                        List.of(new Finding(Severity.WARN, unknownPublisherKey("on-participant-remove", PEER)))),
                Arguments.of("misspelt policy key named like the policy setting only the mode",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "participant-removed", Map.of("mode", "full")),
                        List.of(new Finding(Severity.FATAL, misspeltPolicy("participant-removed", ANCHOR)))),
                Arguments.of("unrelated map with a generic mode key",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "labels", Map.of("mode", "prod")),
                        List.of(new Finding(Severity.WARN, unknownPublisherKey("labels", ANCHOR)))),
                Arguments.of("unrelated flattened key with a generic mode key",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "labels.mode", "prod"),
                        List.of(new Finding(Severity.WARN, unknownPublisherKey("labels", ANCHOR)))),
                Arguments.of("unknown publisher key without policy settings",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "extra", Map.of("key", "value")),
                        List.of(new Finding(Severity.WARN, unknownPublisherKey("extra", ANCHOR)))),
                Arguments.of("camel-case, kebab-case and flattened known keys",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "subscriptionsUrl", REGISTRY_URL,
                                "client-id", PEER, "onParticipantRemoved.overrides[0].mode", "full",
                                "onParticipantRemoved.keep[0]", KEPT, "On-Participant-Removed",
                                Map.of("registryUrl", REGISTRY_URL, "max-adoptions-per-run", MAX_REMOVALS,
                                        "AdoptUnattributed", true, "max-removalsPerRun", MAX_REMOVALS)),
                        List.of()));
    }

    static Stream<Arguments> commaSeparatedValues() {
        return Stream.of(
                Arguments.of("types of a removal publisher as one string",
                        Map.of("id", ANCHOR, "types", COMMA_TYPES),
                        List.of(new Finding(Severity.FATAL, commaSeparated("[0].types", ANCHOR, COMMA_TYPES)))),
                Arguments.of("types of a peer as one string",
                        Map.of("id", PEER, "types", PEER_TYPE + "," + PEER_TYPE),
                        List.of(new Finding(Severity.WARN, commaSeparated("[0].types", PEER, PEER_TYPE + "," + PEER_TYPE)))),
                Arguments.of("keep as one string",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-removed", Map.of("keep", COMMA_DIDS)),
                        List.of(new Finding(Severity.FATAL, commaSeparated("[0].on-participant-removed.keep", ANCHOR, COMMA_DIDS)))),
                Arguments.of("flattened keep element as one string",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-removed.keep[0]", COMMA_DIDS),
                        List.of(new Finding(Severity.FATAL, commaSeparated("[0].on-participant-removed.keep[0]", ANCHOR, COMMA_DIDS)))),
                Arguments.of("override id as one string",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("overrides", List.of(Map.of("id", COMMA_DIDS, "mode", "full")))),
                        List.of(new Finding(Severity.FATAL,
                                commaSeparated("[0].on-participant-removed.overrides[0].id", ANCHOR, COMMA_DIDS)))),
                Arguments.of("flattened override id as one string",
                        Map.of("id", ANCHOR, "types", List.of(PARTICIPANT_REMOVED_TYPE),
                                "on-participant-removed.overrides[0].id", COMMA_DIDS),
                        List.of(new Finding(Severity.FATAL,
                                commaSeparated("[0].on-participant-removed.overrides[0].id", ANCHOR, COMMA_DIDS)))),
                Arguments.of("keep of a policy without the type as one string",
                        Map.of("id", PEER, "types", List.of(PEER_TYPE), "on-participant-removed", Map.of("keep", COMMA_DIDS)),
                        List.of(new Finding(Severity.WARN, commaSeparated("[0].on-participant-removed.keep", PEER, COMMA_DIDS)))),
                Arguments.of("lists without commas",
                        Map.of("id", ANCHOR, "types", List.of(PEER_TYPE, PARTICIPANT_REMOVED_TYPE), "on-participant-removed",
                                Map.of("keep", List.of(KEPT, PEER), "overrides", List.of(Map.of("id", KEPT, "mode", "full")))),
                        List.of()));
    }

    // the bound publisher mirrors the binding, which keeps a string as one element
    @ParameterizedTest(name = "{0}")
    @MethodSource("commaSeparatedValues")
    void findsCommaSeparatedValues(String description, Map<String, Object> entry, List<Finding> expected) {
        Object types = entry.get("types");
        Publisher publisher = types instanceof List<?> list
                ? publisher(idOf(entry), list.stream().map(Object::toString).toArray(String[]::new))
                : publisher(idOf(entry), types.toString());
        assertEquals(expected, PublisherEntryCheck.check(List.of(entry), List.of(publisher)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownKeys")
    void findsUnknownKeys(String description, Map<String, Object> entry, List<Finding> expected) {
        assertEquals(expected, PublisherEntryCheck.check(List.of(entry), List.of(publisher(idOf(entry), typeOf(entry)))));
    }

    private static String idOf(Map<String, Object> entry) {
        return entry.get("id").toString();
    }

    private static String typeOf(Map<String, Object> entry) {
        return ((List<?>) entry.get("types")).get(0).toString();
    }

    private static String unknownPolicyKey(String key, String id) {
        return "%s[0].%s.%s (id %s) is not an %s setting and is ignored, so its default applies.".formatted(
                PublisherEntryCheck.PUBLISHERS_KEY, PublisherEntryCheck.POLICY_KEY, key, id, PublisherEntryCheck.POLICY_KEY);
    }

    private static String misspeltPolicy(String key, String id) {
        return ("%s[0].%s (id %s) is not a publisher setting and is ignored, but holds %s settings: the whole policy, keep "
                + "list included, would fall back to its defaults.").formatted(PublisherEntryCheck.PUBLISHERS_KEY, key, id,
                PublisherEntryCheck.POLICY_KEY);
    }

    private static String commaSeparated(String key, String id, String value) {
        return ("%s%s (id %s) is the single value \"%s\": a comma-separated string is not split into a list, so it "
                + "matches nothing. Give one list item per value.").formatted(PublisherEntryCheck.PUBLISHERS_KEY, key, id, value);
    }

    private static String unknownPublisherKey(String key, String id) {
        return "%s[0].%s (id %s) is not a publisher setting and is ignored.".formatted(PublisherEntryCheck.PUBLISHERS_KEY,
                key, id);
    }

    private static String dropped(int index, String id, boolean removal) {
        return dropped("%s[%s]".formatted(PublisherEntryCheck.PUBLISHERS_KEY, index), id, removal);
    }

    private static String dropped(String keys, String id, boolean removal) {
        String start = "%s (id %s) has an invalid value and is dropped; ".formatted(keys, id);
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

    private static Publisher overriding(Publisher publisher) {
        DataSpaceEventsProperties.ModeOverride override = new DataSpaceEventsProperties.ModeOverride();
        override.setId(KEPT);
        override.setMode(RemovalMode.FULL);
        publisher.getOnParticipantRemoved().setOverrides(List.of(override));
        return publisher;
    }

    private static Publisher capped(Publisher publisher) {
        publisher.getOnParticipantRemoved().setMaxRemovalsPerRun(MAX_REMOVALS);
        return publisher;
    }
}
