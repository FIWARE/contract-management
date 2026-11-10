package org.fiware.iam.dataspace;

import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemovedParticipantsReportTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration STEP = Duration.ofMinutes(1);
    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";

    private final MutableClock clock = new MutableClock(START);
    private final RemovedParticipantsReport report = new RemovedParticipantsReport(clock);

    @Test
    void keepsTheLatestOutcomePerPublisherAndParticipant() {
        report.record(outcome(P1, X, RemovalAction.REPORTED));
        clock.advance(STEP);
        report.record(outcome(P1, X, RemovalAction.REVOKED_MEMBERSHIP));
        report.record(outcome(P2, X, RemovalAction.KEPT));

        List<RemovedParticipantsReport.Entry> entries = report.entries(P1);
        assertEquals(1, entries.size());
        assertEquals(RemovalAction.REVOKED_MEMBERSHIP, entries.get(0).outcome().action());
        assertEquals(START.plus(STEP), entries.get(0).lastSeen());
        assertEquals(RemovalAction.KEPT, report.entries(P2).get(0).outcome().action());
    }

    @Test
    void evictsTheEntrySeenLongestAgo() {
        IntStream.range(0, RemovedParticipantsReport.MAX_ENTRIES_PER_PUBLISHER)
                .forEach(i -> report.record(outcome(P1, did(i), RemovalAction.REPORTED)));
        // seen again, so no longer the oldest
        report.record(outcome(P1, did(0), RemovalAction.REPORTED));
        report.record(outcome(P1, X, RemovalAction.REPORTED));
        report.record(outcome(P2, X, RemovalAction.REPORTED));

        List<String> dids = report.entries(P1).stream().map(entry -> entry.outcome().did()).toList();
        assertEquals(RemovedParticipantsReport.MAX_ENTRIES_PER_PUBLISHER, dids.size());
        assertEquals(did(2), dids.get(0));
        assertEquals(List.of(did(0), X), dids.subList(dids.size() - 2, dids.size()));
        assertEquals(1, report.entries(P2).size());
    }

    static Stream<Arguments> standingDrift() {
        return Stream.of(
                Arguments.of("unapplied reconciliation, no longer a candidate", RemovalTrigger.RECONCILIATION, false, Set.of(), false),
                Arguments.of("unapplied reconciliation, still a candidate", RemovalTrigger.RECONCILIATION, false, Set.of(X), true),
                Arguments.of("applied reconciliation", RemovalTrigger.RECONCILIATION, true, Set.of(), true),
                Arguments.of("unapplied event", RemovalTrigger.EVENT, false, Set.of(), true),
                Arguments.of("applied event", RemovalTrigger.EVENT, true, Set.of(), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standingDrift")
    void dropsOnlyDriftTheSweepNoLongerFinds(String description, RemovalTrigger trigger, boolean applied,
                                            Set<String> candidates, boolean retained) {
        report.record(new RemovalOutcome(P1, X, trigger, "report", RemovalAction.REPORTED, applied, null));
        report.record(new RemovalOutcome(P2, X, RemovalTrigger.RECONCILIATION, "report", RemovalAction.REPORTED, false, null));

        report.retainStandingDrift(P1, candidates);

        assertEquals(retained ? 1 : 0, report.entries(P1).size());
        assertEquals(1, report.entries(P2).size(), "other publishers are not affected");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standingDrift")
    void dropsTheDriftOfPublishersNoLongerReconciled(String description, RemovalTrigger trigger, boolean applied,
                                                    Set<String> candidates, boolean retainedAsCandidate) {
        report.record(new RemovalOutcome(P1, X, trigger, "report", RemovalAction.REPORTED, applied, null));
        report.record(new RemovalOutcome(P2, X, RemovalTrigger.RECONCILIATION, "report", RemovalAction.REPORTED, false, null));

        report.retainStandingDriftOf(Set.of(P2));

        boolean history = applied || trigger == RemovalTrigger.EVENT;
        assertEquals(history ? 1 : 0, report.entries(P1).size());
        assertEquals(1, report.entries(P2).size(), "reconciled publishers are not affected");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributability")
    void tellsWhetherGrantsExplainTheWholeEntry(String description, HeldEntry held, Boolean expected) {
        report.record(new RemovalOutcome(P1, X, RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false, held));

        assertEquals(expected, report.entries(P1).get(0).attributable());
    }

    static Stream<Arguments> attributability() {
        HeldEntry.Grant membership = new HeldEntry.Grant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1);
        return Stream.of(
                Arguments.of("not read", null, null),
                Arguments.of("nothing held", new HeldEntry(X, List.of(), 0), true),
                Arguments.of("unattributed only", new HeldEntry(X, List.of(), 1), false),
                Arguments.of("granted", new HeldEntry(X, List.of(membership), 0), true),
                Arguments.of("mixed", new HeldEntry(X, List.of(membership), 1), false));
    }

    private static RemovalOutcome outcome(String publisher, String did, RemovalAction action) {
        return new RemovalOutcome(publisher, did, RemovalTrigger.EVENT, "report", action, false, null);
    }

    private static String did(int i) {
        return "did:web:participant-" + i + ".org";
    }
}
