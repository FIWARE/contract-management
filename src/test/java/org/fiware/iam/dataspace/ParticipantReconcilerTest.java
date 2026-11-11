package org.fiware.iam.dataspace;

import io.micronaut.context.env.Environment;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.PublisherEntryCheck;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TirEnumerationException;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * Checks the reconciliation sweep against a fake trusted-issuers-list and fake publisher registries.
 */
class ParticipantReconcilerTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final String Y = "did:web:y.org";
    private static final String Z = "did:web:z.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String ORDER = "order-1";
    private static final String PEER_TYPE = "org.fiware.dataspace.other";
    private static final Map<String, String> REGISTRY_URLS = Map.of(P1, "http://registry.p1.org", P2, "http://registry.p2.org");

    private final TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
    private final TilCapabilities capabilities = mock(TilCapabilities.class);
    private final PublisherRegistryLookup registryLookup = mock(PublisherRegistryLookup.class);
    private final TrustedIssuersRegistryReader registryReader = mock(TrustedIssuersRegistryReader.class);
    private final Environment environment = mock(Environment.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final RemovedParticipantsReport report = new RemovedParticipantsReport(clock);
    private final DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
    private final GeneralProperties generalProperties = new GeneralProperties();
    private final ParticipantRemoval removal = new ParticipantRemoval(adapter, capabilities, registryLookup, report,
            properties, generalProperties, Optional.of(meterRegistry));
    // lets a test land a refresh right after the sweep decided, before any write
    private Runnable refreshAfterDecision = () -> {
    };
    private int refreshesInjected;
    private boolean inRefreshLock;
    private final ConfigurationRefreshLock refreshLock = new ConfigurationRefreshLock() {
        @Override
        public <T> T exclusively(Supplier<T> work) {
            T result = super.exclusively(() -> {
                inRefreshLock = true;
                try {
                    return work.get();
                } finally {
                    inRefreshLock = false;
                }
            });
            // only the sweep's decision section, whatever else runs under the lock
            if (result instanceof ParticipantReconciler.Split) {
                refreshesInjected++;
                refreshAfterDecision.run();
            }
            return result;
        }
    };
    private final ParticipantReconciler reconciler = reconciler(Optional.empty());

    private final Logger logger = (Logger) LoggerFactory.getLogger(ParticipantReconciler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    // the fake list and registries
    private final Map<String, HeldEntry> til = new HashMap<>();
    private final Map<String, Set<String>> listed = new HashMap<>();
    private final Map<String, Integer> reads = new HashMap<>();

    @BeforeEach
    void fakes() {
        logged.start();
        logger.addAppender(logged);
        // an old Mockito answers Optional with null; no raw publisher entries unless a test sets them
        when(environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class)).thenReturn(Optional.empty());
        properties.setOwnParticipantId(OWN_ID);
        when(capabilities.requireSupported()).thenReturn(Mono.empty());
        when(registryReader.enumerate(anyString())).thenAnswer(invocation -> REGISTRY_URLS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(invocation.getArguments()[0]))
                .map(entry -> Set.copyOf(listed.getOrDefault(entry.getKey(), Set.of())))
                .findFirst()
                .orElseThrow());
        when(adapter.issuersGrantedBy(anyString(), eq(ScopeTypeVO.DATASPACE_MEMBERSHIP))).thenAnswer(invocation ->
                Mono.fromSupplier(() -> til.values().stream()
                        .filter(entry -> entry.membershipOf((String) invocation.getArguments()[0]).isPresent())
                        .map(HeldEntry::did)
                        .collect(Collectors.toSet())));
        when(adapter.heldEntry(anyString())).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            String did = (String) invocation.getArguments()[0];
            reads.merge(did, 1, Integer::sum);
            return Optional.ofNullable(til.get(did));
        }));
        when(adapter.revokeGrant(anyString(), anyString(), any())).thenAnswer(invocation -> Mono.fromRunnable(() -> {
            String did = (String) invocation.getArguments()[0];
            String scope = (String) invocation.getArguments()[1];
            HeldEntry entry = til.get(did);
            if (entry != null) {
                til.put(did, new HeldEntry(did, entry.grants().stream()
                        .filter(grant -> !(grant.scope().equals(scope) && grant.type() == invocation.getArguments()[2]))
                        .toList(), entry.unattributedCount()));
            }
        }));
        when(adapter.deleteIssuer(anyString())).thenAnswer(invocation ->
                Mono.fromRunnable(() -> til.remove((String) invocation.getArguments()[0])));
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logged);
    }

    static Stream<Arguments> writingModes() {
        return Stream.of(
                Arguments.of(RemovalMode.DATASPACE, RemovalAction.REVOKED_MEMBERSHIP),
                Arguments.of(RemovalMode.GRANTED, RemovalAction.REVOKED_GRANTED),
                Arguments.of(RemovalMode.FULL, RemovalAction.DELETED_ISSUER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writingModes")
    void appliesTheModeToARemovedIdentifierAndLeavesListedOnes(RemovalMode mode, RemovalAction expected) {
        configure(publisher(P1, mode));
        held(X, membership(P1), order());
        held(Y, membership(P1));
        listed.put(P1, Set.of(Y));

        reconciler.run();

        assertFalse(holdsMembership(X, P1));
        assertTrue(holdsMembership(Y, P1));
        assertEquals(1, count(P1, mode.tagValue(), expected));
        assertEquals(RemovalTrigger.RECONCILIATION, report.entries(P1).get(0).outcome().trigger());
        assertEquals(List.of(X), report.entries(P1).stream().map(entry -> entry.outcome().did()).toList());
        assertEquals(0, heldGauge(P1), "the gauge counts what is still held after the run's writes");
        verifyZeroInteractions(registryLookup);
    }

    static Stream<Arguments> keptIdentifiers() {
        return Stream.of(
                Arguments.of("own id", OWN_ID, List.of(), List.of()),
                Arguments.of("own keep list", X, List.of(X), List.of()),
                Arguments.of("another publisher's keep list", X, List.of(), List.of(X)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keptIdentifiers")
    void keptIdentifiersAreNeverTouchedAndCountedOnce(String description, String did, List<String> keepOfP1,
                                                       List<String> keepOfP2) {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.FULL);
        p1.getOnParticipantRemoved().setKeep(keepOfP1);
        DataSpaceEventsProperties.Publisher p2 = publisher(P2, RemovalMode.REPORT);
        p2.getOnParticipantRemoved().setKeep(keepOfP2);
        configure(p1, p2);
        held(did, membership(P1));

        reconciler.run();
        clock.advance(Duration.ofMinutes(1));
        reconciler.run();

        assertTrue(holdsMembership(did, P1));
        assertEquals(List.of(clock.instant()), report.entries(P1).stream().map(RemovedParticipantsReport.Entry::lastSeen).toList(),
                "a kept identifier seen again is touched, not counted again");
        verify(adapter, never()).heldEntry(did);
        verify(adapter, never()).deleteIssuer(anyString());
        assertEquals(1, count(P1, RemovalMode.KEEP_TAG_VALUE, RemovalAction.KEPT));
        assertEquals(did.equals(OWN_ID) ? 0 : 1, heldGauge(P1));
    }

    static Stream<Arguments> refreshedDecisions() {
        DataSpaceEventsProperties.Publisher keeping = publisher(P1, RemovalMode.FULL);
        keeping.getOnParticipantRemoved().setKeep(List.of(X));
        keeping.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        return Stream.of(
                Arguments.of("taken off keep", keeping, RemovalMode.KEEP_TAG_VALUE, RemovalAction.KEPT, true),
                Arguments.of("mode raised", publisher(P1, RemovalMode.DATASPACE), "dataspace",
                        RemovalAction.REVOKED_MEMBERSHIP, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refreshedDecisions")
    void aRefreshDuringTheRunCannotOvertakeItsDecision(String description, DataSpaceEventsProperties.Publisher before,
                                                       String expectedMode, RemovalAction expectedAction,
                                                       boolean membershipKept) {
        configure(before);
        held(X, membership(P1), order());
        DataSpaceEventsProperties.Publisher after = publisher(P1, RemovalMode.FULL);
        after.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        refreshAfterDecision = () -> configure(after);

        reconciler.run();

        assertEquals(1, refreshesInjected, "the refresh was not injected after the decision");
        verify(adapter, never()).deleteIssuer(anyString());
        assertEquals(membershipKept, holdsMembership(X, P1));
        assertEquals(1, count(P1, expectedMode, expectedAction));
        assertEquals(0, count(P1, "full", RemovalAction.DELETED_ISSUER));
    }

    static Stream<Arguments> registryChangesDuringTheListing() {
        DataSpaceEventsProperties.Publisher moved = publisher(P1, RemovalMode.FULL);
        moved.getOnParticipantRemoved().setRegistryUrl(REGISTRY_URLS.get(P2));
        DataSpaceEventsProperties.Publisher dropped = publisher(P1, RemovalMode.FULL);
        dropped.getOnParticipantRemoved().setRegistryUrl(null);
        DataSpaceEventsProperties.Publisher peer = publisher(P1, RemovalMode.FULL);
        peer.setTypes(List.of(PEER_TYPE));
        return Stream.of(
                Arguments.of("registry-url corrected", moved),
                Arguments.of("registry-url removed", dropped),
                Arguments.of("publisher no longer announces removals", peer));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registryChangesDuringTheListing")
    void aRunListedFromAReplacedRegistryWritesNothing(String description, DataSpaceEventsProperties.Publisher after) {
        configure(publisher(P1, RemovalMode.FULL));
        held(X, membership(P1));
        when(registryReader.enumerate(REGISTRY_URLS.get(P1))).thenAnswer(invocation -> {
            configure(after);
            return Set.of();
        });

        reconciler.run();

        assertTrue(holdsMembership(X, P1));
        verify(adapter, never()).deleteIssuer(anyString());
        verify(adapter, never()).heldEntry(anyString());
        assertTrue(meterRegistry.find(ParticipantRemoval.METRIC_NAME).counters().isEmpty());
    }

    @Test
    void aPublisherRemovedDuringTheListingStopsExportingItsDrift() {
        configure(publisher(P1, RemovalMode.REPORT));
        held(X, membership(P1));
        reconciler.run();
        assertEquals(1, heldGauge(P1));

        DataSpaceEventsProperties.Publisher peer = publisher(P1, RemovalMode.REPORT);
        peer.setTypes(List.of(PEER_TYPE));
        when(registryReader.enumerate(REGISTRY_URLS.get(P1))).thenAnswer(invocation -> {
            configure(peer);
            return Set.of();
        });
        reconciler.run();

        assertEquals(0, heldGauge(P1));
        assertEquals(1, count(P1, "report", RemovalAction.REPORTED));
        assertTrue(dids(P1).isEmpty(), "its drift is no longer reported");
    }

    static Stream<Arguments> breakerCaps() {
        return Stream.of(
                Arguments.of(2, 2, false),
                Arguments.of(2, 3, true),
                Arguments.of(0, 1, true),
                Arguments.of(0, 0, false));
    }

    @ParameterizedTest(name = "cap {0}, {1} write candidates -> tripped {2}")
    @MethodSource("breakerCaps")
    void breakerTripsAboveTheCap(int cap, int writeCandidates, boolean tripped) {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(cap);
        configure(p1);
        List<String> dids = dids(writeCandidates);
        dids.forEach(did -> held(did, membership(P1)));

        reconciler.run();

        assertEquals(tripped, reconciler.trippedBreakers().containsKey(P1));
        assertEquals(tripped ? 1 : 0, count(P1, "dataspace", RemovalAction.BREAKER));
        dids.forEach(did -> assertEquals(tripped, holdsMembership(did, P1)));
        assertEquals(tripped ? writeCandidates : 0, count(P1, "dataspace", RemovalAction.REPORTED));
        assertEquals(tripped ? writeCandidates : 0, heldGauge(P1));
    }

    @Test
    void breakerAlertNamesTheCountAndABoundedSample() {
        int writeCandidates = ParticipantReconciler.BREAKER_LOG_SAMPLE_SIZE + 5;
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(1);
        configure(p1);
        List<String> dids = dids(writeCandidates);
        dids.forEach(did -> held(did, membership(P1)));

        reconciler.run();

        String alert = logs(Level.ERROR).get(0).getFormattedMessage();
        assertTrue(alert.contains(writeCandidates + " participants to remove"));
        assertTrue(alert.contains(dids.get(ParticipantReconciler.BREAKER_LOG_SAMPLE_SIZE - 1)));
        assertFalse(alert.contains(dids.get(ParticipantReconciler.BREAKER_LOG_SAMPLE_SIZE)));
        assertTrue(alert.endsWith("…"));
        assertEquals(writeCandidates, reconciler.trippedBreakers().get(P1).writeCandidates().size());
    }

    @Test
    void reportCandidatesBeyondTheCapDoNotTripTheBreaker() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(1);
        p1.getOnParticipantRemoved().setOverrides(List.of(override(X, RemovalMode.REPORT), override(Y, RemovalMode.REPORT)));
        configure(p1);
        held(X, membership(P1));
        held(Y, membership(P1));
        held(Z, membership(P1));

        reconciler.run();

        assertTrue(reconciler.trippedBreakers().isEmpty());
        assertFalse(holdsMembership(Z, P1));
        assertTrue(holdsMembership(X, P1));
        assertEquals(2, count(P1, "report", RemovalAction.REPORTED));
    }

    @Test
    void reportOnlyPublisherNeverTripsTheBreaker() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.REPORT);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        configure(p1);
        dids(3).forEach(did -> held(did, membership(P1)));

        reconciler.run();

        assertTrue(reconciler.trippedBreakers().isEmpty());
        assertEquals(0, count(P1, "report", RemovalAction.BREAKER));
    }

    @Test
    void standingTripAlertsOnceAndClearingIsLogged() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(1);
        configure(p1);
        held(X, membership(P1));
        held(Y, membership(P1));

        reconciler.run();
        reconciler.run();
        reconciler.run();

        assertEquals(1, logs(Level.ERROR).size());
        assertEquals(1, count(P1, "dataspace", RemovalAction.BREAKER));
        assertEquals(Map.of(X, 1, Y, 1), reads, "a tripped breaker reads only candidates not seen before");
        assertEquals(2, count(P1, "dataspace", RemovalAction.REPORTED));
        verify(adapter, never()).revokeGrant(anyString(), anyString(), any());

        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(2);
        reconciler.run();

        assertTrue(reconciler.trippedBreakers().isEmpty());
        assertTrue(logs(Level.INFO).stream().anyMatch(event -> event.getFormattedMessage().contains("breaker cleared")));
        assertFalse(holdsMembership(X, P1));
        assertFalse(holdsMembership(Y, P1));
    }

    @Test
    void withheldCandidateGoneBeforeItsReadIsReportedNotHeldAndNotReadAgain() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        configure(p1);
        held(X, membership(P1));
        // deleted at the list between the listing of P1's members and the read
        when(adapter.heldEntry(X)).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            reads.merge(X, 1, Integer::sum);
            return Optional.empty();
        }));

        reconciler.run();
        reconciler.run();

        RemovedParticipantsReport.Entry entry = report.entries(P1).get(0);
        assertEquals(RemovalAction.NOT_HELD, entry.outcome().action());
        assertFalse(entry.outcome().applied());
        assertEquals(List.of(), entry.outcome().held().grants());
        assertEquals(0, entry.outcome().held().unattributedCount());
        assertEquals(1, count(P1, "dataspace", RemovalAction.NOT_HELD));
        assertEquals(Map.of(X, 1), reads, "a seen candidate is not read again");
        assertTrue(holdsMembership(X, P1), "a tripped breaker writes nothing");
    }

    @Test
    void changedCandidateSetRaisesTheAlertAgain() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        configure(p1);
        held(X, membership(P1));
        reconciler.run();

        held(Y, membership(P1));
        reconciler.run();

        assertEquals(2, logs(Level.ERROR).size());
        assertEquals(2, count(P1, "dataspace", RemovalAction.BREAKER));
    }

    @Test
    void reportDriftIsCountedAndReadOnceUntilItsModeChanges() {
        configure(publisher(P1, RemovalMode.REPORT));
        held(X, membership(P1));
        held(Y, membership(P1));
        held(OWN_ID, membership(P1));
        listed.put(P1, Set.of(Y));

        reconciler.run();
        reconciler.run();
        reconciler.run();

        assertEquals(1, count(P1, "report", RemovalAction.REPORTED));
        assertEquals(Map.of(X, 1), reads);
        assertEquals(1, heldGauge(P1));
        assertTrue(holdsMembership(X, P1));

        // a writing mode the breaker holds back is a new decision for the same identifier
        DataSpaceEventsProperties.Publisher refreshed = publisher(P1, RemovalMode.DATASPACE);
        refreshed.getOnParticipantRemoved().setMaxRemovalsPerRun(0);
        configure(refreshed);
        reconciler.run();
        reconciler.run();

        assertEquals(1, count(P1, "dataspace", RemovalAction.REPORTED));
        assertEquals(Map.of(X, 2), reads);
    }

    @Test
    void everyCandidateIsCountedAndReadExactlyOnceBeyondTheReportCapacity() {
        configure(publisher(P1, RemovalMode.REPORT));
        int candidates = RemovedParticipantsReport.MAX_ENTRIES_PER_PUBLISHER + 1;
        List<String> dids = dids(candidates);
        dids.forEach(did -> held(did, membership(P1)));
        int runsNeeded = (candidates + ParticipantReconciler.MAX_NEW_CANDIDATES_PER_RUN - 1)
                / ParticipantReconciler.MAX_NEW_CANDIDATES_PER_RUN;

        reconciler.run();
        assertEquals(ParticipantReconciler.MAX_NEW_CANDIDATES_PER_RUN, reads.size(), "reads per run are bounded");

        for (int run = 1; run < runsNeeded + 2; run++) {
            reconciler.run();
        }

        assertEquals(candidates, count(P1, "report", RemovalAction.REPORTED));
        assertEquals(Set.of(1), new HashSet<>(reads.values()));
        assertEquals(candidates, reads.size());
        assertEquals(candidates, heldGauge(P1));
    }

    @Test
    void candidateLeavingAndComingBackCountsAgain() {
        configure(publisher(P1, RemovalMode.REPORT));
        held(X, membership(P1));

        reconciler.run();
        listed.put(P1, Set.of(X));
        reconciler.run();
        assertEquals(0, heldGauge(P1));
        listed.put(P1, Set.of());
        reconciler.run();

        assertEquals(2, count(P1, "report", RemovalAction.REPORTED));
    }

    @Test
    void reportDropsDriftThatIsListedAgainButKeepsAppliedAndEventEntries() {
        configure(publisher(P1, RemovalMode.REPORT), publisher(P2, RemovalMode.DATASPACE));
        held(X, membership(P1));
        held(Y, membership(P2));
        report.record(new RemovalOutcome(P1, Z, RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false, null));
        reconciler.run();
        assertEquals(List.of(Z, X), dids(P1));
        assertEquals(List.of(Y), dids(P2));

        listed.put(P1, Set.of(X));
        reconciler.run();

        assertEquals(List.of(Z), dids(P1), "re-admitted X is no longer shown as drift, the event entry stays");
        assertEquals(List.of(Y), dids(P2), "the applied removal stays as history");
    }

    @Test
    void unattributedOnlyEntryIsNotTouched() {
        configure(publisher(P1, RemovalMode.FULL));
        til.put(X, new HeldEntry(X, List.of(), 2));

        reconciler.run();

        assertEquals(new HeldEntry(X, List.of(), 2), til.get(X));
        verify(adapter, never()).heldEntry(X);
        verify(adapter, never()).revokeUnattributed(anyString());
        verify(adapter, never()).deleteIssuer(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"TIL_TOO_OLD", "TIL_CAPABILITY_UNVERIFIED"})
    void runIsSkippedUntilTheListIsUsable(FailureReason reason) {
        configure(publisher(P1, RemovalMode.DATASPACE));
        held(X, membership(P1));
        when(capabilities.requireSupported()).thenReturn(Mono.error(new TrustedIssuersException(reason, "not usable")));

        reconciler.run();
        reconciler.run();

        assertTrue(holdsMembership(X, P1));
        verify(adapter, never()).issuersGrantedBy(anyString(), any());
        assertEquals(1, logs(Level.WARN).size(), "a standing skip is logged once");

        when(capabilities.requireSupported()).thenReturn(Mono.empty());
        reconciler.run();

        assertFalse(holdsMembership(X, P1));
    }

    @Test
    void incompleteRegistrySkipsOnlyItsPublisher() {
        configure(publisher(P1, RemovalMode.DATASPACE), publisher(P2, RemovalMode.DATASPACE));
        held(X, membership(P1));
        held(Y, membership(P2));
        when(registryReader.enumerate(REGISTRY_URLS.get(P1)))
                .thenThrow(new TirEnumerationException("Page 2 answered without items.", 2, null, null));

        reconciler.run();

        assertTrue(holdsMembership(X, P1));
        assertFalse(holdsMembership(Y, P2));
        assertEquals(1, count(P1, "dataspace", RemovalAction.FAILED));
    }

    @Test
    void failingCandidateDoesNotStopTheOthers() {
        configure(publisher(P1, RemovalMode.DATASPACE));
        held(X, membership(P1));
        held(Y, membership(P1));
        when(adapter.heldEntry(X)).thenReturn(Mono.error(new TrustedIssuersException(FailureReason.TIL_TOO_OLD, "broken")));

        reconciler.run();

        assertTrue(holdsMembership(X, P1));
        assertFalse(holdsMembership(Y, P1));
        assertEquals(1, count(P1, "dataspace", RemovalAction.FAILED));
        assertEquals(1, heldGauge(P1), "a failed candidate still holds the membership");
        assertEquals(1, logs(Level.WARN).size());
        assertTrue(logs(Level.WARN).get(0).getThrowableProxy() != null, "the exception is attached");
    }

    static Stream<Arguments> publishersNoLongerReconciled() {
        return Stream.of(
                Arguments.of("registry-url removed",
                        (Consumer<DataSpaceEventsProperties.Publisher>) p -> p.getOnParticipantRemoved().setRegistryUrl(null)),
                Arguments.of("publisher no longer announces removals",
                        (Consumer<DataSpaceEventsProperties.Publisher>) p -> p.setTypes(List.of(PEER_TYPE))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishersNoLongerReconciled")
    void publisherNoLongerReconciledStopsExportingItsDrift(String description,
                                                          Consumer<DataSpaceEventsProperties.Publisher> refresh) {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.REPORT);
        configure(p1);
        held(X, membership(P1));
        report.record(new RemovalOutcome(P1, Z, RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false, null));
        reconciler.run();
        assertEquals(1, heldGauge(P1));
        assertEquals(List.of(Z, X), dids(P1));

        refresh.accept(p1);
        reconciler.run();

        assertEquals(0, heldGauge(P1));
        assertEquals(List.of(Z), dids(P1), "nobody re-checks X any more, the event entry stays as history");
    }

    @Test
    void reportOnlyPublisherWithoutRegistryUrlIsSkipped() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.REPORT);
        p1.getOnParticipantRemoved().setRegistryUrl(null);
        configure(p1);
        held(X, membership(P1));

        reconciler.run();

        verify(adapter, never()).issuersGrantedBy(anyString(), any());
        verify(registryReader, never()).enumerate(anyString());
        assertTrue(logs(Level.ERROR).isEmpty());
    }

    @Test
    void writingModeWithoutRegistryUrlLogsOncePerConfigurationAndActsAsReport() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.FULL);
        p1.getOnParticipantRemoved().setRegistryUrl(null);
        configure(p1);
        held(X, membership(P1));

        reconciler.run();
        reconciler.run();
        assertEquals(1, logs(Level.ERROR).size());

        p1.getOnParticipantRemoved().setKeep(List.of(Y));
        reconciler.run();

        assertEquals(2, logs(Level.ERROR).size());
        assertTrue(logs(Level.ERROR).get(0).getFormattedMessage().contains("never completed"));
        assertEquals(Optional.of(RemovalMode.REPORT), properties.removalMode(P1, X, OWN_ID));
        assertTrue(holdsMembership(X, P1));
        verify(adapter, never()).issuersGrantedBy(anyString(), any());
    }

    @Test
    void logsAPublisherEntryDroppedByARefreshOncePerConfigurationChange() {
        configure(publisher(P1, RemovalMode.REPORT));
        Map<String, Object> bound = Map.of("id", P1, "types", List.of(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE));
        Map<String, Object> dropped = Map.of("id", P2, "types", List.of(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE),
                "on-participant-removed", Map.of("mode", "dataspce"));
        when(environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class))
                .thenReturn(Optional.of(List.of(bound, dropped)));

        reconciler.run();
        reconciler.run();

        assertEquals(1, logs(Level.ERROR).size());
        assertTrue(logs(Level.ERROR).get(0).getFormattedMessage().contains("publishers[1] (id " + P2 + ")"));

        when(environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class))
                .thenReturn(Optional.of(List.of(dropped, bound)));
        reconciler.run();

        assertEquals(2, logs(Level.ERROR).size());
        assertTrue(logs(Level.ERROR).get(1).getFormattedMessage().contains("publishers[0] (id " + P2 + ")"));
    }

    @Test
    void readsThePublisherEntriesUnderTheRefreshLock() {
        configure(publisher(P1, RemovalMode.REPORT));
        List<Boolean> readsInLock = new ArrayList<>();
        when(environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class)).thenAnswer(invocation -> {
            readsInLock.add(inRefreshLock);
            return Optional.empty();
        });

        reconciler.run();

        assertEquals(List.of(true), readsInLock);
    }

    @Test
    void doesNotRepeatTheFindingsLoggedAtStartup() {
        configure(publisher(P1, RemovalMode.REPORT));
        Map<String, Object> bound = Map.of("id", P1, "types", List.of(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE));
        Map<String, Object> droppedPeer = Map.of("id", P2, "types", List.of("org.fiware.dataspace.example"),
                "max-age", "forever");
        when(environment.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class))
                .thenReturn(Optional.of(List.of(bound, droppedPeer)));
        ParticipantReconciler seeded = reconciler(Optional.of(new PublisherEntriesGuard(properties, environment)));

        seeded.run();

        assertTrue(logs(Level.WARN).stream().noneMatch(event -> event.getFormattedMessage().contains("(id " + P2 + ")")));
    }

    private ParticipantReconciler reconciler(Optional<PublisherEntriesGuard> entriesGuard) {
        return new ParticipantReconciler(adapter, capabilities, registryReader, removal,
                new ParticipantAdoption(adapter, removal, registryLookup), report, properties, generalProperties, refreshLock,
                environment, entriesGuard, Optional.of(meterRegistry), clock);
    }

    private void configure(DataSpaceEventsProperties.Publisher... publishers) {
        properties.setPublishers(List.of(publishers));
    }

    private static DataSpaceEventsProperties.Publisher publisher(String id, RemovalMode mode) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE));
        publisher.getOnParticipantRemoved().setMode(mode);
        publisher.getOnParticipantRemoved().setRegistryUrl(REGISTRY_URLS.get(id));
        return publisher;
    }

    private static DataSpaceEventsProperties.ModeOverride override(String id, RemovalMode mode) {
        DataSpaceEventsProperties.ModeOverride override = new DataSpaceEventsProperties.ModeOverride();
        override.setId(id);
        override.setMode(mode);
        return override;
    }

    private void held(String did, HeldEntry.Grant... grants) {
        til.put(did, new HeldEntry(did, List.of(grants), 0));
    }

    private boolean holdsMembership(String did, String publisherId) {
        HeldEntry entry = til.get(did);
        return entry != null && entry.membershipOf(publisherId).isPresent();
    }

    private static HeldEntry.Grant membership(String publisher) {
        return new HeldEntry.Grant(publisher, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1);
    }

    private static HeldEntry.Grant order() {
        return new HeldEntry.Grant(ORDER, null, 1);
    }

    private static List<String> dids(int count) {
        return new ArrayList<>(IntStream.range(0, count).mapToObj(i -> "did:web:participant-%04d.org".formatted(i)).toList());
    }

    private List<String> dids(String publisher) {
        return report.entries(publisher).stream().map(entry -> entry.outcome().did()).toList();
    }

    private List<ILoggingEvent> logs(Level level) {
        return logged.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private double count(String publisher, String mode, RemovalAction action) {
        Counter counter = meterRegistry.find(ParticipantRemoval.METRIC_NAME)
                .tags(ParticipantRemoval.PUBLISHER_TAG, publisher, ParticipantRemoval.MODE_TAG, mode,
                        ParticipantRemoval.ACTION_TAG, action.tagValue())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double heldGauge(String publisher) {
        Gauge gauge = meterRegistry.find(ParticipantReconciler.HELD_METRIC_NAME)
                .tags(ParticipantRemoval.PUBLISHER_TAG, publisher)
                .gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
