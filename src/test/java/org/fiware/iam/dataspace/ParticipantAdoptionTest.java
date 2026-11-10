package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.configuration.RemovalMode;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.HeldEntry;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks adopt-unattributed in the sweep and the event path against a fake trusted-issuers-list that keeps credentials.
 */
class ParticipantAdoptionTest {

    private static final String P1 = "did:web:p1.org";
    private static final String P2 = "did:web:p2.org";
    private static final String X = "did:web:x.org";
    private static final String Y = "did:web:y.org";
    private static final String Z = "did:web:z.org";
    private static final String OWN_ID = "did:web:own.org";
    private static final String DATASPACE_ID = "dataspace-1";
    private static final String ORDER = "order-1";
    private static final String PEER_TYPE = "org.fiware.dataspace.other";
    private static final Map<String, String> REGISTRY_URLS = Map.of(P1, "http://registry.p1.org", P2, "http://registry.p2.org");
    private static final List<CredentialsVO> CREDENTIALS = List.of(credential("MembershipCredential"),
            credential("OperatorCredential"));

    private final TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
    private final TilCapabilities capabilities = mock(TilCapabilities.class);
    private final PublisherRegistryLookup registryLookup = mock(PublisherRegistryLookup.class);
    private final TrustedIssuersRegistryReader registryReader = mock(TrustedIssuersRegistryReader.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final RemovedParticipantsReport report = new RemovedParticipantsReport(clock);
    private final DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
    private final GeneralProperties generalProperties = new GeneralProperties();
    private final ParticipantRemoval removal = new ParticipantRemoval(adapter, capabilities, registryLookup, report,
            properties, generalProperties, Optional.of(meterRegistry));
    private final ParticipantReconciler reconciler = new ParticipantReconciler(adapter, capabilities, registryReader,
            removal, new ParticipantAdoption(adapter, removal, report), report, properties, generalProperties,
            new ConfigurationRefreshLock(), Optional.of(meterRegistry), clock);

    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(ParticipantReconciler.class),
            (Logger) LoggerFactory.getLogger(ParticipantAdoption.class));
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    // the fake list: per issuer its grants by (scope, type) and its unattributed credentials
    private final Map<String, Map<GrantKey, List<CredentialsVO>>> grants = new HashMap<>();
    private final Map<String, List<CredentialsVO>> unattributed = new HashMap<>();
    private final Map<String, Set<String>> listed = new HashMap<>();
    // run right after a membership grant was written, to change the entry behind the adoption's back
    private Consumer<String> afterGrant = did -> {
    };

    private record GrantKey(String scope, ScopeTypeVO type) {
    }

