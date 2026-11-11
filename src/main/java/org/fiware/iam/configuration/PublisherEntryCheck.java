package org.fiware.iam.configuration;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import org.fiware.iam.configuration.DataSpaceEventsProperties.ModeOverride;
import org.fiware.iam.configuration.DataSpaceEventsProperties.OnParticipantRemoved;
import org.fiware.iam.configuration.DataSpaceEventsProperties.Publisher;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;

/**
 * Finds publisher entries whose participant.removed handling differs from what the configuration reads like: entries
 * dropped by a failed conversion, unknown keys the binding ignores, policies ignored for lack of the type, and
 * conflicting policies of one repeated id.
 * Shared by the start-up guard and the sweep, which re-checks after a refresh.
 */
public final class PublisherEntryCheck {

    public static final String PUBLISHERS_KEY = "dataspace.events.publishers";
    public static final String POLICY_KEY = "on-participant-removed";
    private static final String POLICY_FIELD = "onParticipantRemoved";
    static final String ID_KEY = "id";
    static final String TYPES_KEY = "types";
    private static final String LIST_SEPARATOR = ",";
    private static final String MODE_FIELD = "mode";
    private static final String MAX_REMOVALS_FIELD = "maxRemovalsPerRun";
    private static final String MAX_ADOPTIONS_FIELD = "maxAdoptionsPerRun";
    private static final String ADOPT_FIELD = "adoptUnattributed";
    private static final String REGISTRY_URL_FIELD = "registryUrl";
    private static final String KEEP_FIELD = "keep";
    private static final String OVERRIDES_FIELD = "overrides";
    private static final String OVERRIDE_ID_FIELD = "id";
    private static final String OVERRIDE_MODE_FIELD = "mode";
    private static final int UNMATCHED = -1;
    private static final String KEY_PATH_SEPARATORS = "[.\\[]";
    private static final String KEY_PATH_SEPARATOR = ".";
    private static final String SNAKE_CASE_SEPARATOR = "_";
    // the binding accepts a key whose camel-case form is the field name and silently ignores any other key
    private static final Set<String> PUBLISHER_FIELDS = fieldNames(Publisher.class);
    private static final Set<String> POLICY_FIELDS = fieldNames(OnParticipantRemoved.class);
    // the binding keeps the order of the entries and leaves out those it cannot convert, so a greedy match pairs them;
    // between entries repeating an id, the one whose types and policy values agree with the bound entry wins
    private static final List<BiPredicate<Object, Publisher>> PREFERENCES = List.of(
            (entry, publisher) -> sameTypes(entry, publisher) && policyAgrees(entry, publisher.getOnParticipantRemoved()),
            PublisherEntryCheck::sameTypes,
            (entry, publisher) -> true);

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
        int[] matched = align(raw, publishers);
        checkDropped(raw, publishers, matched, findings);
        checkUnknownKeys(raw, findings);
        int[] rawIndexOf = keyIndices(matched);
        checkInertPolicies(publishers, rawIndexOf, findings);
        checkRepeatedIds(publishers, rawIndexOf, findings);
        return findings;
    }

    /**
     * Index of each bound publisher in the unconverted entries, i.e. its configuration key; the bound index where none matches.
     */
    public static int[] rawIndices(@Nullable List<?> rawEntries, List<Publisher> publishers) {
        return keyIndices(align(rawEntries == null ? List.of() : rawEntries, publishers));
    }

    // a publisher without a raw counterpart, e.g. bound from another source, keeps its bound index for messages
    private static int[] keyIndices(int[] matched) {
        int[] keyIndices = matched.clone();
        for (int bound = 0; bound < keyIndices.length; bound++) {
            if (keyIndices[bound] == UNMATCHED) {
                keyIndices[bound] = bound;
            }
        }
        return keyIndices;
    }

    // the raw index of each bound publisher, UNMATCHED where none matches
    private static int[] align(List<?> raw, List<Publisher> publishers) {
        int[] rawIndexOf = new int[publishers.size()];
        int next = 0;
        for (int bound = 0; bound < publishers.size(); bound++) {
            Publisher publisher = publishers.get(bound);
            int match = raw.size();
            for (BiPredicate<Object, Publisher> preference : PREFERENCES) {
                match = firstMatch(raw, next, publisher, preference);
                if (match < raw.size()) {
                    break;
                }
            }
            if (match == raw.size()) {
                rawIndexOf[bound] = UNMATCHED;
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

    // the strongest preference an entry meets for a publisher, PREFERENCES.size() for another id
    private static int preferenceLevel(Object entry, Publisher publisher) {
        if (!Objects.equals(idOf(entry), publisher.getId())) {
            return PREFERENCES.size();
        }
        int level = 0;
        while (level < PREFERENCES.size() && !PREFERENCES.get(level).test(entry, publisher)) {
            level++;
        }
        return level;
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
        Map<String, Object> configured = policyValues(entry);
        RemovalMode mode = bound.getMode() == null ? RemovalMode.REPORT : bound.getMode();
        return agrees(configured.get(MODE_FIELD), mode.name())
                && agrees(configured.get(MAX_REMOVALS_FIELD), String.valueOf(bound.getMaxRemovalsPerRun()))
                && agrees(configured.get(MAX_ADOPTIONS_FIELD), String.valueOf(bound.getMaxAdoptionsPerRun()))
                && agrees(configured.get(ADOPT_FIELD), String.valueOf(bound.isAdoptUnattributed()))
                && agrees(configured.get(REGISTRY_URL_FIELD), bound.getRegistryUrl())
                && (configured.get(KEEP_FIELD) == null || asStrings(configured.get(KEEP_FIELD)).equals(
                        bound.getKeep() == null ? List.of() : bound.getKeep()))
                && overridesAgree(configured.get(OVERRIDES_FIELD), bound.getOverrides());
    }

    private static boolean overridesAgree(@Nullable Object configured, @Nullable List<ModeOverride> bound) {
        if (configured == null) {
            return true;
        }
        List<ModeOverride> overrides = bound == null ? List.of() : bound;
        if (!(configured instanceof List<?> list) || list.size() != overrides.size()) {
            return false;
        }
        for (int index = 0; index < list.size(); index++) {
            ModeOverride override = overrides.get(index);
            if (!(list.get(index) instanceof Map<?, ?> entry)
                    || !agrees(entry.get(OVERRIDE_ID_FIELD), override.getId())
                    || !agrees(entry.get(OVERRIDE_MODE_FIELD), override.getMode() == null ? null : override.getMode().name())) {
                return false;
            }
        }
        return true;
    }

    private static boolean agrees(@Nullable Object configured, @Nullable String bound) {
        return configured == null || configured.toString().trim().equalsIgnoreCase(String.valueOf(bound));
    }

    // policy values keyed by the field they bind to
    private static Map<String, Object> policyValues(Object entry) {
        Map<String, Object> values = new HashMap<>();
        if (entry instanceof Map<?, ?> map) {
            policyEntries(map).forEach((key, value) -> values.put(boundName(key), value));
        }
        return values;
    }

    // policy values keyed as configured, from a nested policy map or flattened dotted keys
    private static Map<String, Object> policyEntries(Map<?, ?> entry) {
        Map<String, Object> values = new LinkedHashMap<>();
        entry.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (isPolicyKey(name) && value instanceof Map<?, ?> nested) {
                nested.forEach((field, fieldValue) -> values.put(String.valueOf(field), fieldValue));
            } else if (isFlattenedPolicyKey(name)) {
                values.put(name.substring(name.indexOf(KEY_PATH_SEPARATOR) + 1), value);
            }
        });
        return values;
    }

    // the field a key binds to; Micronaut has already turned snake case in flattened keys into kebab case, while a
    // nested snake-case key binds nothing
    private static String boundName(String key) {
        return key.contains(SNAKE_CASE_SEPARATOR) ? key : NameUtils.camelCase(key);
    }

    private static void checkDropped(List<?> raw, List<Publisher> publishers, int[] rawIndexOf, List<Finding> findings) {
        boolean[] matched = new boolean[raw.size()];
        for (int index : rawIndexOf) {
            if (index != UNMATCHED) {
                matched[index] = true;
            }
        }
        for (int index = 0; index < raw.size(); index++) {
            if (matched[index]) {
                continue;
            }
            Object entry = raw.get(index);
            String dropped = droppedKeys(raw, publishers, rawIndexOf, index);
            if (concernsRemovals(entry)) {
                findings.add(new Finding(Severity.FATAL, ("%s (id %s) has an invalid value and is dropped; an invalid "
                        + "value drops the whole publisher entry, so it would neither be authorized for its event types nor "
                        + "handle participant removals.").formatted(dropped, idOf(entry))));
            } else {
                findings.add(new Finding(Severity.WARN, ("%s (id %s) has an invalid value and is dropped; it is not "
                        + "authorized for any event type.").formatted(dropped, idOf(entry))));
            }
        }
    }

    // the key of a dropped entry; when a converted entry of the same id fits its bound publisher no better, the invalid
    // value cannot be told apart from the valid one, so all of them are named
    private static String droppedKeys(List<?> raw, List<Publisher> publishers, int[] rawIndexOf, int dropped) {
        TreeSet<Integer> indices = new TreeSet<>(List.of(dropped));
        for (int bound = 0; bound < publishers.size(); bound++) {
            int converted = rawIndexOf[bound];
            Publisher publisher = publishers.get(bound);
            if (converted != UNMATCHED && Objects.equals(idOf(raw.get(dropped)), publisher.getId())
                    && preferenceLevel(raw.get(dropped), publisher) <= preferenceLevel(raw.get(converted), publisher)) {
                indices.add(converted);
            }
        }
        String keys = indices.stream().map(index -> "%s[%s]".formatted(PUBLISHERS_KEY, index))
                .collect(Collectors.joining(" or "));
        return indices.size() == 1 ? keys : "one of " + keys;
    }

    // a misspelt key binds nothing, so the field keeps its default; inside the policy of a removal entry that would
    // silently weaken the configured cleanup, elsewhere it keeps today's behaviour
    private static void checkUnknownKeys(List<?> raw, List<Finding> findings) {
        for (int index = 0; index < raw.size(); index++) {
            if (!(raw.get(index) instanceof Map<?, ?> entry)) {
                continue;
            }
            String prefix = "%s[%s]".formatted(PUBLISHERS_KEY, index);
            String id = idOf(entry);
            boolean removal = typesOf(entry).contains(PARTICIPANT_REMOVED_TYPE);
            entry.forEach((key, value) -> {
                if (PUBLISHER_FIELDS.contains(fieldOf(String.valueOf(key)))) {
                    return;
                }
                if (removal && holdsPolicy(String.valueOf(key), value)) {
                    findings.add(new Finding(Severity.FATAL, ("%s.%s (id %s) is not a publisher setting and is ignored, "
                            + "but holds %s settings: the whole policy, keep list included, would fall back to its defaults.")
                            .formatted(prefix, key, id, POLICY_KEY)));
                } else {
                    findings.add(new Finding(Severity.WARN, "%s.%s (id %s) is not a publisher setting and is ignored."
                            .formatted(prefix, key, id)));
                }
            });
            Severity severity = removal ? Severity.FATAL : Severity.WARN;
            for (String key : policyEntries(entry).keySet()) {
                if (!POLICY_FIELDS.contains(fieldOf(key))) {
                    findings.add(new Finding(severity, ("%s.%s.%s (id %s) is not an %s setting and is ignored, so its "
                            + "default applies.").formatted(prefix, POLICY_KEY, key, id, POLICY_KEY)));
                }
            }
        }
    }

    // a misspelt policy key: a map, or a flattened key, setting policy fields
    private static boolean holdsPolicy(String key, @Nullable Object value) {
        if (value instanceof Map<?, ?> nested) {
            return nested.keySet().stream().anyMatch(field -> POLICY_FIELDS.contains(fieldOf(String.valueOf(field))));
        }
        int separator = key.indexOf(KEY_PATH_SEPARATOR);
        return separator >= 0 && POLICY_FIELDS.contains(fieldOf(key.substring(separator + 1)));
    }

    // the field a possibly flattened key (overrides[0].mode) sets
    private static String fieldOf(String key) {
        return boundName(key.split(KEY_PATH_SEPARATORS, 2)[0]);
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toUnmodifiableSet());
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
                .anyMatch(key -> isPolicyKey(key) || isFlattenedPolicyKey(key));
    }

    private static boolean isPolicyKey(String key) {
        return boundName(key).equals(POLICY_FIELD);
    }

    private static boolean isFlattenedPolicyKey(String key) {
        int separator = key.indexOf(KEY_PATH_SEPARATOR);
        return separator >= 0 && isPolicyKey(key.substring(0, separator));
    }

    private static List<String> typesOf(@Nullable Object entry) {
        return asStrings(entry instanceof Map<?, ?> map ? map.get(TYPES_KEY) : null);
    }

    // a list, or a comma-separated string as the binding splits it
    private static List<String> asStrings(@Nullable Object values) {
        if (values instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(value -> value.toString().trim()).toList();
        }
        if (values == null || values.toString().isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.toString().split(LIST_SEPARATOR)).map(String::trim).toList();
    }

    @Nullable
    private static String idOf(@Nullable Object entry) {
        if (entry instanceof Map<?, ?> map && map.get(ID_KEY) != null) {
            return map.get(ID_KEY).toString();
        }
        return null;
    }
}
