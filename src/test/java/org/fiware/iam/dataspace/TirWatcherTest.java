package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.dataspace.OwnEventPublisher.PublishedEvent;
import org.fiware.iam.dataspace.TirWatcher.Outcome;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TirEnumerationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.fiware.iam.dataspace.TirWatcher.Outcome.BREAKER;
import static org.fiware.iam.dataspace.TirWatcher.Outcome.COMPLETE;
import static org.fiware.iam.dataspace.TirWatcher.Outcome.EMPTY;
import static org.fiware.iam.dataspace.TirWatcher.Outcome.INCOMPLETE;
import static org.fiware.iam.dataspace.TirWatcher.Outcome.INITIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Acceptance scenarios of the trust anchor watcher: confirmations, error cycles, empty registry, circuit breaker and
 * the retry of confirmed removals nobody received yet.
 */
class TirWatcherTest {

    private static final String OTHER = "did:web:other.org";
    private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_CONFIRMATIONS = 2;
    private static final int DEFAULT_CAP = 5;
    private static final int DELIVERED = 1;
    private static final URI SINK = URI.create("https://sink.org/events");

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TirWatcher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private TrustedIssuersRegistryReader reader;
    private OwnEventPublisher publisher;
    private SubscriptionRegistry subscriptionRegistry;
    private DataSpaceEventsProperties properties;
    private MutableClock clock;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        logged.start();
        logger.addAppender(logged);
        reader = mock(TrustedIssuersRegistryReader.class);
        publisher = mock(OwnEventPublisher.class);
        subscriptionRegistry = mock(SubscriptionRegistry.class);
        properties = new DataSpaceEventsProperties();
        clock = new MutableClock(START);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
    }

    @Test
    void freshWatcherIsNotScheduled() {
        assertEquals(TirWatcher.Status.NOT_SCHEDULED, newWatcher().status());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void runsScenario(Scenario scenario) {
        properties.getTirWatcher().setConfirmations(scenario.confirmations);
        properties.getTirWatcher().setMaxRemovalsPerCycle(scenario.cap);
        TirWatcher watcher = newWatcher();
        List<String> published = new ArrayList<>();
        Map<String, Set<String>> eventIds = new HashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        Instant lastSuccess = null;

        for (int i = 0; i < scenario.cycles.size(); i++) {
            int number = i + 1;
            Cycle cycle = scenario.cycles.get(i);
            arrange(cycle, number, published, eventIds);
            clock.advance(INTERVAL);

            Outcome outcome = watcher.cycle();

            outcomes.add(outcome);
            if (outcome == INITIAL || outcome == COMPLETE) {
                lastSuccess = clock.instant();
            }
            assertEquals(lastSuccess, watcher.status().lastSuccessfulCycle(), "last successful cycle after " + number);
            assertEquals(outcome, watcher.status().lastOutcome());
        }

        TirWatcher.Status status = watcher.status();
        assertEquals(scenario.outcomes, outcomes, "outcomes");
        assertEquals(scenario.published.stream().sorted().toList(), published.stream().sorted().toList(), "published");
        eventIds.forEach((did, ids) -> assertEquals(1, ids.size(), "event ids of the removal of " + did));
        assertEquals(eventIds.size(), eventIds.values().stream().flatMap(Set::stream).distinct().count(),
                "one event id per removal");
        assertEquals(scenario.announced(), meterRegistry.counter(TirWatcher.REMOVALS_METRIC).count(), "announced");
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcomes.stream().filter(outcome::equals).count(), cycles(outcome), "cycles " + outcome);
        }
        assertEquals(scenario.alert, status.alert(), "alert");
        assertEquals(scenario.undelivered, status.undeliveredSize(), "undelivered");
        if (scenario.snapshot != null) {
            assertEquals(scenario.snapshot, status.snapshotSize(), "snapshot");
        }
        if (scenario.pending != null) {
            assertEquals(scenario.pending, status.pendingSize(), "pending");
        }
        assertEquals(scenario.errors, logged(Level.ERROR), "ERROR lines");
        assertEquals(scenario.warnings, logged(Level.WARN), "WARN lines");
        for (String fragment : scenario.infoFragments) {
            assertEquals(1, logged.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains(fragment))
                    .count(), "INFO lines containing " + fragment);
        }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                scenario("clean removal",
                        ok("A", "B", "C"), ok("A", "B"), ok("A", "B"))
                        .outcomes(INITIAL, COMPLETE, COMPLETE).published("3:C").snapshot(2),
                scenario("clean removal, one confirmation",
                        ok("A", "B", "C"), ok("A", "B"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE).published("2:C").snapshot(2),
                scenario("registry error mid-enumeration",
                        ok("A", "B", "C"), error(), error())
                        .outcomes(INITIAL, INCOMPLETE, INCOMPLETE).snapshot(3).warnings(2),
                scenario("error cycles neither confirm nor refute",
                        ok("A", "B", "C"), ok("A", "B"), error(), ok("A", "B"))
                        .outcomes(INITIAL, COMPLETE, INCOMPLETE, COMPLETE).published("4:C").warnings(1),
                scenario("empty registry",
                        ok("A", "B"), ok(), ok())
                        .outcomes(INITIAL, EMPTY, EMPTY).alert(FailureReason.TIR_EMPTY).snapshot(2).errors(1),
                scenario("empty registry restored",
                        ok("A", "B"), ok(), ok(), ok("A", "B"))
                        .outcomes(INITIAL, EMPTY, EMPTY, COMPLETE).snapshot(2).errors(1).info("cleared"),
                scenario("empty from the start",
                        ok(), ok(), ok("A"))
                        .outcomes(INITIAL, COMPLETE, COMPLETE).snapshot(1).warnings(1),
                scenario("last participant removed",
                        ok("A"), ok(), ok())
                        .outcomes(INITIAL, EMPTY, EMPTY).alert(FailureReason.TIR_EMPTY).snapshot(1).errors(1),
                scenario("reappearance",
                        ok("A", "B", "C"), ok("A", "B"), ok("A", "B", "C"), ok("A", "B"))
                        .outcomes(INITIAL, COMPLETE, COMPLETE, COMPLETE).snapshot(3).pending(1),
                scenario("restart",
                        ok("A"))
                        .outcomes(INITIAL).snapshot(1),
                scenario("over the cap",
                        ok("A", "B", "C", "D"), ok("A"))
                        .cap(2).outcomes(INITIAL, BREAKER).alert(FailureReason.TIR_MASS_REMOVAL).snapshot(4)
                        .pending(0).errors(1),
                scenario("exactly the cap",
                        ok("A", "B", "C", "D"), ok("A"), ok("A"))
                        .cap(3).outcomes(INITIAL, COMPLETE, COMPLETE).published("3:B", "3:C", "3:D").snapshot(1),
                scenario("staggered removals",
                        ok("A", "B", "C", "D", "E", "F", "G"), ok("A", "B", "C", "D"), ok("A"))
                        .outcomes(INITIAL, COMPLETE, BREAKER).alert(FailureReason.TIR_MASS_REMOVAL).snapshot(7)
                        .pending(3).errors(1),
                scenario("breaker remedy lowered too early",
                        ok("A", "B", "C", "D"), ok("A"), ok("A").cap(5), ok("A").cap(2))
                        .cap(2).outcomes(INITIAL, BREAKER, COMPLETE, BREAKER).alert(FailureReason.TIR_MASS_REMOVAL)
                        .snapshot(4).errors(2).info("cleared"),
                scenario("breaker remedy kept for the confirmations",
                        ok("A", "B", "C", "D"), ok("A"), ok("A").cap(5), ok("A"))
                        .cap(2).outcomes(INITIAL, BREAKER, COMPLETE, COMPLETE).published("4:B", "4:C", "4:D")
                        .snapshot(1).errors(1),
                scenario("no subscriptions",
                        ok("A", "B"), ok("A").subscribedBy(), ok("A").subscribedBy("A"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, COMPLETE).published("3:B").warnings(1),
                scenario("no subscriptions, then reappeared",
                        ok("A", "B"), ok("A").subscribedBy(), ok("A", "B"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, COMPLETE).snapshot(2).warnings(1),
                scenario("check/publish race",
                        ok("A", "B"), ok("A").subscribedBy("A").delivers(0), ok("A").subscribedBy("A"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, COMPLETE).published("2:B", "3:B").announced(1)
                        .warnings(1),
                scenario("no source",
                        ok("A", "B"), ok("A").noSource(), ok("A").noSource(), ok("A"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, COMPLETE, COMPLETE).published("2:B", "3:B", "4:B")
                        .announced(1).errors(1).info("set again"),
                scenario("retry waits for a complete cycle",
                        ok("A", "B"), ok("A").subscribedBy(), error(), ok("A"))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, INCOMPLETE, COMPLETE).published("4:B").warnings(2),
                scenario("only the removed participant subscribed",
                        ok("A", "B"), ok("A").subscribedBy("B"), ok("A").subscribedBy("B", "A").delivers(2))
                        .confirmations(1).outcomes(INITIAL, COMPLETE, COMPLETE).published("3:B").warnings(1),
                scenario("publish throws: the cycle completes and the removal is retried",
                        ok("A", "B"), ok(), ok("A", "C").publishFails(), ok("A", "C").publishFails(), ok("A", "C"))
                        .confirmations(1).outcomes(INITIAL, EMPTY, COMPLETE, COMPLETE, COMPLETE)
                        .published("3:B", "4:B", "5:B").announced(1).snapshot(2).errors(1).warnings(1)
                        .info("cleared"),
                scenario("additions are never announced",
                        ok("A"), ok("A", "B"))
                        .outcomes(INITIAL, COMPLETE).snapshot(2));
    }

    private TirWatcher newWatcher() {
        return new TirWatcher(reader, publisher, subscriptionRegistry, properties, clock, Optional.of(meterRegistry));
    }

    private void arrange(Cycle cycle, int number, List<String> published, Map<String, Set<String>> eventIds) {
        if (cycle.registry == null) {
            doThrow(new TirEnumerationException("unreachable", 1, null, null)).when(reader).enumerate();
        } else {
            doReturn(cycle.registry).when(reader).enumerate();
        }
        if (cycle.cap != null) {
            properties.getTirWatcher().setMaxRemovalsPerCycle(cycle.cap);
        }
        doReturn(cycle.subscribers.stream().map(TirWatcherTest::subscription).toList())
                .when(subscriptionRegistry).active(TirWatcher.PARTICIPANT_REMOVED_TYPE);
        doAnswer(invocation -> {
            String did = (String) invocation.getArguments()[1];
            assertEquals(TirWatcher.PARTICIPANT_REMOVED_TYPE, invocation.getArguments()[0]);
            assertEquals(Map.of(TirWatcher.PARTICIPANT_ID_KEY, did), invocation.getArguments()[2]);
            String id = (String) invocation.getArguments()[3];
            assertFalse(id == null || id.isBlank(), "event id");
            eventIds.computeIfAbsent(did, key -> new HashSet<>()).add(id);
            published.add(number + ":" + did);
            if (cycle.fails) {
                throw new IllegalStateException("delivery failed");
            }
            return cycle.delivered == null
                    ? Optional.empty()
                    : Optional.of(new PublishedEvent(id, cycle.delivered));
        }).when(publisher).publish(any(), any(), any(), any());
    }

    private double cycles(Outcome outcome) {
        Counter counter = meterRegistry.find(TirWatcher.CYCLES_METRIC)
                .tag(TirWatcher.OUTCOME_TAG, outcome.tagValue())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private int logged(Level level) {
        return (int) logged.list.stream().filter(event -> event.getLevel() == level).count();
    }

    private static Subscription subscription(String subscriberId) {
        return new Subscription(UUID.randomUUID().toString(), subscriberId, SINK,
                List.of(TirWatcher.PARTICIPANT_REMOVED_TYPE), "client", Set.of(), START.plus(Duration.ofDays(1)), null);
    }

    private static Scenario scenario(String name, Cycle... cycles) {
        return new Scenario(name, List.of(cycles));
    }

    private static Cycle ok(String... dids) {
        return new Cycle(Set.of(dids), null, List.of(OTHER), DELIVERED, false);
    }

    private static Cycle error() {
        return new Cycle(null, null, List.of(OTHER), DELIVERED, false);
    }

    /**
     * One cycle: the registry answer (null = enumeration fails), a refreshed cap, the active subscribers of
     * participant.removed, and how many subscriptions a publish matches (null = no source) or whether it throws.
     */
    private record Cycle(@Nullable Set<String> registry, @Nullable Integer cap, List<String> subscribers,
                         @Nullable Integer delivered, boolean fails) {

        Cycle cap(int value) {
            return new Cycle(registry, value, subscribers, delivered, fails);
        }

        Cycle subscribedBy(String... subscriberIds) {
            return new Cycle(registry, cap, List.of(subscriberIds), delivered, fails);
        }

        Cycle delivers(int count) {
            return new Cycle(registry, cap, subscribers, count, fails);
        }

        Cycle noSource() {
            return new Cycle(registry, cap, subscribers, null, fails);
        }

        Cycle publishFails() {
            return new Cycle(registry, cap, subscribers, delivered, true);
        }
    }

    /**
     * A scenario with its expectations; published entries are "cycle:did" of every publish call.
     */
    private static final class Scenario {
        private final String name;
        private final List<Cycle> cycles;
        private int confirmations = DEFAULT_CONFIRMATIONS;
        private int cap = DEFAULT_CAP;
        private List<Outcome> outcomes = List.of();
        private List<String> published = List.of();
        @Nullable
        private Integer announced;
        @Nullable
        private FailureReason alert;
        private int undelivered = 0;
        @Nullable
        private Integer snapshot;
        @Nullable
        private Integer pending;
        private int errors = 0;
        private int warnings = 0;
        private final List<String> infoFragments = new ArrayList<>();

        private Scenario(String name, List<Cycle> cycles) {
            this.name = name;
            this.cycles = cycles;
        }

        Scenario confirmations(int value) {
            confirmations = value;
            return this;
        }

        Scenario cap(int value) {
            cap = value;
            return this;
        }

        Scenario outcomes(Outcome... values) {
            outcomes = List.of(values);
            return this;
        }

        Scenario published(String... values) {
            published = Arrays.asList(values);
            return this;
        }

        Scenario announced(int value) {
            announced = value;
            return this;
        }

        double announced() {
            return announced == null ? published.size() : announced;
        }

        Scenario alert(FailureReason value) {
            alert = value;
            return this;
        }

        Scenario snapshot(int value) {
            snapshot = value;
            return this;
        }

        Scenario pending(int value) {
            pending = value;
            return this;
        }

        Scenario errors(int value) {
            errors = value;
            return this;
        }

        Scenario warnings(int value) {
            warnings = value;
            return this;
        }

        Scenario info(String fragment) {
            infoFragments.add(fragment);
            return this;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
