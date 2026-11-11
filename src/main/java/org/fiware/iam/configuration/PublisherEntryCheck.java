package org.fiware.iam.configuration;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties.OnParticipantRemoved;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;

/**
 * Finds publisher entries whose participant.removed handling differs from what the configuration reads like: entries
 * dropped by a failed conversion, policies ignored for lack of the type, and conflicting policies of one repeated id.
 * Shared by the start-up guard and the sweep, which re-checks after a refresh.
 */
public final class PublisherEntryCheck {

    public static final String PUBLISHERS_KEY = "dataspace.events.publishers";
    public static final String POLICY_KEY = "on-participant-removed";
    static final String POLICY_KEY_CAMEL_CASE = "onParticipantRemoved";
    static final String ID_KEY = "id";
    static final String TYPES_KEY = "types";
    private static final String LIST_SEPARATOR = ",";
    private static final String MODE_FIELD = "mode";
    private static final String MAX_REMOVALS_FIELD = "maxremovalsperrun";
    private static final String MAX_ADOPTIONS_FIELD = "maxadoptionsperrun";
    private static final String ADOPT_FIELD = "adoptunattributed";
    private static final String REGISTRY_URL_FIELD = "registryurl";

    private PublisherEntryCheck() {
    }

    /**
     * A finding; FATAL ones fail start-up and are logged as ERROR after a refresh, WARN ones are only logged.
     */
    public record Finding(Severity severity, String message) {
    }

    /**
     * How serious a finding is.
     */
    public enum Severity {
        FATAL,
        WARN
    }

    /**
     * Checks the unconverted entries of {@value #PUBLISHERS_KEY} (null if unset) against the bound publishers.
     */
    public static List<Finding> check(@Nullable List<?> rawEntries, List<Publisher> publishers) {
        List<Finding> findings = new ArrayList<>();
        List<?> raw = rawEntries == null ? List.of() : rawEntries;
        int[] rawIndexOf = rawIndices(raw, publishers);
        checkDropped(raw, rawIndexOf, findings);
        checkInertPolicies(publishers, rawIndexOf, findings);
        checkRepeatedIds(publishers, rawIndexOf, findings);
        return findings;
    }

    /**
     * Index of each bound publisher in the unconverted entries, i.e. its configuration key; the bound index where none matches.
     */
    public static int[] rawIndices(@Nullable List<?> rawEntries, List<Publisher> publishers) {
        List<?> raw = rawEntries == null ? List.of() : rawEntries;
        // the binding keeps the order of the entries and leaves out those it cannot convert, so a greedy match pairs them;
        // between entries repeating an id, the one whose types and policy values agree with the bound entry wins
        List<BiPredicate<Object, Publisher>> preferences = List.of(
                (entry, publisher) -> sameTypes(entry, publisher) && policyAgrees(entry, publisher.getOnParticipantRemoved()),
                PublisherEntryCheck::sameTypes,
                (entry, publisher) -> true);
        int[] rawIndexOf = new int[publishers.size()];
        int next = 0;
        for (int bound = 0; bound < publishers.size(); bound++) {
            Publisher publisher = publishers.get(bound);
            int match = raw.size();
            for (BiPredicate<Object, Publisher> preference : preferences) {
                match = firstMatch(raw, next, publisher, preference);
                if (match < raw.size()) {
                    break;
                }
            }
            if (match == raw.size()) {
                // not from the list, e.g. bound from another source; keep the bound index
                rawIndexOf[bound] = bound;
                continue;
            }
            rawIndexOf[bound] = match;
            next = match + 1;
        }
        return rawIndexOf;
    }

    private static int firstMatch(List<?> raw, int from, Publisher publisher, BiPredicate<Object, Publisher> preference) {
        int match = from;
        while (match < raw.size() && !(Objects.equals(idOf(raw.get(match)), publisher.getId())
                && preference.test(raw.get(match), publisher))) {
            match++;
        }
        return match;
    }

    private static boolean sameTypes(Object entry, Publisher publisher) {
        List<String> bound = publisher.getTypes() == null ? List.of() : publisher.getTypes();
        return typesOf(entry).equals(bound);
    }

    // a converted value must equal the bound one; a value the conversion rejected never does
    private static boolean policyAgrees(Object entry, @Nullable OnParticipantRemoved policy) {
        OnParticipantRemoved bound = policy == null ? new OnParticipantRemoved() : policy;
        if (!hasPolicyKey(entry)) {
            return isDefault(bound);
        }
        Map<String, Object> configured = policyScalars(entry);
        RemovalMode mode = bound.getMode() == null ? RemovalMode.REPORT : bound.getMode();
        return agrees(configured.get(MODE_FIELD), mode.name())
                && agrees(configured.get(MAX_REMOVALS_FIELD), String.valueOf(bound.getMaxRemovalsPerRun()))
                && agrees(configured.get(MAX_ADOPTIONS_FIELD), String.valueOf(bound.getMaxAdoptionsPerRun()))
                && agrees(configured.get(ADOPT_FIELD), String.valueOf(bound.isAdoptUnattributed()))
                && agrees(configured.get(REGISTRY_URL_FIELD), bound.getRegistryUrl());
    }

    private static boolean agrees(@Nullable Object configured, @Nullable String bound) {
        return configured == null || configured.toString().trim().equalsIgnoreCase(String.valueOf(bound));
    }

