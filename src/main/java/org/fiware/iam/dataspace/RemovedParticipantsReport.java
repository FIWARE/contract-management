package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory record of the latest outcome per (publisher, participant), bounded per publisher by evicting the entries seen
 * longest ago. Not persisted: after a restart the reconciliation refills the attributable entries.
 * <p>
 * Unapplied reconciliation entries are standing drift and are dropped once the sweep no longer finds them; applied ones
 * and event entries are history, kept until evicted.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class RemovedParticipantsReport {

    public static final int MAX_ENTRIES_PER_PUBLISHER = 1000;

    private final Clock clock;
    // access order is kept by re-inserting on every record, so the first entry is the one seen longest ago
    private final Map<String, LinkedHashMap<String, Entry>> byPublisher = new HashMap<>();

    public RemovedParticipantsReport(Clock clock) {
        this.clock = clock;
    }

    /**
     * Replace the participant's entry of the outcome's publisher.
     */
    public synchronized void record(RemovalOutcome outcome) {
        LinkedHashMap<String, Entry> entries = byPublisher.computeIfAbsent(outcome.publisherId(), id -> new LinkedHashMap<>());
        entries.remove(outcome.did());
        entries.put(outcome.did(), new Entry(outcome, clock.instant()));
        if (entries.size() > MAX_ENTRIES_PER_PUBLISHER) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /**
     * Mark a recorded participant as seen now, keeping its outcome. Does nothing if the entry was evicted or never recorded.
     */
    public synchronized void touch(String publisherId, String did) {
        LinkedHashMap<String, Entry> entries = byPublisher.get(publisherId);
        Entry entry = entries == null ? null : entries.remove(did);
        if (entry != null) {
            entries.put(did, new Entry(entry.outcome(), clock.instant()));
        }
    }

    /**
     * Drop the publisher's unapplied reconciliation entries whose participant is no longer among the sweep's candidates.
     */
    public synchronized void retainStandingDrift(String publisherId, Set<String> candidates) {
        LinkedHashMap<String, Entry> entries = byPublisher.get(publisherId);
        if (entries != null) {
            entries.values().removeIf(entry -> entry.outcome().trigger() == RemovalTrigger.RECONCILIATION
                    && !entry.outcome().applied()
                    && !candidates.contains(entry.outcome().did()));
        }
    }

    /**
     * The publisher's entries, the one seen longest ago first.
     */
    public synchronized List<Entry> entries(String publisherId) {
        return List.copyOf(byPublisher.getOrDefault(publisherId, new LinkedHashMap<>()).values());
    }

    /**
     * The latest outcome for one participant and when it was recorded.
     */
    public record Entry(RemovalOutcome outcome, Instant lastSeen) {

        /**
         * {@link org.fiware.iam.til.HeldEntry#isAttributable()} of what is still held, null if the entry was not read.
         */
        @Nullable
        public Boolean attributable() {
            return outcome.held() == null ? null : outcome.held().isAttributable();
        }
    }
}
