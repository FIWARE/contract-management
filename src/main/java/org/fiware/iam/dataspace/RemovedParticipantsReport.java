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

/**
 * In-memory record of the latest outcome per (publisher, participant), bounded per publisher by evicting the entries seen
 * longest ago. Not persisted: after a restart the reconciliation refills the attributable entries.
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
         * True if a grant explains what is still held, null if the entry was not read.
         */
        @Nullable
        public Boolean attributable() {
            return outcome.held() == null ? null : !outcome.held().grants().isEmpty();
        }
    }
}
