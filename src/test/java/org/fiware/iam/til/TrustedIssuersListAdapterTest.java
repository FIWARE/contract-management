package org.fiware.iam.til;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import io.micronaut.http.codec.CodecException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.CredentialsVOTestExample;
import org.fiware.iam.til.model.IssuerScopeVO;
import org.fiware.iam.til.model.ProblemDetailsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.fiware.iam.til.model.TrustedIssuersListResponseVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a grant and its revocation are attributed to the order that made them, so that one
 * order's revocation cannot remove what another order still requires, and the membership, listing and probe calls.
 */
@MicronautTest(packages = { "org.fiware.iam" })
class TrustedIssuersListAdapterTest {

    private static final String ISSUER_DID = "testDID";
    private static final String ORDER_ID = "urn:ngsi-ld:product-order:first";
    private static final String REMOTE_ADDRESS = "https://remote.org";
    private static final String REMOTE_CLIENT_ID = "remote-client";

    private IssuerApiClient apiClient = mock(IssuerApiClient.class);

    @MockBean(IssuerApiClient.class)
    public IssuerApiClient apiClient() {
        return apiClient;
    }

    @Inject
    private TrustedIssuersListAdapter classUnderTest;

    @Test
    void allowIssuer_grantsUnderTheOrdersScope() {
        CredentialsVO testCVO = CredentialsVOTestExample.build();
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any(), isNull(ScopeTypeVO.class)))
                .thenReturn(Mono.just(HttpResponse.ok()));

        Boolean success = classUnderTest.allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(testCVO))).block();

        Assertions.assertEquals(true, success, "The grant should have succeeded.");
        verify(apiClient).replaceCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID), eq(List.of(testCVO)), isNull(ScopeTypeVO.class));
    }

    @Test
    void allowIssuer_withoutLocalCredentialsGrantsNothing() {
        Boolean success = classUnderTest
                .allowIssuer(ISSUER_DID, ORDER_ID, List.of(remoteConfig(CredentialsVOTestExample.build())))
                .block();

        Assertions.assertEquals(true, success, "An order with nothing to grant locally is not a failure.");
        verify(apiClient, never()).replaceCredentialsByScope(anyString(), anyString(), any(), isNull(ScopeTypeVO.class));
    }

    @Test
    void allowIssuer_deduplicatesIdenticalCredentials() {
        CredentialsVO testCVO = CredentialsVOTestExample.build();
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any(), isNull(ScopeTypeVO.class)))
                .thenReturn(Mono.just(HttpResponse.ok()));

        // the same credential configured by two parts of one composed product
        classUnderTest.allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(testCVO), localConfig(testCVO))).block();

        verify(apiClient).replaceCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID), eq(List.of(testCVO)), isNull(ScopeTypeVO.class));
    }

    @Test
    void allowIssuer_reportsAFailedGrant() {
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any(), isNull(ScopeTypeVO.class)))
                .thenReturn(Mono.just(HttpResponse.status(HttpStatus.BAD_GATEWAY)));

        Boolean success = classUnderTest
                .allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(CredentialsVOTestExample.build())))
                .block();

        Assertions.assertEquals(false, success, "A rejected grant should be reported as such.");
    }

    @Test
    void allowIssuer_fault() {
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any(), isNull(ScopeTypeVO.class)))
                .thenThrow(new HttpClientException("test"));

        Assertions.assertThrows(TrustedIssuersException.class,
                () -> classUnderTest
                        .allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(CredentialsVOTestExample.build())))
                        .block());
    }

    @Test
    void denyIssuer_revokesTheOrdersScopeWithoutResolvingItsConfiguration() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString(), isNull(ScopeTypeVO.class), anyBoolean()))
                .thenReturn(Mono.just(HttpResponse.noContent()));

        HttpResponse<?> response = classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block();

        Assertions.assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "The revocation should succeed.");
        verify(apiClient).deleteCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID), isNull(ScopeTypeVO.class), eq(false));
    }

    @Test
    void denyIssuer_unknownIssuerIsNotAFailure() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString(), isNull(ScopeTypeVO.class),anyBoolean()))
                .thenReturn(Mono.error(new HttpClientResponseException("not found", HttpResponse.notFound())));

        HttpResponse<?> response = classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block();

        Assertions.assertEquals(HttpStatus.NO_CONTENT, response.getStatus(),
                "Revoking from an issuer that was never granted anything is a no-op, not an error.");
    }

    @Test
    void denyIssuer_fault() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString(), isNull(ScopeTypeVO.class), anyBoolean()))
                .thenReturn(Mono.error(new HttpClientException("test")));

        Assertions.assertThrows(TrustedIssuersException.class,
                () -> classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block());
    }

    private static final String PUBLISHER = "did:web:trust-anchor.org";
    private static final String OTHER_PUBLISHER = "did:web:other-anchor.org";
    private static final int PAGE_SIZE = 2;
    private static final int MAX_PAGES = 3;
    private static final String OTHER_MESSAGE = "something else is wrong";

    private TrustedIssuersListAdapter membershipAdapter() {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getParticipantRemoved().setPageSize(PAGE_SIZE);
        properties.getParticipantRemoved().setMaxPages(MAX_PAGES);
        return new TrustedIssuersListAdapter(apiClient, properties);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void write_treatsAnUnknownIssuerAsDone(String write, Function<TrustedIssuersListAdapter, Mono<Void>> call) {
        stubWrites(error(HttpStatus.NOT_FOUND));

        Assertions.assertDoesNotThrow(() -> call.apply(membershipAdapter()).block(), "A 404 means already done.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void write_succeeds(String write, Function<TrustedIssuersListAdapter, Mono<Void>> call) {
        stubWrites(Mono.just(HttpResponse.noContent()));

        Assertions.assertDoesNotThrow(() -> call.apply(membershipAdapter()).block());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void write_wrapsAFailure(String write, Function<TrustedIssuersListAdapter, Mono<Void>> call) {
        Throwable cause = new HttpClientResponseException("down", HttpResponse.serverError());
        stubWrites(Mono.error(cause));

        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> call.apply(membershipAdapter()).block());
        Assertions.assertEquals(FailureReason.TIL_REVOKE_FAILED, e.getReason());
        Assertions.assertSame(cause, e.getCause(), "The cause carries the downstream answer.");
        Assertions.assertTrue(e.getMessage().contains(ISSUER_DID), "The message names the issuer.");
    }

    @Test
    void write_addressesExactlyTheGrantOrTheUnattributedCredentials() {
        stubWrites(Mono.just(HttpResponse.noContent()));
        TrustedIssuersListAdapter adapter = membershipAdapter();

        adapter.revokeGrant(ISSUER_DID, PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP).block();
        adapter.revokeGrant(ISSUER_DID, ORDER_ID, null).block();
        adapter.revokeUnattributed(ISSUER_DID).block();
        adapter.deleteIssuer(ISSUER_DID).block();

        verify(apiClient).deleteCredentialsByScope(ISSUER_DID, PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP, false);
        verify(apiClient).deleteCredentialsByScope(ISSUER_DID, ORDER_ID, null, false);
        verify(apiClient).deleteCredentialsByScope(ISSUER_DID, null, null, true);
        verify(apiClient).deleteIssuerById(ISSUER_DID);
    }

    static Stream<Arguments> writes() {
        return Stream.of(
                Arguments.of("revoke membership", (Function<TrustedIssuersListAdapter, Mono<Void>>)
                        a -> a.revokeGrant(ISSUER_DID, PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP)),
                Arguments.of("revoke order grant", (Function<TrustedIssuersListAdapter, Mono<Void>>)
                        a -> a.revokeGrant(ISSUER_DID, ORDER_ID, null)),
                Arguments.of("revoke unattributed", (Function<TrustedIssuersListAdapter, Mono<Void>>)
                        a -> a.revokeUnattributed(ISSUER_DID)),
                Arguments.of("delete issuer", (Function<TrustedIssuersListAdapter, Mono<Void>>)
                        a -> a.deleteIssuer(ISSUER_DID)));
    }

    @Test
    void grantMembership_grantsUnderTheTypedPublisherScopeAndLeavesUnscopedCredentialsAlone() {
        // the same credential the issuer already holds unscoped (C + C)
        CredentialsVO unscoped = CredentialsVOTestExample.build();
        doReturn(Mono.just(HttpResponse.ok())).when(apiClient).replaceCredentialsByScope(any(), any(), any(), any());

        membershipAdapter().grantMembership(ISSUER_DID, PUBLISHER, List.of(unscoped)).block();

        verify(apiClient).replaceCredentialsByScope(ISSUER_DID, PUBLISHER, List.of(unscoped), ScopeTypeVO.DATASPACE_MEMBERSHIP);
        verify(apiClient, never()).deleteCredentialsByScope(any(), any(), any(), any());
        verify(apiClient, never()).deleteIssuerById(any());
    }

    @Test
    void grantMembership_wrapsAFailure() {
        doReturn(error(HttpStatus.BAD_GATEWAY)).when(apiClient).replaceCredentialsByScope(any(), any(), any(), any());

        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> membershipAdapter().grantMembership(ISSUER_DID, PUBLISHER, List.of(CredentialsVOTestExample.build())).block());
        Assertions.assertTrue(e.getMessage().contains(PUBLISHER), "The message names the grant.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    void issuersGrantedBy_readsAllPages(String description, List<TrustedIssuersListResponseVO> pages,
            @Nullable Set<String> expected) {
        for (int page = 0; page < pages.size(); page++) {
            doReturn(Mono.just(HttpResponse.ok(pages.get(page)))).when(apiClient)
                    .getIssuers(PAGE_SIZE, page, PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        }
        Mono<Set<String>> listing = membershipAdapter().issuersGrantedBy(PUBLISHER, ScopeTypeVO.DATASPACE_MEMBERSHIP);

        if (expected == null) {
            TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class, listing::block,
                    "A partial list must never be answered.");
            Assertions.assertEquals(FailureReason.TIL_READ_FAILED, e.getReason());
        } else {
            Assertions.assertEquals(expected, listing.block());
            verify(apiClient, times(pages.size())).getIssuers(any(), any(), any(), any());
        }
    }

    static Stream<Arguments> listings() {
        return Stream.of(
                Arguments.of("empty list", List.of(page(0)), Set.of()),
                Arguments.of("one page", List.of(page(1, "a")), Set.of("a")),
                Arguments.of("full page, total reached", List.of(page(2, "a", "b")), Set.of("a", "b")),
                Arguments.of("several pages", List.of(page(4, "a", "b"), page(4, "c", "d")), Set.of("a", "b", "c", "d")),
                Arguments.of("short last page", List.of(page(5, "a", "b"), page(5, "c", "d"), page(5, "e")),
                        Set.of("a", "b", "c", "d", "e")),
                Arguments.of("total changes between pages", List.of(page(4, "a", "b"), page(3, "c")), null),
                Arguments.of("entries shifted between pages", List.of(page(3, "a", "b"), page(3, "b")), null),
                Arguments.of("short page before the total", List.of(page(3, "a")), null),
                Arguments.of("no total", List.of(new TrustedIssuersListResponseVO().items(List.of("a"))), null),
                Arguments.of("too many pages", List.of(page(7, "a", "b"), page(7, "c", "d"), page(7, "e", "f")), null));
    }

    @Test
    void allIssuers_listsWithoutAFilter() {
        doReturn(Mono.just(HttpResponse.ok(page(1, "a")))).when(apiClient).getIssuers(PAGE_SIZE, 0, null, null);

        Assertions.assertEquals(Set.of("a"), membershipAdapter().allIssuers().block());
    }

    @Test
    void issuersGrantedBy_wrapsAFailedPage() {
        doReturn(Mono.just(HttpResponse.ok(page(4, "a", "b")))).when(apiClient).getIssuers(PAGE_SIZE, 0, PUBLISHER, null);
        doReturn(error(HttpStatus.INTERNAL_SERVER_ERROR)).when(apiClient).getIssuers(PAGE_SIZE, 1, PUBLISHER, null);

        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> membershipAdapter().issuersGrantedBy(PUBLISHER, null).block());
        Assertions.assertEquals(FailureReason.TIL_READ_FAILED, e.getReason());
        Assertions.assertInstanceOf(HttpClientResponseException.class, e.getCause());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("heldEntries")
    void heldEntry_classifiesTheGrants(String description, int credentials, List<IssuerScopeVO> scopes,
            boolean membershipOfPublisher, int otherMemberships, int orderGrants, int unattributed, boolean attributable) {
        stubIssuer(credentials, scopes);

        HeldEntry entry = membershipAdapter().heldEntry(ISSUER_DID).block().orElseThrow();

        Assertions.assertEquals(ISSUER_DID, entry.did());
        Assertions.assertEquals(membershipOfPublisher, entry.membershipOf(PUBLISHER).isPresent());
        Assertions.assertEquals(otherMemberships, entry.otherMemberships(PUBLISHER).size());
        Assertions.assertEquals(orderGrants, entry.orderGrants().size());
        Assertions.assertEquals(unattributed, entry.unattributedCount());
        Assertions.assertEquals(attributable, entry.isAttributable());
    }

    static Stream<Arguments> heldEntries() {
        return Stream.of(
                Arguments.of("publisher's membership", 1, List.of(membership(PUBLISHER, 1)), true, 0, 0, 0, false),
                Arguments.of("other publisher's membership", 2, List.of(membership(OTHER_PUBLISHER, 2)), false, 1, 0, 0, false),
                Arguments.of("both memberships", 2, List.of(membership(PUBLISHER, 1), membership(OTHER_PUBLISHER, 1)),
                        true, 1, 0, 0, false),
                Arguments.of("order grants", 3, List.of(orderGrant(ORDER_ID, 2), orderGrant("other-order", 1)),
                        false, 0, 2, 0, false),
                Arguments.of("an order grant under the publisher's id is no membership", 1, List.of(orderGrant(PUBLISHER, 1)),
                        false, 0, 1, 0, false),
                Arguments.of("unattributed only", 2, List.of(), false, 0, 0, 2, true),
                Arguments.of("unattributed next to grants", 4, List.of(membership(PUBLISHER, 1), orderGrant(ORDER_ID, 1)),
                        true, 0, 1, 2, false),
                Arguments.of("empty issuer", 0, List.of(), false, 0, 0, 0, false));
    }

    @Test
    void heldEntry_unknownIssuerIsNotHeld() {
        doReturn(error(HttpStatus.NOT_FOUND)).when(apiClient).getIssuer(ISSUER_DID);

        Assertions.assertEquals(Optional.empty(), membershipAdapter().heldEntry(ISSUER_DID).block());
        verify(apiClient, never()).getIssuerScopes(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableEntries")
    void heldEntry_failsOnAnUnreadableEntry(String description, Mono<?> issuer, Mono<?> scopes) {
        doReturn(issuer).when(apiClient).getIssuer(ISSUER_DID);
        doReturn(scopes).when(apiClient).getIssuerScopes(ISSUER_DID);

        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> membershipAdapter().heldEntry(ISSUER_DID).block(), "An unreadable entry must not count as not held.");
        Assertions.assertEquals(FailureReason.TIL_READ_FAILED, e.getReason());
        Assertions.assertTrue(e.getMessage().contains(ISSUER_DID), "The message names the issuer.");
    }

    static Stream<Arguments> unreadableEntries() {
        Mono<?> issuerWithOneCredential = Mono.just(HttpResponse.ok(issuer(1)));
        return Stream.of(
                Arguments.of("scopes endpoint missing", issuerWithOneCredential, error(HttpStatus.NOT_FOUND)),
                Arguments.of("entry changed between the reads", issuerWithOneCredential,
                        Mono.just(HttpResponse.ok(List.of(membership(PUBLISHER, 2))))),
                Arguments.of("scopes read fails", issuerWithOneCredential, error(HttpStatus.SERVICE_UNAVAILABLE)),
                Arguments.of("issuer read fails", error(HttpStatus.SERVICE_UNAVAILABLE), Mono.just(HttpResponse.ok(List.of()))),
                Arguments.of("no connection", Mono.error(new HttpClientException("refused")), Mono.just(HttpResponse.ok(List.of()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probeAnswers")
    void probeTypedScopes_classifiesTheAnswer(String description, Function<String, Mono<?>> answer, TilCapability.State expected) {
        doReturn(answer.apply(TrustedIssuersListAdapter.SCOPE_TYPE_WITHOUT_SCOPE)).when(apiClient)
                .getIssuers(null, null, null, ScopeTypeVO.DATASPACE_MEMBERSHIP);

        Assertions.assertEquals(expected, membershipAdapter().probeTypedScopes().block());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probeAnswers")
    void probeUnscoped_classifiesTheAnswer(String description, Function<String, Mono<?>> answer, TilCapability.State expected) {
        doReturn(answer.apply(TrustedIssuersListAdapter.UNSCOPED_WITH_SCOPE)).when(apiClient)
                .deleteCredentialsByScope(eq(TrustedIssuersListAdapter.PROBE_DID), anyString(), isNull(ScopeTypeVO.class), eq(true));

        Assertions.assertEquals(expected, membershipAdapter().probeUnscoped().block());
    }

    static Stream<Arguments> probeAnswers() {
        return Stream.of(
                Arguments.of("400 with the expected message", answer(expected -> problem(HttpStatus.BAD_REQUEST, expected)),
                        TilCapability.State.SUPPORTED),
                Arguments.of("400 with the expected message as text",
                        answer(expected -> Mono.error(new HttpClientResponseException("bad",
                                HttpResponse.badRequest(expected)))),
                        TilCapability.State.SUPPORTED),
                Arguments.of("400 with another message", answer(expected -> problem(HttpStatus.BAD_REQUEST, OTHER_MESSAGE)),
                        TilCapability.State.TOO_OLD),
                Arguments.of("400 of requireScope", answer(expected ->
                        problem(HttpStatus.BAD_REQUEST, "A scope is required - credentials without one cannot be revoked without affecting others.")),
                        TilCapability.State.TOO_OLD),
                Arguments.of("400 without a body", answer(expected -> error(HttpStatus.BAD_REQUEST)), TilCapability.State.TOO_OLD),
                Arguments.of("404", answer(expected -> error(HttpStatus.NOT_FOUND)), TilCapability.State.TOO_OLD),
                Arguments.of("405", answer(expected -> error(HttpStatus.METHOD_NOT_ALLOWED)), TilCapability.State.TOO_OLD),
                Arguments.of("422", answer(expected -> error(HttpStatus.UNPROCESSABLE_ENTITY)), TilCapability.State.TOO_OLD),
                Arguments.of("expected message, but 422", answer(expected -> problem(HttpStatus.UNPROCESSABLE_ENTITY, expected)),
                        TilCapability.State.TOO_OLD),
                Arguments.of("401", answer(expected -> error(HttpStatus.UNAUTHORIZED)), TilCapability.State.UNVERIFIED),
                Arguments.of("403", answer(expected -> error(HttpStatus.FORBIDDEN)), TilCapability.State.UNVERIFIED),
                Arguments.of("408", answer(expected -> error(HttpStatus.REQUEST_TIMEOUT)), TilCapability.State.UNVERIFIED),
                Arguments.of("429", answer(expected -> error(HttpStatus.TOO_MANY_REQUESTS)), TilCapability.State.UNVERIFIED),
                Arguments.of("200", answer(expected -> Mono.just(HttpResponse.ok())), TilCapability.State.TOO_OLD),
                Arguments.of("204", answer(expected -> Mono.just(HttpResponse.noContent())), TilCapability.State.TOO_OLD),
                Arguments.of("500", answer(expected -> error(HttpStatus.INTERNAL_SERVER_ERROR)), TilCapability.State.UNVERIFIED),
                Arguments.of("503 answered", answer(expected -> Mono.just(HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE))),
                        TilCapability.State.UNVERIFIED),
                Arguments.of("connection error", answer(expected -> Mono.error(new HttpClientException("refused"))),
                        TilCapability.State.UNVERIFIED),
                Arguments.of("timeout", answer(expected -> Mono.error(ReadTimeoutException.TIMEOUT_EXCEPTION)),
                        TilCapability.State.UNVERIFIED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopesEndpointAnswers")
    void probeScopesEndpoint_classifiesTheAnswers(String description, Mono<?> list, @Nullable Mono<?> scopes,
            @Nullable Mono<?> issuer, TilCapability.State expected) {
        doReturn(list).when(apiClient).getIssuers(1, 0, null, null);
        if (scopes != null) {
            doReturn(scopes).when(apiClient).getIssuerScopes(ISSUER_DID);
        }
        if (issuer != null) {
            doReturn(issuer).when(apiClient).getIssuer(ISSUER_DID);
        }

        Assertions.assertEquals(expected, membershipAdapter().probeScopesEndpoint().block());
    }

    static Stream<Arguments> scopesEndpointAnswers() {
        Mono<?> oneIssuer = Mono.just(HttpResponse.ok(page(3, ISSUER_DID)));
        Mono<?> issuerFound = Mono.just(HttpResponse.ok(issuer(1)));
        return Stream.of(
                Arguments.of("empty list", Mono.just(HttpResponse.ok(page(0))), null, null, TilCapability.State.PENDING),
                Arguments.of("list fails", error(HttpStatus.INTERNAL_SERVER_ERROR), null, null, TilCapability.State.UNVERIFIED),
                Arguments.of("list not routed", error(HttpStatus.NOT_FOUND), null, null, TilCapability.State.TOO_OLD),
                Arguments.of("list unauthorized", error(HttpStatus.UNAUTHORIZED), null, null, TilCapability.State.UNVERIFIED),
                Arguments.of("list answers 204", Mono.just(HttpResponse.noContent()), null, null, TilCapability.State.TOO_OLD),
                Arguments.of("list without connection", Mono.error(new HttpClientException("refused")), null, null,
                        TilCapability.State.UNVERIFIED),
                Arguments.of("scopes answer an array", oneIssuer, Mono.just(HttpResponse.ok(List.of(membership(PUBLISHER, 1)))),
                        null, TilCapability.State.SUPPORTED),
                Arguments.of("scopes answer an empty array", oneIssuer, Mono.just(HttpResponse.ok(List.of())), null,
                        TilCapability.State.SUPPORTED),
                Arguments.of("scopes answer no body", oneIssuer, Mono.just(HttpResponse.noContent()), null,
                        TilCapability.State.TOO_OLD),
                Arguments.of("scopes answer a page that is no array", oneIssuer,
                        Mono.error(new HttpClientResponseException("Error decoding HTTP response body",
                                new CodecException("not an array"), HttpResponse.ok("<html/>"))),
                        null, TilCapability.State.TOO_OLD),
                Arguments.of("scopes body fails to decode", oneIssuer, Mono.error(new CodecException("not an array")), null,
                        TilCapability.State.TOO_OLD),
                Arguments.of("scopes 404, issuer still found", oneIssuer, error(HttpStatus.NOT_FOUND), issuerFound,
                        TilCapability.State.TOO_OLD),
                Arguments.of("scopes 404, issuer gone", oneIssuer, error(HttpStatus.NOT_FOUND), error(HttpStatus.NOT_FOUND),
                        TilCapability.State.UNVERIFIED),
                Arguments.of("scopes 404, issuer read fails", oneIssuer, error(HttpStatus.NOT_FOUND),
                        error(HttpStatus.BAD_GATEWAY), TilCapability.State.UNVERIFIED),
                Arguments.of("scopes 405", oneIssuer, error(HttpStatus.METHOD_NOT_ALLOWED), null, TilCapability.State.TOO_OLD),
                Arguments.of("scopes 5xx", oneIssuer, error(HttpStatus.SERVICE_UNAVAILABLE), null, TilCapability.State.UNVERIFIED),
                Arguments.of("scopes timeout", oneIssuer, Mono.error(ReadTimeoutException.TIMEOUT_EXCEPTION), null,
                        TilCapability.State.UNVERIFIED));
    }

    private void stubWrites(Mono<?> answer) {
        doReturn(answer).when(apiClient).deleteCredentialsByScope(any(), any(), any(), any());
        doReturn(answer).when(apiClient).deleteIssuerById(any());
    }

    private void stubIssuer(int credentials, List<IssuerScopeVO> scopes) {
        doReturn(Mono.just(HttpResponse.ok(issuer(credentials)))).when(apiClient).getIssuer(ISSUER_DID);
        doReturn(Mono.just(HttpResponse.ok(scopes))).when(apiClient).getIssuerScopes(ISSUER_DID);
    }

    private static TrustedIssuerVO issuer(int credentials) {
        return new TrustedIssuerVO().did(ISSUER_DID).credentials(
                Stream.generate(CredentialsVOTestExample::build).limit(credentials).toList());
    }

    private static IssuerScopeVO membership(String publisher, int count) {
        return new IssuerScopeVO().scope(publisher).scopeType(ScopeTypeVO.DATASPACE_MEMBERSHIP).credentialCount(count);
    }

    private static IssuerScopeVO orderGrant(String orderId, int count) {
        return new IssuerScopeVO().scope(orderId).credentialCount(count);
    }

    private static TrustedIssuersListResponseVO page(int total, String... dids) {
        return new TrustedIssuersListResponseVO().total(total).items(List.of(dids));
    }

    private static <T> Mono<T> error(HttpStatus status) {
        return Mono.error(new HttpClientResponseException("answered " + status.getCode(), HttpResponse.status(status)));
    }

    private static <T> Mono<T> problem(HttpStatus status, String detail) {
        return Mono.error(new HttpClientResponseException("answered " + status.getCode(),
                HttpResponse.status(status).body(new ProblemDetailsVO().status(status.getCode()).detail(detail))));
    }

    private static Function<String, Mono<?>> answer(Function<String, Mono<?>> answer) {
        return answer;
    }

    private static CredentialsConfigResolver.CredentialConfig localConfig(CredentialsVO credentialsVO) {
        return new CredentialsConfigResolver.CredentialConfig(new ContractManagement(true), List.of(credentialsVO));
    }

    private static CredentialsConfigResolver.CredentialConfig remoteConfig(CredentialsVO credentialsVO) {
        return new CredentialsConfigResolver.CredentialConfig(
                new ContractManagement(false, REMOTE_ADDRESS, REMOTE_CLIENT_ID, Set.of("scope")),
                List.of(credentialsVO));
    }
}