    // scalar policy values keyed by their normalised name, from a nested policy map or flattened dotted keys
    private static Map<String, Object> policyScalars(Object entry) {
        Map<String, Object> scalars = new HashMap<>();
        if (!(entry instanceof Map<?, ?> map)) {
            return scalars;
        }
        map.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (isPolicyKey(name) && value instanceof Map<?, ?> nested) {
                nested.forEach((field, fieldValue) -> scalars.put(normalised(String.valueOf(field)), fieldValue));
            } else if (name.startsWith(POLICY_KEY + ".") || name.startsWith(POLICY_KEY_CAMEL_CASE + ".")) {
                scalars.put(normalised(name.substring(name.indexOf('.') + 1)), value);
            }
        });
        return scalars;
    }

    private static String normalised(String key) {
        return key.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static void checkDropped(List<?> raw, int[] rawIndexOf, List<Finding> findings) {
        boolean[] matched = new boolean[raw.size()];
        for (int index : rawIndexOf) {
            if (index < raw.size()) {
                matched[index] = true;
            }
        }
        for (int index = 0; index < raw.size(); index++) {
            if (matched[index]) {
                continue;
            }
            Object entry = raw.get(index);
            if (concernsRemovals(entry)) {
                findings.add(new Finding(Severity.FATAL, ("%s[%s] (id %s) has an invalid value and is dropped; an invalid "
                        + "value drops the whole publisher entry, so it would neither be authorized for its event types nor "
                        + "handle participant removals.").formatted(PUBLISHERS_KEY, index, idOf(entry))));
            } else {
                findings.add(new Finding(Severity.WARN, ("%s[%s] (id %s) has an invalid value and is dropped; it is not "
                        + "authorized for any event type.").formatted(PUBLISHERS_KEY, index, idOf(entry))));
            }
        }
    }

    private static void checkInertPolicies(List<Publisher> publishers, int[] rawIndexOf, List<Finding> findings) {
        for (int bound = 0; bound < publishers.size(); bound++) {
            Publisher publisher = publishers.get(bound);
            if (!publishesRemovals(publisher) && !isDefault(publisher.getOnParticipantRemoved())) {
                findings.add(new Finding(Severity.WARN, "%s[%s]: on-participant-removed of %s is ignored: its types do not include %s"
                        .formatted(PUBLISHERS_KEY, rawIndexOf[bound], publisher.getId(), PARTICIPANT_REMOVED_TYPE)));
            }
        }
    }

    private static void checkRepeatedIds(List<Publisher> publishers, int[] rawIndexOf, List<Finding> findings) {
        Map<String, Integer> first = new HashMap<>();
        for (int bound = 0; bound < publishers.size(); bound++) {
            Publisher publisher = publishers.get(bound);
            if (publisher.getId() == null || publisher.getId().isBlank() || !publishesRemovals(publisher)) {
                continue;
            }
            Integer firstBound = first.putIfAbsent(publisher.getId(), bound);
            if (firstBound != null && !Objects.equals(publishers.get(firstBound).getOnParticipantRemoved(),
                    publisher.getOnParticipantRemoved())) {
                findings.add(new Finding(Severity.FATAL, ("%s[%s] and %s[%s] both publish participant removals as %s with "
                        + "different on-participant-removed policies; only the first one would apply. Give the publisher "
                        + "one policy.").formatted(PUBLISHERS_KEY, rawIndexOf[firstBound], PUBLISHERS_KEY, rawIndexOf[bound],
                        publisher.getId())));
            }
        }
    }

    private static boolean publishesRemovals(Publisher publisher) {
        return publisher.getTypes() != null && publisher.getTypes().contains(PARTICIPANT_REMOVED_TYPE);
    }

    private static boolean isDefault(@Nullable OnParticipantRemoved policy) {
        return policy == null || policy.equals(new OnParticipantRemoved());
    }

    private static boolean concernsRemovals(@Nullable Object entry) {
        return hasPolicyKey(entry) || typesOf(entry).contains(PARTICIPANT_REMOVED_TYPE);
    }

    private static boolean hasPolicyKey(@Nullable Object entry) {
        // flattened properties (publishers[0].on-participant-removed.mode) keep the dotted key
        return entry instanceof Map<?, ?> map && map.keySet().stream()
                .map(String::valueOf)
                .anyMatch(key -> isPolicyKey(key) || key.startsWith(POLICY_KEY + ".")
                        || key.startsWith(POLICY_KEY_CAMEL_CASE + "."));
    }

    private static boolean isPolicyKey(String key) {
        return key.equals(POLICY_KEY) || key.equals(POLICY_KEY_CAMEL_CASE);
    }

    // a list, or a comma-separated string as the binding splits it
    private static List<String> typesOf(@Nullable Object entry) {
        Object types = entry instanceof Map<?, ?> map ? map.get(TYPES_KEY) : null;
        if (types instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(type -> type.toString().trim()).toList();
        }
        if (types == null || types.toString().isBlank()) {
            return List.of();
        }
        return Arrays.stream(types.toString().split(LIST_SEPARATOR)).map(String::trim).toList();
    }

    @Nullable
    private static String idOf(@Nullable Object entry) {
        if (entry instanceof Map<?, ?> map && map.get(ID_KEY) != null) {
            return map.get(ID_KEY).toString();
        }
        return null;
    }
}
