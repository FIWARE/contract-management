package org.fiware.iam.dataspace;

import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.AdoptionState;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.BreakerState;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.HeldGrant;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.ParticipantReport;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.PublisherReport;
import org.fiware.iam.dataspace.RemovedParticipantsEndpoint.TilState;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TilCapability;
import org.fiware.iam.til.TilCapability.State;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.fiware.iam.configuration.DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks how the removed-participants report renders the publishers' policy state and each participant's outcome.
 */
class RemovedParticipantsEndpointTest {

    private static final String P1 = "did:web:anchor-1.org";
    private static final String P2 = "did:web:anchor-2.org";
    private static final String X = "did:web:removed-x.org";
    private static final String Y = "did:web:removed-y.org";
    private static final String REGISTRY_URL = "http://tir.anchor.org";
    private static final String DATASPACE_ID = "test-dataspace";
    private static final String ORDER_ID = "urn:ngsi-ld:product-order:1";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private DataSpaceEventsProperties properties;
    private RemovedParticipantsReport report;
    private TilCapabilities capabilities;
    private ParticipantReconciler reconciler;
    private RemovedParticipantsEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        properties.getListener().setEnabled(true);
        properties.setPublishers(new ArrayList<>(List.of(publisher(P1, RemovalMode.REPORT, null, false))));
        report = new RemovedParticipantsReport(Clock.fixed(NOW, ZoneOffset.UTC));
        capabilities = mock(TilCapabilities.class);
        reconciler = mock(ParticipantReconciler.class);
        when(capabilities.required()).thenReturn(Set.of(TilCapability.TYPED_SCOPES, TilCapability.SCOPES_ENDPOINT));
        when(capabilities.state()).thenReturn(Map.of(TilCapability.TYPED_SCOPES, State.SUPPORTED,
                TilCapability.SCOPES_ENDPOINT, State.SUPPORTED, TilCapability.UNSCOPED, State.SUPPORTED));
        when(reconciler.trippedBreakers()).thenReturn(Map.of());
        endpoint = new RemovedParticipantsEndpoint(properties, report, capabilities, reconciler);
    }

    static Stream<Arguments> tilStates() {
        return Stream.of(
                Arguments.of(State.SUPPORTED, State.SUPPORTED, TilState.SUPPORTED),
                Arguments.of(State.SUPPORTED, State.PENDING, TilState.PENDING),
                Arguments.of(State.UNVERIFIED, State.PENDING, TilState.UNVERIFIED),
                Arguments.of(State.TOO_OLD, State.UNVERIFIED, TilState.TOO_OLD),
                Arguments.of(State.SUPPORTED, State.TOO_OLD, TilState.TOO_OLD));
    }

    @ParameterizedTest(name = "typed-scopes {0}, scopes-endpoint {1} -> {2}")
    @MethodSource("tilStates")
    void tilIsTheLeastUsableRequiredCapability(State typedScopes, State scopesEndpoint, TilState expected) {
        // an unrequired capability never counts
        when(capabilities.state()).thenReturn(Map.of(TilCapability.TYPED_SCOPES, typedScopes,
                TilCapability.SCOPES_ENDPOINT, scopesEndpoint, TilCapability.UNSCOPED, State.TOO_OLD));

        assertEquals(expected, single().til());
    }

    static Stream<Arguments> adoptions() {
        return Stream.of(
                Arguments.of("not asked", List.of(publisher(P1, RemovalMode.REPORT, REGISTRY_URL, false)), DATASPACE_ID,
                        List.of(AdoptionState.OFF)),
                Arguments.of("allowed", List.of(publisher(P1, RemovalMode.REPORT, REGISTRY_URL, true)), DATASPACE_ID,
                        List.of(AdoptionState.ALLOWED)),
                Arguments.of("no dataspace-id", List.of(publisher(P1, RemovalMode.REPORT, REGISTRY_URL, true)), null,
                        List.of(AdoptionState.REFUSED)),
                Arguments.of("two publishers", List.of(publisher(P1, RemovalMode.REPORT, REGISTRY_URL, true),
                        publisher(P2, RemovalMode.REPORT, REGISTRY_URL, false)), DATASPACE_ID,
                        List.of(AdoptionState.REFUSED, AdoptionState.OFF)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adoptions")
    void adoptionIsShownForThePublisherAskingForIt(String description, List<DataSpaceEventsProperties.Publisher> publishers,
                                                   String dataspaceId, List<AdoptionState> expected) {
        properties.setPublishers(publishers);
        properties.setDataspaceId(dataspaceId);

        assertEquals(expected, endpoint.report().stream().map(PublisherReport::adoption).toList());
    }

    static Stream<Arguments> publisherModes() {
        return Stream.of(
                Arguments.of(RemovalMode.REPORT, REGISTRY_URL, "report"),
                Arguments.of(RemovalMode.GRANTED, REGISTRY_URL, "granted"),
                Arguments.of(RemovalMode.FULL, null, "report"));
    }

    @ParameterizedTest(name = "{0} with registry-url {1} -> {2}")
    @MethodSource("publisherModes")
    void modeIsThePublishersEffectiveMode(RemovalMode mode, String registryUrl, String expected) {
        properties.setPublishers(List.of(publisher(P1, mode, registryUrl, false)));

        assertEquals(expected, single().mode());
    }

    @Test
    void breakerIsShownPerPublisher() {
        properties.setPublishers(List.of(publisher(P1, RemovalMode.FULL, REGISTRY_URL, false),
                publisher(P2, RemovalMode.FULL, REGISTRY_URL, false)));
        when(reconciler.trippedBreakers()).thenReturn(Map.of(P2, new ParticipantReconciler.Breaker(Set.of(X, Y), 1)));

        assertEquals(List.of(BreakerState.CLEAR, BreakerState.TRIPPED),
                endpoint.report().stream().map(PublisherReport::breaker).toList());
    }

    static Stream<Arguments> participants() {
        HeldEntry mixed = new HeldEntry(X, List.of(new HeldEntry.Grant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP, 2),
                new HeldEntry.Grant(ORDER_ID, null, 1)), 3);
        return Stream.of(
                Arguments.of("not read", outcome(RemovalTrigger.EVENT, RemovalMode.KEEP_TAG_VALUE, RemovalAction.KEPT, false, null),
                        new ParticipantReport(X, "event", NOW, "keep", "kept", false, null, null, null)),
                Arguments.of("nothing held", outcome(RemovalTrigger.EVENT, "dataspace", RemovalAction.NOT_HELD, false,
                                new HeldEntry(X, List.of(), 0)),
                        new ParticipantReport(X, "event", NOW, "dataspace", "not_held", false, true, List.of(), 0)),
                Arguments.of("held in report mode", outcome(RemovalTrigger.RECONCILIATION, "report", RemovalAction.REPORTED,
                                false, mixed),
                        new ParticipantReport(X, "reconciliation", NOW, "report", "reported", false, false,
                                List.of(new HeldGrant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP, 2), new HeldGrant(ORDER_ID, null, 1)),
                                3)),
                Arguments.of("attributable", outcome(RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false,
                                new HeldEntry(X, List.of(new HeldEntry.Grant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1)), 0)),
                        new ParticipantReport(X, "event", NOW, "report", "reported", false, true,
                                List.of(new HeldGrant(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP, 1)), 0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("participants")
    void rendersTheParticipantsOutcome(String description, RemovalOutcome outcome, ParticipantReport expected) {
        report.record(outcome);

        assertEquals(List.of(expected), single().participants());
    }

    @Test
    void showsTheParticipantSeenMostRecentlyFirst() {
        report.record(new RemovalOutcome(P1, X, RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false, null));
        report.record(new RemovalOutcome(P1, Y, RemovalTrigger.EVENT, "report", RemovalAction.REPORTED, false, null));

        assertEquals(List.of(Y, X), single().participants().stream().map(ParticipantReport::id).toList());
    }

    @Test
    void showsOnlyConfiguredRemovalPublishers() {
        DataSpaceEventsProperties.Publisher other = publisher(P2, RemovalMode.REPORT, null, false);
        other.setTypes(List.of("org.fiware.dataspace.example"));
        properties.setPublishers(List.of(publisher(P1, RemovalMode.REPORT, null, false), other));

        assertEquals(List.of(P1), endpoint.report().stream().map(PublisherReport::publisher).toList());
    }

    private PublisherReport single() {
        List<PublisherReport> reports = endpoint.report();
        assertEquals(1, reports.size());
        return reports.get(0);
    }

    private static RemovalOutcome outcome(RemovalTrigger trigger, String modeTag, RemovalAction action, boolean applied,
                                          HeldEntry held) {
        return new RemovalOutcome(P1, X, trigger, modeTag, action, applied, held);
    }

    private static DataSpaceEventsProperties.Publisher publisher(String id, RemovalMode mode, String registryUrl,
                                                                 boolean adoptUnattributed) {
        DataSpaceEventsProperties.Publisher publisher = new DataSpaceEventsProperties.Publisher();
        publisher.setId(id);
        publisher.setTypes(List.of(PARTICIPANT_REMOVED_TYPE));
        publisher.getOnParticipantRemoved().setMode(mode);
        publisher.getOnParticipantRemoved().setRegistryUrl(registryUrl);
        publisher.getOnParticipantRemoved().setAdoptUnattributed(adoptUnattributed);
        return publisher;
    }
}
