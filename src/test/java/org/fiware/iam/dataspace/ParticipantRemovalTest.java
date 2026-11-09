package org.fiware.iam.dataspace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.ParticipantRemovalException;
import org.fiware.iam.exception.TirLookupException;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class ParticipantRemovalTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String GENERAL_DID = "did:web:general.org";
    private static final String ORDER = "order-1";
    private static final String REGISTRY_URL = "http://registry.p1.org";
    private static final int CREDENTIALS = 2;
    private static final String READ = "read";
    private static final String DELETE = "delete";

    private final TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
    private final TilCapabilities capabilities = mock(TilCapabilities.class);
    private final PublisherRegistryLookup registryLookup = mock(PublisherRegistryLookup.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RemovedParticipantsReport report = new RemovedParticipantsReport(new MutableClock(Instant.EPOCH));
    private final DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
    private final GeneralProperties generalProperties = new GeneralProperties();
    private final ParticipantRemoval removal = new ParticipantRemoval(adapter, capabilities, registryLookup, report,
            properties, generalProperties, Optional.of(meterRegistry));

    // the fake list: what it holds for X (null if nothing), writes failing once, and a change before the second read
    @Nullable
    private HeldEntry held;
    private final Map<String, RuntimeException> failOnce = new HashMap<>();
    private final List<String> reads = new ArrayList<>();
    @Nullable
    private UnaryOperator<HeldEntry> beforeSecondRead;

    @BeforeEach
    void fakeTil() {
        properties.setOwnParticipantId(OWN_ID);
        generalProperties.setDid(GENERAL_DID);
        when(capabilities.requireSupported()).thenReturn(Mono.empty());
        when(registryLookup.isListed(anyString(), anyString())).thenReturn(Mono.just(false));
        when(adapter.heldEntry(X)).thenAnswer(invocation -> Mono.defer(() -> {
            reads.add(READ);
            if (reads.size() == 2 && beforeSecondRead != null) {
                held = beforeSecondRead.apply(held);
            }
            return failed(READ).orElseGet(() -> Mono.just(Optional.ofNullable(held)));
        }));
        when(adapter.revokeGrant(eq(X), anyString(), any())).thenAnswer(invocation -> write((String) invocation.getArguments()[1],
                new RemovalPlan.RevokeGrant((String) invocation.getArguments()[1], (ScopeTypeVO) invocation.getArguments()[2])));
        when(adapter.revokeUnattributed(X)).thenAnswer(invocation -> write("unattributed", new RemovalPlan.RevokeUnattributed()));
        when(adapter.deleteIssuer(X)).thenAnswer(invocation -> Mono.defer(() -> failed(DELETE).orElseGet(() -> {
            held = null;
            return Mono.empty();
        })));
    }

    @Test
    void writesOrderGrantsFirstAndDeletesTheEmptiedIssuerLast() {
        configure(publisher(P1, RemovalMode.GRANTED));
        held = entry(0, membership(P1), order());

        RemovalOutcome outcome = apply(P1);

        InOrder inOrder = inOrder(capabilities, registryLookup, adapter);
        inOrder.verify(capabilities).requireSupported();
        inOrder.verify(registryLookup).isListed(REGISTRY_URL, X);
        inOrder.verify(adapter).heldEntry(X);
        inOrder.verify(adapter).revokeGrant(X, ORDER, null);
        inOrder.verify(adapter).heldEntry(X);
        inOrder.verify(adapter).deleteIssuer(X);
        verify(adapter, never()).revokeGrant(X, P1, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        assertNull(held);
        assertEquals(RemovalAction.REVOKED_GRANTED, outcome.action());
        assertEquals(RemovalPlan.emptyEntry(X), report.entries(P1).get(0).outcome().held());
        assertEquals(1, count(P1, "granted", RemovalAction.REVOKED_GRANTED));
    }

    @Test
    void cleanupLeavingAnotherGrantDoesNotDelete() {
        configure(publisher(P1, RemovalMode.DATASPACE), publisher(P2, RemovalMode.REPORT));
        held = entry(0, membership(P1), membership(P2));

        RemovalOutcome outcome = apply(P1);

        verify(adapter).revokeGrant(X, P1, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        verify(adapter, never()).deleteIssuer(X);
        assertEquals(entry(0, membership(P2)), held);
        assertEquals(entry(0, membership(P2)), outcome.held());
    }

    @Test
    void grantAppearingBeforeTheDeleteTurnsItBackIntoTheRevoke() {
        configure(publisher(P1, RemovalMode.DATASPACE));
        held = entry(0, membership(P1));
        beforeSecondRead = entry -> entry(0, membership(P1), order());

        apply(P1);

        verify(adapter).revokeGrant(X, P1, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        verify(adapter, never()).deleteIssuer(X);
        assertEquals(entry(0, order()), held);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RemovalMode.class, names = {"DATASPACE", "GRANTED"})
    void alreadyEmptyEntryIsDeletedInAWritingMode(RemovalMode mode) {
        configure(publisher(P1, mode));
        held = entry(0);

        apply(P1);

        verify(adapter).deleteIssuer(X);
        assertNull(held);
    }

    @Test
    void reportModeNeverWrites() {
        configure(publisher(P1, RemovalMode.REPORT));
        held = entry(0, membership(P1));

        RemovalOutcome outcome = apply(P1);

        assertEquals(RemovalAction.REPORTED, outcome.action());
        assertEquals(entry(0, membership(P1)), outcome.held());
        verify(adapter, never()).deleteIssuer(anyString());
        verify(adapter, never()).revokeGrant(anyString(), anyString(), any());
        verifyZeroInteractions(registryLookup);
        assertEquals(1, count(P1, "report", RemovalAction.REPORTED));
    }

    @Test
    void notHeldIsReportedWithNothingHeld() {
        configure(publisher(P1, RemovalMode.FULL));

        RemovalOutcome outcome = apply(P1);

        assertEquals(RemovalAction.NOT_HELD, outcome.action());
        assertEquals(RemovalPlan.emptyEntry(X), outcome.held());
        verify(adapter, never()).deleteIssuer(anyString());
    }

    @Test
    void failingReadWritesNothing() {
        configure(publisher(P1, RemovalMode.FULL));
        held = entry(0, membership(P1));
        failOnce.put(READ, tilFailure());

        ParticipantRemovalException e = assertThrows(ParticipantRemovalException.class, () -> apply(P1));

        assertInstanceOf(TrustedIssuersException.class, e.getCause());
        verify(adapter, never()).deleteIssuer(anyString());
        assertEquals(1, count(P1, "full", RemovalAction.FAILED));
        assertEquals(RemovalAction.FAILED, report.entries(P1).get(0).outcome().action());
    }

    @Test
    void failingFirstWriteKeepsTheMembershipAndTheRetryCompletes() {
        configure(publisher(P1, RemovalMode.GRANTED));
        held = entry(CREDENTIALS, membership(P1), order());
        failOnce.put(ORDER, tilFailure());

        assertThrows(ParticipantRemovalException.class, () -> apply(P1));

        verify(adapter, never()).revokeGrant(X, P1, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        assertEquals(entry(CREDENTIALS, membership(P1), order()), held);

        RemovalOutcome retried = apply(P1);

        assertEquals(entry(CREDENTIALS), held);
        assertEquals(RemovalAction.REVOKED_GRANTED, retried.action());
    }

    @Test
    void failingDeleteKeepsTheMembershipAndTheRetryCompletes() {
        configure(publisher(P1, RemovalMode.DATASPACE));
        held = entry(0, membership(P1));
        failOnce.put(DELETE, tilFailure());

        assertThrows(ParticipantRemovalException.class, () -> apply(P1));

        assertEquals(entry(0, membership(P1)), held);

        apply(P1);

        assertNull(held);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownIds")
    void ownIdIsNeverTouched(String description, @Nullable String ownParticipantId, String ownId, boolean viaOverride) {
        properties.setOwnParticipantId(ownParticipantId);
        DataSpaceEventsProperties.Publisher publisher = publisher(P1, viaOverride ? RemovalMode.REPORT : RemovalMode.FULL);
        if (viaOverride) {
            publisher.getOnParticipantRemoved().setOverrides(List.of(override(ownId, RemovalMode.FULL)));
        }
        configure(publisher);

        RemovalOutcome outcome = removal.apply(P1, ownId, RemovalTrigger.EVENT).block();

        assertEquals(RemovalAction.KEPT, outcome.action());
        verifyZeroInteractions(adapter, capabilities, registryLookup);
        assertEquals(1, count(P1, RemovalMode.KEEP_TAG_VALUE, RemovalAction.KEPT));
    }

    static Stream<Arguments> ownIds() {
        return Stream.of(true, false).flatMap(viaOverride -> Stream.of(
                Arguments.of("own-participant-id, override " + viaOverride, OWN_ID, OWN_ID, viaOverride),
                Arguments.of("general.did fallback, override " + viaOverride, null, GENERAL_DID, viaOverride)));
    }

    @Test
    void unverifiedCapabilityFailsBeforeAnyRead() {
        configure(publisher(P1, RemovalMode.REPORT));
        when(capabilities.requireSupported()).thenReturn(Mono.error(
                new TrustedIssuersException(FailureReason.TIL_CAPABILITY_UNVERIFIED, "unverified")));

        ParticipantRemovalException e = assertThrows(ParticipantRemovalException.class, () -> apply(P1));

        assertInstanceOf(TrustedIssuersException.class, e.getCause());
        verifyZeroInteractions(adapter);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tooOldTil")
    void tooOldTilIsDecidedByTheEventsOwnMode(String description, List<DataSpaceEventsProperties.Publisher> publishers,
                                              @Nullable RemovalAction expected) {
        configure(publishers.toArray(DataSpaceEventsProperties.Publisher[]::new));
        when(capabilities.requireSupported()).thenReturn(Mono.error(
                new TrustedIssuersException(FailureReason.TIL_TOO_OLD, "too old")));

        if (expected == null) {
            assertThrows(ParticipantRemovalException.class, () -> apply(P1));
        } else {
            RemovalOutcome outcome = apply(P1);
            assertEquals(expected, outcome.action());
            assertNull(outcome.held());
        }
        verifyZeroInteractions(adapter);
    }

    static Stream<Arguments> tooOldTil() {
        DataSpaceEventsProperties.Publisher overridden = publisher(P1, RemovalMode.DATASPACE);
        overridden.getOnParticipantRemoved().setOverrides(List.of(override(X, RemovalMode.REPORT)));
        return Stream.of(
                Arguments.of("report next to a writing publisher",
                        List.of(publisher(P1, RemovalMode.REPORT), publisher(P2, RemovalMode.DATASPACE)), RemovalAction.REPORTED),
                Arguments.of("report override of a writing publisher", List.of(overridden), RemovalAction.REPORTED),
                Arguments.of("writing mode", List.of(publisher(P1, RemovalMode.DATASPACE)), null));
    }

    @Test
    void reAdmittedParticipantIsNotWritten() {
        configure(publisher(P1, RemovalMode.FULL));
        held = entry(0, membership(P1));
        when(registryLookup.isListed(REGISTRY_URL, X)).thenReturn(Mono.just(true));

        RemovalOutcome outcome = apply(P1);

        assertEquals(RemovalAction.STILL_LISTED, outcome.action());
        assertNull(outcome.held());
        verifyZeroInteractions(adapter);
        assertEquals(1, count(P1, "full", RemovalAction.STILL_LISTED));
    }

    @Test
    void failingLookupFailsBeforeAnyTilCall() {
        configure(publisher(P1, RemovalMode.FULL));
        when(registryLookup.isListed(REGISTRY_URL, X)).thenReturn(Mono.error(new TirLookupException(X, REGISTRY_URL, 500, null)));

        ParticipantRemovalException e = assertThrows(ParticipantRemovalException.class, () -> apply(P1));

        assertInstanceOf(TirLookupException.class, e.getCause());
        verifyZeroInteractions(adapter);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withoutLookup")
    void looksUpOnlyEventsOfWritingModes(String description, DataSpaceEventsProperties.Publisher publisher,
                                         RemovalTrigger trigger, RemovalAction expected) {
        configure(publisher);
        held = entry(0, membership(P1));

        assertEquals(expected, removal.apply(P1, X, trigger).block().action());
        verifyZeroInteractions(registryLookup);
    }

    static Stream<Arguments> withoutLookup() {
        DataSpaceEventsProperties.Publisher withoutRegistry = publisher(P1, RemovalMode.FULL);
        withoutRegistry.getOnParticipantRemoved().setRegistryUrl(null);
        return Stream.of(
                Arguments.of("report", publisher(P1, RemovalMode.REPORT), RemovalTrigger.EVENT, RemovalAction.REPORTED),
                Arguments.of("writing mode without registry-url", withoutRegistry, RemovalTrigger.EVENT, RemovalAction.REPORTED),
                Arguments.of("reconciliation", publisher(P1, RemovalMode.FULL), RemovalTrigger.RECONCILIATION,
                        RemovalAction.DELETED_ISSUER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removedByP1HeldByBoth")
    void otherMembershipSurvivesEveryModeButFull(RemovalMode mode, @Nullable HeldEntry expectedHeld) {
        configure(publisher(P1, mode), publisher(P2, RemovalMode.REPORT));
        held = entry(0, membership(P1), membership(P2));

        apply(P1);

        assertEquals(expectedHeld, held);
    }

    static Stream<Arguments> removedByP1HeldByBoth() {
        return Stream.of(
                Arguments.of(RemovalMode.REPORT, entry(0, membership(P1), membership(P2))),
                Arguments.of(RemovalMode.DATASPACE, entry(0, membership(P2))),
                Arguments.of(RemovalMode.GRANTED, entry(0, membership(P2))),
                Arguments.of(RemovalMode.FULL, null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RemovalMode.class, names = {"DATASPACE", "FULL"})
    void keepOfOnePublisherProtectsAgainstAll(RemovalMode p2Mode) {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.REPORT);
        p1.getOnParticipantRemoved().setKeep(List.of(X));
        configure(p1, publisher(P2, p2Mode));
        held = entry(0, membership(P1), membership(P2));

        assertEquals(RemovalAction.KEPT, apply(P2).action());

        verifyZeroInteractions(adapter, capabilities, registryLookup);
        assertEquals(1, count(P2, RemovalMode.KEEP_TAG_VALUE, RemovalAction.KEPT));
    }

    @Test
    void reportOverrideStopsOnlyItsPublisher() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.FULL);
        p1.getOnParticipantRemoved().setOverrides(List.of(override(X, RemovalMode.REPORT)));
        configure(p1, publisher(P2, RemovalMode.FULL));
        held = entry(0, membership(P1), membership(P2));

        assertEquals(RemovalAction.REPORTED, apply(P1).action());
        verify(adapter, never()).deleteIssuer(anyString());

        assertEquals(RemovalAction.DELETED_ISSUER, apply(P2).action());
        assertNull(held);
    }

    @Test
    void unattributedOnlyEntryIsRevokedByEventWhenAdoptionAllowed() {
        DataSpaceEventsProperties.Publisher p1 = publisher(P1, RemovalMode.DATASPACE);
        p1.getOnParticipantRemoved().setAdoptUnattributed(true);
        properties.setDataspaceId("test-dataspace");
        configure(p1);
        held = entry(CREDENTIALS);

        assertEquals(RemovalAction.REVOKED_MEMBERSHIP, apply(P1).action());

        assertNull(held);
        assertTrue(report.entries(P1).get(0).outcome().applied());
    }

    private RemovalOutcome apply(String publisherId) {
        return removal.apply(publisherId, X, RemovalTrigger.EVENT).block();
    }

    private Mono<Void> write(String key, RemovalPlan.Operation operation) {
        return Mono.defer(() -> this.<Void>failed(key).orElseGet(() -> {
            if (held != null) {
                held = operation.applyTo(held);
            }
            return Mono.empty();
        }));
    }

    private <T> Optional<Mono<T>> failed(String key) {
        return Optional.ofNullable(failOnce.remove(key)).map(Mono::error);
    }

    private double count(String publisher, String mode, RemovalAction action) {
        Counter counter = meterRegistry.find(ParticipantRemoval.METRIC_NAME)
                .tags(ParticipantRemoval.PUBLISHER_TAG, publisher, ParticipantRemoval.MODE_TAG, mode,
                        ParticipantRemoval.ACTION_TAG, action.tagValue())
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private void configure(DataSpaceEventsProperties.Publisher... publishers) {
        properties.setPublishers(List.of(publishers));
    }

    private static DataSpaceEventsProperties.Publisher publisher(String id, RemovalMode mode) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE));
        publisher.getOnParticipantRemoved().setMode(mode);
        publisher.getOnParticipantRemoved().setRegistryUrl(REGISTRY_URL);
        return publisher;
    }

    private static DataSpaceEventsProperties.ModeOverride override(String id, RemovalMode mode) {
        DataSpaceEventsProperties.ModeOverride override = new DataSpaceEventsProperties.ModeOverride();
        override.setId(id);
        override.setMode(mode);
        return override;
    }

    private static TrustedIssuersException tilFailure() {
        return new TrustedIssuersException(FailureReason.TIL_READ_FAILED, "TIL down");
    }

    private static HeldEntry entry(int unattributed, HeldEntry.Grant... grants) {
        return new HeldEntry(X, List.of(grants), unattributed);
    }

    private static HeldEntry.Grant membership(String publisher) {
        return new HeldEntry.Grant(publisher, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1);
    }

    private static HeldEntry.Grant order() {
        return new HeldEntry.Grant(ORDER, null, 1);
    }
}
