package org.fiware.iam.dataspace;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventDeduplicatorTest {

    private static final EventDeduplicator.Key KEY = new EventDeduplicator.Key("did:web:provider.org", "event-1");
    private static final String MAX_ENTRIES = "dataspace.events.listener.dedup.max-entries";
    private static final String TTL = "dataspace.events.listener.dedup.ttl";
    private static final int INITIAL_MAX_ENTRIES = 100;
    private static final Duration INITIAL_TTL = Duration.ofHours(1);
    private static final int LOWERED_MAX_ENTRIES = 3;
    private static final Duration LOWERED_TTL = Duration.ofMinutes(1);
    // keeps the test context away from the Rainbow and TM Forum startup calls
    private static final Map<String, Object> ISOLATED = Map.of("notification.enabled", false, "general.enable-rainbow", false);

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void tracksTheEventState(String description, Consumer<EventDeduplicator> before, EventDeduplicator.Seen expected) {
        EventDeduplicator deduplicator = new EventDeduplicator(new DataSpaceEventsProperties());
        before.accept(deduplicator);

        assertEquals(expected, deduplicator.begin(KEY), description);
    }

    static Stream<Arguments> transitions() {
        Consumer<EventDeduplicator> nothing = d -> {
        };
        Consumer<EventDeduplicator> begun = d -> d.begin(KEY);
        return Stream.of(
                Arguments.of("unseen event is new", nothing, EventDeduplicator.Seen.NEW),
                Arguments.of("begun event is in flight", begun, EventDeduplicator.Seen.IN_FLIGHT),
                Arguments.of("completed event is done", begun.andThen(d -> d.complete(KEY)), EventDeduplicator.Seen.DONE),
                Arguments.of("forgotten event is new again", begun.andThen(d -> d.forget(KEY)), EventDeduplicator.Seen.NEW),
                Arguments.of("completing an unseen event does not add it", (Consumer<EventDeduplicator>) d -> d.complete(KEY),
                        EventDeduplicator.Seen.NEW),
                Arguments.of("forgetting a completed event keeps it", begun.andThen(d -> d.complete(KEY)).andThen(d -> d.forget(KEY)),
                        EventDeduplicator.Seen.DONE));
    }

    @Test
    void completeAfterExpiryDoesNotReAddTheEntry() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        EventDeduplicator deduplicator = new EventDeduplicator(new DataSpaceEventsProperties(), ticker);
        deduplicator.begin(KEY);

        nanos.addAndGet(DataSpaceEventsProperties.DEFAULT_DEDUP_TTL.plusSeconds(1).toNanos());
        deduplicator.complete(KEY);

        assertEquals(EventDeduplicator.Seen.NEW, deduplicator.begin(KEY));
    }

    @Test
    void refreshedLimitsApplyToTheLiveCache() {
        Map<String, Object> config = new HashMap<>(Map.of(MAX_ENTRIES, INITIAL_MAX_ENTRIES, TTL, INITIAL_TTL.toString()));
        try (ApplicationContext context = ApplicationContext.builder()
                .properties(ISOLATED)
                .propertySources(PropertySource.of("test", config))
                .start()) {
            EventDeduplicator deduplicator = context.getBean(EventDeduplicator.class);
            deduplicator.begin(KEY);
            assertLimits(deduplicator, INITIAL_MAX_ENTRIES, INITIAL_TTL);

            config.put(MAX_ENTRIES, LOWERED_MAX_ENTRIES);
            config.put(TTL, LOWERED_TTL.toString());
            context.publishEvent(new RefreshEvent(context.getEnvironment().refreshAndDiff()));
            EventDeduplicator.Seen seen = deduplicator.begin(KEY);

            assertLimits(deduplicator, LOWERED_MAX_ENTRIES, LOWERED_TTL);
            assertEquals(EventDeduplicator.Seen.IN_FLIGHT, seen, "existing entries survive the change");
        }
    }

    private static void assertLimits(EventDeduplicator deduplicator, long maxEntries, Duration ttl) {
        assertEquals(List.of(maxEntries, ttl), List.of(
                deduplicator.policy().eviction().orElseThrow().getMaximum(),
                deduplicator.policy().expireAfterWrite().orElseThrow().getExpiresAfter()));
    }
}
