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
import org.fiware.iam.til.IssuerContent;
import org.fiware.iam.til.TilCapabilities;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.til.model.CredentialsVO;
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
            removal, new ParticipantAdoption(adapter, removal, registryLookup), report, properties, generalProperties,
            new ConfigurationRefreshLock(), Optional.of(meterRegistry), clock);

    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(ParticipantReconciler.class),
            (Logger) LoggerFactory.getLogger(ParticipantAdoption.class));
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    // the fake list: per issuer its grants by (scope, type) and its unattributed credentials
    private final Map<String, Map<GrantKey, List<CredentialsVO>>> grants = new HashMap<>();
    private final Map<String, List<CredentialsVO>> unattributed = new HashMap<>();
    private final Map<String, Set<String>> listed = new HashMap<>();
    // run right before and right after a membership grant is written, to change the entry behind the adoption's back
    private Consumer<String> beforeGrant = did -> {
    };
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
        when(adapter.content(anyString())).thenAnswer(invocation ->
                Mono.fromSupplier(() -> contentOf((String) invocation.getArguments()[0])));
        when(adapter.grantMembership(anyString(), anyString(), any())).thenAnswer(invocation -> Mono.fromRunnable(() -> {
            String did = (String) invocation.getArguments()[0];
            @SuppressWarnings("unchecked")
            List<CredentialsVO> credentials = (List<CredentialsVO>) invocation.getArguments()[2];
            beforeGrant.accept(did);
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
        assertTrue(report.entries(P1).isEmpty(), "an adoption is no removal, it takes no slot in the report");
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
    void unrelatedPolicyChangeDoesNotWarnAgain() {
        DataSpaceEventsProperties.Publisher p1 = writing(adopting(P1));
        configure(p1, publisher(P2, RemovalMode.REPORT));
        reconciler.run();
        p1.getOnParticipantRemoved().setKeep(List.of(Y));
        p1.getOnParticipantRemoved().setMaxRemovalsPerRun(1);
        reconciler.run();

        assertEquals(1, logs(Level.WARN).stream()
                .filter(event -> event.getFormattedMessage().contains("adopt-unattributed"))
                .count());
        assertEquals(1, count(P1, "dataspace", RemovalAction.ADOPTION_REFUSED));
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
        verify(adapter, never()).content(X);
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
        assertTrue(logs(Level.WARN).stream().anyMatch(event -> event.getFormattedMessage().contains(X)
                && event.getFormattedMessage().contains(CREDENTIALS.get(1).getCredentialsType())), "names the grant's credentials");
        assertEquals(0, count(P1, "report", RemovalAction.ADOPTED));
    }

    @Test
    void delistedUnattributedOnlyEntryIsRemovedByAnEventButNotByTheSweep() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);

        reconciler.run();

        assertEquals(CREDENTIALS, unattributed.get(X), "the sweep never removes unattributed entries");
        verify(adapter, never()).heldEntry(X);
        verify(adapter, never()).content(X);

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresAfterTheCopy")
    void failedAdoptionLeavesNoLossAndIsCompletedInTheNextRun(String description,
                                                           Consumer<ParticipantAdoptionTest> failOnce, boolean granted) {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        failOnce.accept(this);

        reconciler.run();

        assertTrue(contentOf(X).orElseThrow().credentials().containsAll(CREDENTIALS), "nothing is lost");
        assertEquals(granted, grants.containsKey(X), "the membership grant may already be written");
        assertEquals(1, count(P1, "report", RemovalAction.FAILED));
        assertTrue(logs(Level.WARN).stream().anyMatch(event -> event.getFormattedMessage().contains("adoption incomplete")));

        reconciler.run();

        assertEquals(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS), grants.get(X));
        assertFalse(unattributed.containsKey(X), "the duplicate is resolved");
        assertEquals(1, count(P1, "report", RemovalAction.ADOPTED));
    }

    static Stream<Arguments> failuresAfterTheCopy() {
        TrustedIssuersException unavailable = new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER, "unavailable");
        return Stream.of(
                Arguments.of("revoke fails", (Consumer<ParticipantAdoptionTest>) test -> when(test.adapter.revokeUnattributed(X))
                        .thenReturn(Mono.error(unavailable))
                        .thenAnswer(invocation -> Mono.fromRunnable(() -> test.unattributed.remove(X))), true),
                Arguments.of("revoke written, its answer lost", (Consumer<ParticipantAdoptionTest>) test ->
                        when(test.adapter.revokeUnattributed(X)).thenReturn(Mono.fromRunnable(() -> test.unattributed.remove(X))
                                .then(Mono.error(unavailable))), true),
                Arguments.of("grant written, its answer lost", (Consumer<ParticipantAdoptionTest>) test ->
                        test.afterGrant = did -> {
                            test.afterGrant = ignored -> {
                            };
                            throw unavailable;
                        }, true),
                Arguments.of("grant fails without writing", (Consumer<ParticipantAdoptionTest>) test ->
                        when(test.adapter.grantMembership(X, P1, CREDENTIALS))
                                .thenReturn(Mono.error(unavailable))
                                .thenAnswer(invocation -> Mono.fromRunnable(() -> test.grants
                                        .computeIfAbsent(X, id -> new LinkedHashMap<>())
                                        .put(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS))), false));
    }

    @Test
    void incompleteAdoptionOfADelistedEntryIsCompletedBeforeItsRemoval() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        when(adapter.revokeUnattributed(X))
                .thenReturn(Mono.error(new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER, "unavailable")))
                .thenAnswer(invocation -> Mono.fromRunnable(() -> unattributed.remove(X)));
        reconciler.run();

        listed.put(P1, Set.of());
        reconciler.run();

        assertEquals(Optional.empty(), heldEntry(X), "the originals are revoked first, so the removal leaves nothing trusted");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RemovalTrigger.class)
    void removalRevokesTheCopyOfAnAdoptionWhoseCompletionWasLost(RemovalTrigger trigger) {
        configure(writing(adopting(P1)));
        // what an adoption whose revoke failed leaves, with the in-memory completion lost over a restart
        grants.put(X, new LinkedHashMap<>(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS)));
        unattributed.put(X, CREDENTIALS);

        if (trigger == RemovalTrigger.EVENT) {
            removal.apply(P1, X, trigger).block();
        } else {
            reconciler.run();
        }

        assertEquals(Optional.empty(), heldEntry(X), "the copy does not keep the removed participant trusted");
    }

    @Test
    void removalBetweenTheGrantAndTheRevokeOfAnAdoptionLeavesNothingTrusted() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        afterGrant = did -> removal.apply(P1, did, RemovalTrigger.EVENT).block();

        reconciler.run();

        assertEquals(Optional.empty(), heldEntry(X));
        assertEquals(0, count(P1, "dataspace", RemovalAction.ADOPTED));
    }

    @Test
    void removalBetweenTheReadAndTheGrantOfAnAdoptionDoesNotTrustTheParticipantAgain() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        // the grant re-creates the issuer the removal deleted
        beforeGrant = did -> removal.apply(P1, did, RemovalTrigger.EVENT).block();

        reconciler.run();

        assertEquals(Optional.empty(), heldEntry(X));
        assertEquals(0, count(P1, "dataspace", RemovalAction.ADOPTED));
    }

    @Test
    void migrationIntoTheMembershipBetweenTheReadAndTheGrantOfAListedParticipantIsKept() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        when(registryLookup.isListed(REGISTRY_URLS.get(P1), X)).thenReturn(Mono.just(true));
        // another component moves the credentials into P1's membership itself, as the adoption would
        beforeGrant = did -> {
            grants.computeIfAbsent(did, id -> new LinkedHashMap<>())
                    .put(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS);
            unattributed.remove(did);
        };

        reconciler.run();

        assertEquals(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS), grants.get(X),
                "a participant P1 still lists keeps its membership");
        verify(adapter, never()).deleteIssuer(anyString());
        assertEquals(1, count(P1, "report", RemovalAction.ADOPTED));
    }

    @Test
    void failingLookupBeforeAWithdrawalDeletesNothingAndIsCompletedInTheNextRun() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        when(registryLookup.isListed(REGISTRY_URLS.get(P1), X))
                .thenReturn(Mono.error(new IllegalStateException("registry unavailable")))
                .thenReturn(Mono.just(false));
        beforeGrant = did -> {
            beforeGrant = ignored -> {
            };
            // removed meanwhile: delisted by P1 and its originals revoked
            listed.put(P1, Set.of());
            unattributed.remove(did);
        };

        reconciler.run();

        assertEquals(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS), grants.get(X),
                "nothing is deleted while the registry cannot tell");
        verify(adapter, never()).deleteIssuer(anyString());

        reconciler.run();

        assertEquals(Optional.empty(), heldEntry(X), "the delisted participant's re-created issuer is withdrawn");
        assertEquals(0, count(P1, "report", RemovalAction.ADOPTED));
    }

    @Test
    void originalsPartlyRemovedBetweenTheReadAndTheGrantAreDroppedFromTheMembership() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        List<CredentialsVO> left = List.of(CREDENTIALS.get(1));
        beforeGrant = did -> {
            beforeGrant = ignored -> {
            };
            unattributed.put(did, left);
        };

        reconciler.run();

        assertEquals(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), left), grants.get(X),
                "the grant does not restore the credential removed meanwhile");
        assertFalse(unattributed.containsKey(X), "the remaining original is revoked");
        assertEquals(1, count(P1, "report", RemovalAction.ADOPTED));
        assertTrue(logs(Level.WARN).isEmpty(), "nothing is left to resolve manually");
    }

    @Test
    void removalKeepsUnattributedCredentialsThatAreNoCopyOfTheMembership() {
        configure(writing(adopting(P1)));
        grants.put(X, new LinkedHashMap<>(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS)));
        List<CredentialsVO> operatorManaged = List.of(credential("OperatorCredential"), credential("OtherCredential"));
        unattributed.put(X, operatorManaged);

        removal.apply(P1, X, RemovalTrigger.EVENT).block();

        assertEquals(Optional.of(new HeldEntry(X, List.of(), operatorManaged.size())), heldEntry(X));
    }

    @Test
    void standingListingFailureIsReportedOnceAndItsRecoveryLogged() {
        configure(adopting(P1));
        unattributed.put(X, CREDENTIALS);
        listed.put(P1, Set.of(X));
        TrustedIssuersException tooManyPages = new TrustedIssuersException(FailureReason.TIL_READ_FAILED, "too many pages");
        when(adapter.allIssuers()).thenReturn(Mono.error(tooManyPages), Mono.error(tooManyPages), Mono.just(Set.of(X)));

        reconciler.run();
        reconciler.run();

        assertEquals(1, logs(Level.WARN).stream()
                .filter(event -> event.getFormattedMessage().contains("Adoption for publisher")).count());
        assertEquals(1, count(P1, "report", RemovalAction.FAILED));

        reconciler.run();

        assertTrue(logs(Level.INFO).stream().anyMatch(event -> event.getFormattedMessage().contains("resumed")));
        assertFalse(unattributed.containsKey(X), "adopted once the issuers are listed again");
    }

    @Test
    void failingIssuerListingSkipsAdoptionButNotTheRemovals() {
        configure(writing(adopting(P1)));
        unattributed.put(X, CREDENTIALS);
        grants.put(Y, new LinkedHashMap<>(Map.of(new GrantKey(P1, ScopeTypeVO.DATASPACE_MEMBERSHIP), CREDENTIALS)));
        listed.put(P1, Set.of(X));
        when(adapter.allIssuers()).thenReturn(Mono.error(new TrustedIssuersException(FailureReason.TIL_READ_FAILED, "too many pages")));

        reconciler.run();

        assertEquals(Optional.empty(), heldEntry(Y), "the delisted participant is still removed");
        assertEquals(CREDENTIALS, unattributed.get(X), "nothing is adopted");
        assertEquals(1, count(P1, "dataspace", RemovalAction.FAILED));
        assertTrue(logs(Level.WARN).stream().anyMatch(event -> event.getFormattedMessage().contains("Adoption for publisher")));
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
        verify(adapter, times(2)).content(X);
        verify(adapter, times(1)).content(Y);

        grants.remove(Y);
        clock.advance(ParticipantReconciler.NOT_ADOPTABLE_RECHECK_INTERVAL.minus(Duration.ofSeconds(1)));
        reconciler.run();
        verify(adapter, times(1)).content(Y);

        clock.advance(Duration.ofSeconds(1));
        reconciler.run();
        assertFalse(unattributed.containsKey(Y), "an entry that lost its grants is adopted on the recheck");
        verify(adapter, times(2)).content(X);
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

    private Optional<IssuerContent> contentOf(String did) {
        return heldEntry(did).map(held -> {
            List<CredentialsVO> all = new ArrayList<>();
            Optional.ofNullable(grants.get(did)).ifPresent(granted -> granted.values().forEach(all::addAll));
            all.addAll(unattributed.getOrDefault(did, List.of()));
            return new IssuerContent(held, all);
        });
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