    @BeforeEach
    void fakes() {
        logged.start();
        loggers.forEach(logger -> logger.addAppender(logged));
        properties.setOwnParticipantId(OWN_ID);
        properties.setDataspaceId(DATASPACE_ID);
        when(capabilities.requireSupported()).thenReturn(Mono.empty());
        when(registryLookup.isListed(anyString(), anyString())).thenReturn(Mono.just(false));
        when(registryReader.enumerate(anyString())).thenAnswer(invocation -> REGISTRY_URLS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(invocation.getArguments()[0]))
                .map(entry -> Set.copyOf(listed.getOrDefault(entry.getKey(), Set.of())))
                .findFirst()
                .orElseThrow());
        when(adapter.issuersGrantedBy(anyString(), eq(ScopeTypeVO.DATASPACE_MEMBERSHIP))).thenAnswer(invocation ->
                Mono.fromSupplier(() -> {
                    GrantKey key = new GrantKey((String) invocation.getArguments()[0], ScopeTypeVO.DATASPACE_MEMBERSHIP);
                    Set<String> granted = new HashSet<>();
                    grants.forEach((did, held) -> {
                        if (held.containsKey(key)) {
                            granted.add(did);
                        }
                    });
                    return granted;
                }));
        when(adapter.allIssuers()).thenAnswer(invocation -> Mono.fromSupplier(() -> {
            Set<String> all = new HashSet<>(grants.keySet());
            all.addAll(unattributed.keySet());
            return all;
        }));
        when(adapter.heldEntry(anyString())).thenAnswer(invocation ->
                Mono.fromSupplier(() -> heldEntry((String) invocation.getArguments()[0])));
        when(adapter.credentials(anyString())).thenAnswer(invocation ->
                Mono.fromSupplier(() -> credentialsOf((String) invocation.getArguments()[0])));
        when(adapter.grantMembership(anyString(), anyString(), any())).thenAnswer(invocation -> Mono.fromRunnable(() -> {
            String did = (String) invocation.getArguments()[0];
            @SuppressWarnings("unchecked")
            List<CredentialsVO> credentials = (List<CredentialsVO>) invocation.getArguments()[2];
            grants.computeIfAbsent(did, id -> new LinkedHashMap<>()).put(
                    new GrantKey((String) invocation.getArguments()[1], ScopeTypeVO.DATASPACE_MEMBERSHIP), List.copyOf(credentials));
            afterGrant.accept(did);
        }));
        when(adapter.revokeUnattributed(anyString())).thenAnswer(invocation -> Mono.fromRunnable(() ->
                unattributed.remove((String) invocation.getArguments()[0])));
        when(adapter.revokeGrant(anyString(), anyString(), any())).thenAnswer(invocation -> Mono.fromRunnable(() ->
                Optional.ofNullable(grants.get((String) invocation.getArguments()[0])).ifPresent(held -> held.remove(
                        new GrantKey((String) invocation.getArguments()[1], (ScopeTypeVO) invocation.getArguments()[2])))));
        when(adapter.deleteIssuer(anyString())).thenAnswer(invocation -> Mono.fromRunnable(() -> {
            grants.remove((String) invocation.getArguments()[0]);
            unattributed.remove((String) invocation.getArguments()[0]);
        }));
    }

    @AfterEach
    void detach() {
        loggers.forEach(logger -> logger.detachAppender(logged));
    }

    @Test
    void sweepAdoptsAListedUnattributedOnlyEntry() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));

        reconciler.run();

        assertEquals(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS), grants.get(X),
                "the credentials are now P1's membership grant");
        assertFalse(unattributed.containsKey(X), "the unattributed originals are revoked");
        verify(adapter).grantMembership(X, P1, CREDENTIALS);
        assertEquals(1, count(P1, "report", RemovalAction.ADOPTED));
        RemovalOutcome outcome = report.entries(P1).get(0).outcome();
        assertEquals(RemovalAction.ADOPTED, outcome.action());
        assertTrue(outcome.applied(), "an adoption is history, not standing drift");
    }

    @Test
    void eventRemovesAnUnattributedOnlyEntryWhenAdoptionIsAllowed() {
        DataSpaceEventsProperties.Publisher p1 = adopting(P1);
        p1.getOnParticipantRemoved().setMode(RemovalMode.DATASPACE);
        configure(p1);
        unattributed.put(X, CREDENTIALS);

        RemovalOutcome outcome = removal.apply(P1, X, RemovalTrigger.EVENT).block();

        assertEquals(RemovalAction.REVOKED_MEMBERSHIP, outcome.action());
        assertEquals(Optional.empty(), heldEntry(X), "the announcement attributes the entry to P1, nothing stays empty");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusingConfigurations")
    void refusedAdoptionSaysSoAndWritesNothing(String description, Consumer<DataSpaceEventsProperties> configuration,
                                              String reason) {
        configuration.accept(properties);
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));

        reconciler.run();
        reconciler.run();

        List<ILoggingEvent> warnings = logs(Level.WARN).stream()
                .filter(event -> event.getFormattedMessage().contains("adopt-unattributed"))
                .toList();
        assertEquals(1, warnings.size(), "warned once per configuration, not per run");
        assertTrue(warnings.get(0).getFormattedMessage().contains(reason), warnings.get(0).getFormattedMessage());
        assertEquals(1, count(P1, "dataspace", RemovalAction.ADOPTION_REFUSED));
        verify(adapter, never()).grantMembership(anyString(), anyString(), any());
        verify(adapter, never()).allIssuers();

        removal.apply(P1, X, RemovalTrigger.EVENT).block();
        assertEquals(CREDENTIALS, unattributed.get(X), "without adoption an event leaves unattributed credentials alone");
        verify(adapter, never()).revokeUnattributed(anyString());
    }

    static Stream<Arguments> refusingConfigurations() {
        return Stream.of(
                Arguments.of("two removal publishers", (Consumer<DataSpaceEventsProperties>) properties ->
                                properties.setPublishers(List.of(writing(adopting(P1)), publisher(P2, RemovalMode.REPORT))),
                        "exactly one publisher"),
                Arguments.of("no dataspace-id", (Consumer<DataSpaceEventsProperties>) properties -> {
                    properties.setDataspaceId(null);
                    properties.setPublishers(List.of(writing(adopting(P1))));
                }, "dataspace-id"));
    }

    @Test
    void changedConfigurationWarnsAgain() {
        configure(writing(adopting(P1)), publisher(P2, RemovalMode.REPORT));
        reconciler.run();
        configure(writing(adopting(P1)));
        properties.setDataspaceId(null);
        reconciler.run();

        assertEquals(2, logs(Level.WARN).stream()
                .filter(event -> event.getFormattedMessage().contains("adopt-unattributed"))
                .count());
    }

    @Test
    void peerPublishingOtherTypesDoesNotRefuseAdoption() {
        DataSpaceEventsProperties.Publisher peer = publisher(P2, RemovalMode.REPORT);
        peer.setTypes(List.of(PEER_TYPE));
        configure(adopting(P1), peer);
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));

        reconciler.run();

        assertFalse(unattributed.containsKey(X));
        assertTrue(logs(Level.WARN).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedIdentifiers")
    void protectedEntryIsNotAdopted(String description, Consumer<ParticipantAdoptionTest> protect) {
        configure(adopting(P1));
        protect.accept(this);
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));

        reconciler.run();

        assertEquals(CREDENTIALS, unattributed.get(X));
        verify(adapter, never()).heldEntry(X);
        verify(adapter, never()).grantMembership(anyString(), anyString(), any());
    }

    static Stream<Arguments> protectedIdentifiers() {
        return Stream.of(
                Arguments.of("on keep", (Consumer<ParticipantAdoptionTest>) test ->
                        test.properties.removalPublishers().get(0).getOnParticipantRemoved().setKeep(List.of(X))),
                Arguments.of("own-participant-id", (Consumer<ParticipantAdoptionTest>) test ->
                        test.properties.setOwnParticipantId(X)),
                Arguments.of("general.did fallback", (Consumer<ParticipantAdoptionTest>) test -> {
                    test.properties.setOwnParticipantId(null);
                    test.generalProperties.setDid(X);
                }));
    }

    @Test
    void entryChangedDuringTheAdoptionKeepsItsUnattributedCredentials() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        CredentialsVO added = credential("AddedByTheOperator");
        afterGrant = did -> unattributed.put(did, List.of(CREDENTIALS.get(0), added));

        reconciler.run();

        assertEquals(List.of(CREDENTIALS.get(0), added), unattributed.get(X), "nothing is revoked");
        verify(adapter, never()).revokeUnattributed(anyString());
        assertTrue(logs(Level.WARN).stream().anyMatch(event -> event.getFormattedMessage().contains(X)));
        assertEquals(0, count(P1, "report", RemovalAction.ADOPTED));
    }

    @Test
    void delistedUnattributedOnlyEntryIsRemovedByAnEventButNotByTheSweep() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);

        reconciler.run();

        assertEquals(CREDENTIALS, unattributed.get(X), "the sweep never removes unattributed entries");
        verify(adapter, never()).heldEntry(X);

        removal.apply(P1, X, RemovalTrigger.EVENT).block();
        assertEquals(Optional.empty(), heldEntry(X));
    }

    @Test
    void mixedEntryIsNotAdoptedAndKeepsItsUnattributedCredentialsOnAnEvent() {
        configure(writing(adopting(P1)));
        grants.put(X, new LinkedHashMap<>(Map.of(new GrantKey(ORDER, null), List.of(credential("OrderCredential")))));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));

        reconciler.run();

        verify(adapter, never()).grantMembership(anyString(), anyString(), any());
        assertTrue(logs(Level.INFO).stream().anyMatch(event -> event.getFormattedMessage().contains("not adopted")));

        removal.apply(P1, X, RemovalTrigger.EVENT).block();
        assertEquals(CREDENTIALS, unattributed.get(X));
        verify(adapter, never()).revokeUnattributed(anyString());
    }

    @Test
    void adoptsAtMostMaxAdoptionsPerRunAndTheRestLater() {
        DataSpaceEventsProperties.Publisher p1 = adopting(P1);
        p1.getOnParticipantRemoved().setMaxAdoptionsPerRun(2);
        configure(p1);
        List.of(X, Y, Z).forEach(did -> unattributed.put(did, CREDENTIALS));
        listed.put(P1, Set.of(X, Y, Z));

        reconciler.run();
        assertEquals(Set.of(Z), unattributed.keySet(), "the first two in DID order are adopted");

        reconciler.run();
        assertTrue(unattributed.isEmpty());
        assertEquals(3, count(P1, "report", RemovalAction.ADOPTED));
    }

    @Test
    void failedRevokeLeavesADuplicateButNoLoss() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        when(adapter.revokeUnattributed(anyString())).thenReturn(Mono.error(
                new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER, "unavailable")));

        reconciler.run();

        assertEquals(CREDENTIALS, unattributed.get(X));
        assertEquals(CREDENTIALS, grants.get(X).get(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP)));
        assertEquals(1, count(P1, "report", RemovalAction.FAILED));
    }

    @Test
    void adoptedAndKnownMixedEntriesAreNotReadAgainUntilTheRecheck() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        grants.put(Y, new LinkedHashMap<>(Map.of(new GrantKey(ORDER, null), List.of(credential("OrderCredential")))));
        unattributed.put(Y, CREDENTIALS);
        listed.put(P1, Set.of(X, Y));

        reconciler.run();
        reconciler.run();

        // X: once for the decision, once to verify the duplicate; Y: once for the decision
        verify(adapter, times(2)).heldEntry(X);
        verify(adapter, times(1)).heldEntry(Y);

        grants.remove(Y);
        clock.advance(ParticipantReconciler.NOT_ADOPTABLE_RECHECK_INTERVAL.minus(Duration.ofSeconds(1)));
        reconciler.run();
        verify(adapter, times(1)).heldEntry(Y);

        clock.advance(Duration.ofSeconds(1));
        reconciler.run();
        assertFalse(unattributed.containsKey(Y), "an entry that lost its grants is adopted on the recheck");
        verify(adapter, times(2)).heldEntry(X);
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

    private static DataSpaceEventsProperties.Publisher adopting(String id) {
        DataSpaceEventsProperties.Publisher publisher = publisher(id, RemovalMode.REPORT);
        publisher.getOnParticipantRemoved().setAdoptUnattributed(true);
        return publisher;
    }

    private static DataSpaceEventsProperties.Publisher writing(DataSpaceEventsProperties.Publisher publisher) {
        publisher.getOnParticipantRemoved().setMode(RemovalMode.DATASPACE);
        return publisher;
    }

    private static CredentialsVO credential(String type) {
        return new CredentialsVO().credentialsType(type);
    }

    private Optional<HeldEntry> heldEntry(String did) {
        Map<GrantKey, List<CredentialsVO>> held = grants.get(did);
        List<CredentialsVO> loose = unattributed.get(did);
        if (held == null && loose == null) {
            return Optional.empty();
        }
        List<HeldEntry.Grant> heldGrants = new ArrayList<>();
        Optional.ofNullable(held).ifPresent(map -> map.forEach((key, credentials) ->
                heldGrants.add(new HeldEntry.Grant(key.scope(), key.type(), credentials.size()))));
        return Optional.of(new HeldEntry(did, heldGrants, loose == null ? 0 : loose.size()));
    }

    private Optional<List<CredentialsVO>> credentialsOf(String did) {
        if (heldEntry(did).isEmpty()) {
            return Optional.empty();
        }
        List<CredentialsVO> all = new ArrayList<>();
        Optional.ofNullable(grants.get(did)).ifPresent(held -> held.values().forEach(all::addAll));
        all.addAll(unattributed.getOrDefault(did, List.of()));
        return Optional.of(all);
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
}
