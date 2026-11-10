package org.fiware.iam.til;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.IssuerScopeVO;
import org.fiware.iam.til.model.ProblemDetailsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.fiware.iam.til.model.TrustedIssuersListResponseVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Grants and revokes credentials at the trusted-issuers-list on behalf of a product order, and grants, reads and revokes
 * data-space memberships for the participant-removed handling.
 * <p>
 * Every credential is attributed to the order that granted it - its <i>scope</i> - so that revoking
 * one order cannot remove a credential another order still requires. That matters as soon as two
 * products share configuration, which is the normal case once the configuration lives on a
 * {@code ServiceSpecification} several products are composed of.
 * <p>
 * Requires trusted-issuers-list {@code 0.9.0} or newer for the scope-addressed endpoints; what the membership calls need
 * beyond that is decided by the capability probes (see {@link TilCapabilities}). Failures are raised, never logged here.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

    /**
     * Order grants are untyped: omitting the scope type addresses exactly the untyped grants of a scope,
     * never e.g. a data-space membership granted under the same id, and is understood by older lists too.
     */
    private static final ScopeTypeVO ORDER_SCOPE_TYPE = null;

    /**
     * Membership grants are typed, so they never collide with an order grant under the same id.
     */
    public static final ScopeTypeVO DATASPACE_MEMBERSHIP_SCOPE_TYPE = ScopeTypeVO.DATASPACE_MEMBERSHIP;

    // validation messages of TrustedIssuersListController (FIWARE/trusted-issuers-list), answered as ProblemDetails detail
    static final String SCOPE_TYPE_WITHOUT_SCOPE =
            "A scope type requires a scope - unscoped credentials cannot be addressed by type.";
    static final String UNSCOPED_WITH_SCOPE =
            "A scope cannot be combined with unscoped=true - it addresses either a grant or none.";

    /**
     * Issuer the unscoped probe addresses; the probe is rejected before the issuer is looked up, and matches nothing otherwise.
     */
    static final String PROBE_DID = "did:web:capability-probe.contract-management.invalid";

    private static final int FIRST_PAGE = 0;
    private static final int PROBE_PAGE_SIZE = 1;
    // answers that say nothing about the version of the list
    private static final Set<Integer> NO_VERSION_SIGNAL = Set.of(
            HttpStatus.UNAUTHORIZED.getCode(), HttpStatus.FORBIDDEN.getCode(),
            HttpStatus.REQUEST_TIMEOUT.getCode(), HttpStatus.TOO_MANY_REQUESTS.getCode());

    private final IssuerApiClient apiClient;
    private final DataSpaceEventsProperties properties;

    /**
     * Grant the credentials an order configures to the given issuer.
     * <p>
     * The grant replaces whatever the same order granted before, so a redelivered notification
     * converges instead of accumulating entries. The issuer is created by the trusted-issuers-list if
     * it is not known yet.
     *
     * @param issuerDid         the issuer to grant to
     * @param orderId           the order the credentials are granted by
     * @param credentialsConfig the resolved configuration, of which only the locally managed part is granted
     * @return whether the grant succeeded
     */
    public Mono<Boolean> allowIssuer(String issuerDid, String orderId,
            List<CredentialsConfigResolver.CredentialConfig> credentialsConfig) {

        List<CredentialsVO> credentialsVOS = filterLocalCredentialsVO(credentialsConfig);
        if (credentialsVOS.isEmpty()) {
            // nothing to grant locally - and creating an empty issuer entry would be misleading
            log.debug("Order {} grants no locally managed credential to {}.", orderId, issuerDid);
            return Mono.just(true);
        }

        // deferred, so the call is issued on subscription and a synchronous failure of the client is
        // signalled through the returned Mono like any other error
        List<String> credentialTypes = credentialsVOS.stream().map(CredentialsVO::getCredentialsType).toList();
        return Mono.defer(() -> apiClient.replaceCredentialsByScope(issuerDid, orderId, credentialsVOS, ORDER_SCOPE_TYPE))
                .onErrorMap(e -> new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER,
                        "The trusted-issuers-list did not allow issuer %s the credentials %s for order %s.".formatted(
                                issuerDid, credentialTypes, orderId), e))
                .map(response -> {
                    log.debug("Order {}: the trusted-issuers-list answered the grant of {} to issuer {} with {}.",
                            orderId, credentialTypes, issuerDid, response.getStatus().getCode());
                    return HttpResponses.isSuccess(response);
                });
    }

    /**
     * Revoke everything the given order granted to the given issuer.
     * <p>
     * Deliberately does not resolve the order's configuration: what has to be revoked is what was
     * granted, and that is recorded at the trusted-issuers-list under the order's id. A specification
     * that changed - or became unreadable - since the grant therefore cannot prevent the revocation.
     * Credentials granted by another order, and credentials an operator manages by hand, are not
     * touched.
     *
     * @param issuerDid the issuer to revoke from
     * @param orderId   the order whose grants are revoked
     * @return the response of the trusted-issuers-list
     */
    public Mono<HttpResponse<?>> denyIssuer(String issuerDid, String orderId) {
        return Mono.defer(() -> apiClient.deleteCredentialsByScope(issuerDid, orderId, ORDER_SCOPE_TYPE, false))
                .<HttpResponse<?>>map(response -> {
                    log.debug("Order {}: revoked the credentials of issuer {} at the trusted-issuers-list.", orderId, issuerDid);
                    return response;
                })
                .onErrorResume(e -> {
                    if (e instanceof HttpClientResponseException hcr && hcr.getStatus() == HttpStatus.NOT_FOUND) {
                        // nothing was ever granted to that issuer, so there is nothing to revoke
                        log.debug("Issuer {} is unknown to the trusted-issuers-list, nothing to revoke for order {}.",
                                issuerDid, orderId);
                        return Mono.just(HttpResponse.noContent());
                    }
                    return Mono.error(new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER,
                            "The trusted-issuers-list did not revoke the credentials of issuer %s for order %s.".formatted(issuerDid, orderId), e));
                });
    }

    /**
     * Read what the list holds for the issuer. Empty if the issuer is unknown.
     * <p>
     * Fails with {@code TIL_READ_FAILED} if the issuer exists but its scopes cannot be listed (the {@code /scopes}
     * endpoint is missing), or if the issuer changed between the two reads so that the counts contradict each other.
     */
    public Mono<Optional<HeldEntry>> heldEntry(String did) {
        return content(did).map(content -> content.map(IssuerContent::held));
    }

    /**
     * Read what the list holds for the issuer together with its credentials, from the same reads; as {@link #heldEntry}.
     */
    public Mono<Optional<IssuerContent>> content(String did) {
        return call(() -> apiClient.getIssuer(did))
                .map(response -> response.getBody()
                        .map(TrustedIssuerVO::getCredentials)
                        .<List<CredentialsVO>>map(List::copyOf)
                        .orElse(List.of()))
                .flatMap(credentials -> call(() -> apiClient.getIssuerScopes(did))
                        .onErrorMap(TrustedIssuersListAdapter::isNotFound, e -> new TrustedIssuersException(FailureReason.TIL_READ_FAILED,
                                "The trusted-issuers-list holds issuer %s but lists no scopes for it - its /scopes endpoint is missing.".formatted(did), e))
                        .map(response -> new IssuerContent(
                                toHeldEntry(did, credentials.size(), response.getBody().orElse(List.of())), credentials)))
                .map(Optional::of)
                .onErrorResume(TrustedIssuersListAdapter::isNotFound, e -> Mono.just(Optional.empty()))
                .onErrorMap(e -> !(e instanceof TrustedIssuersException), e -> new TrustedIssuersException(FailureReason.TIL_READ_FAILED,
                        "The trusted-issuers-list could not be read for issuer %s.".formatted(did), e));
    }

    /**
     * All issuers holding the grant. Fails rather than answer a partial list (more than max-pages, or inconsistent pages).
     */
    public Mono<Set<String>> issuersGrantedBy(String scope, ScopeTypeVO type) {
        return listIssuers(scope, type, "the issuers granted " + describe(scope, type));
    }

    /**
     * All issuers of the list, under the same completeness rules as {@link #issuersGrantedBy}.
     */
    public Mono<Set<String>> allIssuers() {
        return listIssuers(null, null, "all issuers");
    }

    /**
     * Revoke exactly the issuer's (scope, type) grant. An unknown issuer counts as done.
     */
    public Mono<Void> revokeGrant(String did, String scope, @Nullable ScopeTypeVO type) {
        return idempotentWrite(() -> apiClient.deleteCredentialsByScope(did, scope, type, false),
                "The trusted-issuers-list did not revoke the grant %s of issuer %s.".formatted(describe(scope, type), did));
    }

    /**
     * Revoke the issuer's unattributed credentials, keeping every grant. An unknown issuer counts as done.
     */
    public Mono<Void> revokeUnattributed(String did) {
        return idempotentWrite(() -> apiClient.deleteCredentialsByScope(did, null, null, true),
                "The trusted-issuers-list did not revoke the unattributed credentials of issuer %s.".formatted(did));
    }

    /**
     * Delete the issuer with everything it holds. An unknown issuer counts as done.
     */
    public Mono<Void> deleteIssuer(String did) {
        return idempotentWrite(() -> apiClient.deleteIssuerById(did),
                "The trusted-issuers-list did not delete issuer %s.".formatted(did));
    }

    /**
     * Grant the publisher's data-space membership, replacing what it granted before. The only place membership grants are
     * written; the issuer's other grants and unattributed credentials are left alone, even identical ones.
     */
    public Mono<Void> grantMembership(String did, String publisherId, List<CredentialsVO> credentials) {
        return call(() -> apiClient.replaceCredentialsByScope(did, publisherId, credentials, DATASPACE_MEMBERSHIP_SCOPE_TYPE))
                .then()
                .onErrorMap(e -> new TrustedIssuersException(FailureReason.TIL_REJECTED_ISSUER,
                        "The trusted-issuers-list did not grant issuer %s the membership %s.".formatted(
                                did, describe(publisherId, DATASPACE_MEMBERSHIP_SCOPE_TYPE)), e));
    }

    /**
     * Probe for typed scopes: a type filter without a scope is rejected with a specific message only by a list that knows types.
     */
    public Mono<TilCapability.State> probeTypedScopes() {
        return call(() -> apiClient.getIssuers(null, null, null, DATASPACE_MEMBERSHIP_SCOPE_TYPE))
                // the filter was ignored
                .map(response -> TilCapability.State.TOO_OLD)
                .onErrorResume(e -> Mono.just(classify(e, SCOPE_TYPE_WITHOUT_SCOPE)));
    }

    /**
     * Probe for unscoped addressing: a scope combined with unscoped=true is rejected before the issuer is looked up. On an
     * old list the random scope matches nothing, so the probe has no effect.
     */
    public Mono<TilCapability.State> probeUnscoped() {
        return call(() -> apiClient.deleteCredentialsByScope(PROBE_DID, UUID.randomUUID().toString(), null, true))
                .map(response -> TilCapability.State.TOO_OLD)
                .onErrorResume(e -> Mono.just(classify(e, UNSCOPED_WITH_SCOPE)));
    }

    /**
     * Probe for {@code GET /issuer/{did}/scopes} with an issuer that exists, since the list answers an unknown issuer there
     * with the same bare 404 as a missing route. PENDING on an empty list.
     */
    public Mono<TilCapability.State> probeScopesEndpoint() {
        return call(() -> apiClient.getIssuers(PROBE_PAGE_SIZE, FIRST_PAGE, null, null))
                .flatMap(response -> {
                    if (response.getStatus() != HttpStatus.OK) {
                        return Mono.just(classify(response.getStatus().getCode(), false));
                    }
                    return response.getBody()
                            .map(TrustedIssuersListResponseVO::getItems)
                            .flatMap(items -> items.stream().filter(Objects::nonNull).findFirst())
                            .map(this::probeScopesOf)
                            .orElseGet(() -> Mono.just(TilCapability.State.PENDING));
                })
                .onErrorResume(e -> Mono.just(classify(e, null)));
    }

    private Mono<TilCapability.State> probeScopesOf(String did) {
        return call(() -> apiClient.getIssuerScopes(did))
                .map(response -> response.getStatus() == HttpStatus.OK && response.getBody().isPresent()
                        ? TilCapability.State.SUPPORTED
                        : TilCapability.State.TOO_OLD)
                .onErrorResume(e -> {
                    if (isNotFound(e)) {
                        return probeIssuerFound(did);
                    }
                    // a body that is not a list of scopes, e.g. the page of a proxy
                    return Mono.just(e instanceof CodecException ? TilCapability.State.TOO_OLD : classify(e, null));
                });
    }

    private Mono<TilCapability.State> probeIssuerFound(String did) {
        return call(() -> apiClient.getIssuer(did))
                // the issuer exists, so the 404 came from a missing /scopes route
                .map(response -> TilCapability.State.TOO_OLD)
                // deleted between the reads, so the 404 proved nothing
                .onErrorResume(e -> Mono.just(isNotFound(e) ? TilCapability.State.UNVERIFIED : classify(e, null)));
    }

    private Mono<Set<String>> listIssuers(@Nullable String scope, @Nullable ScopeTypeVO type, String listing) {
        DataSpaceEventsProperties.ParticipantRemovedSettings settings = properties.getParticipantRemoved();
        int pageSize = settings.effectivePageSize();
        int maxPages = settings.effectiveMaxPages();
        return fetchPage(scope, type, FIRST_PAGE, pageSize, listing)
                .expand(page -> {
                    if (!page.hasNext(pageSize)) {
                        return Mono.empty();
                    }
                    if (page.number() + 1 >= maxPages) {
                        return Mono.error(incomplete(listing, "needs more than the %s pages allowed".formatted(maxPages)));
                    }
                    return fetchPage(scope, type, page.number() + 1, pageSize, listing);
                })
                .collectList()
                .map(pages -> distinctDids(pages, listing));
    }

    private Mono<Page> fetchPage(@Nullable String scope, @Nullable ScopeTypeVO type, int number, int pageSize, String listing) {
        return call(() -> apiClient.getIssuers(pageSize, number, scope, type))
                .map(response -> response.getBody()
                        .map(body -> new Page(number, body.getTotal(), Optional.ofNullable(body.getItems()).orElse(List.of())))
                        .orElseThrow(() -> incomplete(listing, "answered page %s without a body".formatted(number))))
                .onErrorMap(e -> !(e instanceof TrustedIssuersException), e -> new TrustedIssuersException(FailureReason.TIL_READ_FAILED,
                        "The trusted-issuers-list could not list page %s of %s.".formatted(number, listing), e));
    }

    private static Set<String> distinctDids(List<Page> pages, String listing) {
        Integer total = pages.get(0).total();
        if (total == null) {
            throw incomplete(listing, "answered without a total");
        }
        Set<String> dids = new LinkedHashSet<>();
        for (Page page : pages) {
            if (!total.equals(page.total())) {
                throw incomplete(listing, "answered a total of %s on page %s instead of %s".formatted(page.total(), page.number(), total));
            }
            if (page.items().stream().anyMatch(Objects::isNull)) {
                throw incomplete(listing, "answered an entry without a DID on page %s".formatted(page.number()));
            }
            dids.addAll(page.items());
        }
        if (dids.size() != total) {
            throw incomplete(listing, "ended with %s distinct DIDs instead of the total of %s".formatted(dids.size(), total));
        }
        return Collections.unmodifiableSet(dids);
    }

    private static TrustedIssuersException incomplete(String listing, String detail) {
        return new TrustedIssuersException(FailureReason.TIL_READ_FAILED,
                "The trusted-issuers-list listing of %s is incomplete: it %s.".formatted(listing, detail));
    }

    private static HeldEntry toHeldEntry(String did, int credentialCount, List<IssuerScopeVO> scopes) {
        List<HeldEntry.Grant> grants = scopes.stream()
                .map(scope -> new HeldEntry.Grant(scope.getScope(), scope.getScopeType(),
                        Optional.ofNullable(scope.getCredentialCount()).orElse(0)))
                .toList();
        int attributed = grants.stream().mapToInt(HeldEntry.Grant::count).sum();
        if (attributed > credentialCount) {
            throw new TrustedIssuersException(FailureReason.TIL_READ_FAILED,
                    "Issuer %s changed while it was read: it holds %s credentials, but its scopes attribute %s.".formatted(
                            did, credentialCount, attributed));
        }
        return new HeldEntry(did, grants, credentialCount - attributed);
    }

    private Mono<Void> idempotentWrite(Supplier<Mono<HttpResponse<Object>>> write, String failure) {
        return call(write)
                .then()
                .onErrorResume(TrustedIssuersListAdapter::isNotFound, e -> Mono.empty())
                .onErrorMap(e -> new TrustedIssuersException(FailureReason.TIL_REVOKE_FAILED, failure, e));
    }

    // issued on subscription, so a synchronous failure of the client is signalled through the Mono; a non-2xx answer
    // becomes an error like the one the http client raises
    private static <T> Mono<HttpResponse<T>> call(Supplier<Mono<HttpResponse<T>>> request) {
        return Mono.defer(request)
                .flatMap(response -> HttpResponses.isSuccess(response)
                        ? Mono.just(response)
                        : Mono.error(new HttpClientResponseException(
                                "The trusted-issuers-list answered " + response.getStatus().getCode(), response)));
    }

    private static boolean isNotFound(Throwable e) {
        return e instanceof HttpClientResponseException hcr && hcr.getResponse().code() == HttpStatus.NOT_FOUND.getCode();
    }

    /**
     * Decide a capability from the probe's error: a 400 carrying the expected message is SUPPORTED, any other definitive
     * answer TOO_OLD, everything that is no version signal (auth, throttling, 5xx, no answer) UNVERIFIED.
     */
    static TilCapability.State classify(Throwable e, @Nullable String expectedMessage) {
        if (e instanceof HttpClientResponseException hcr) {
            return classify(hcr.getResponse().code(), expectedMessage != null && mentions(hcr, expectedMessage));
        }
        return TilCapability.State.UNVERIFIED;
    }

    private static TilCapability.State classify(int status, boolean expectedRejection) {
        if (status == HttpStatus.BAD_REQUEST.getCode()) {
            return expectedRejection ? TilCapability.State.SUPPORTED : TilCapability.State.TOO_OLD;
        }
        if (NO_VERSION_SIGNAL.contains(status) || status >= HttpStatus.INTERNAL_SERVER_ERROR.getCode()) {
            return TilCapability.State.UNVERIFIED;
        }
        if (status >= HttpStatus.OK.getCode() && status < HttpStatus.MULTIPLE_CHOICES.getCode()
                || status >= HttpStatus.BAD_REQUEST.getCode()) {
            return TilCapability.State.TOO_OLD;
        }
        return TilCapability.State.UNVERIFIED;
    }

    private static boolean mentions(HttpClientResponseException e, String message) {
        return Stream.of(
                        body(e, String.class),
                        body(e, ProblemDetailsVO.class).map(ProblemDetailsVO::getDetail),
                        Optional.ofNullable(e.getMessage()))
                .flatMap(Optional::stream)
                .anyMatch(text -> text.contains(message));
    }

    private static <T> Optional<T> body(HttpClientResponseException e, Class<T> type) {
        try {
            return e.getResponse().getBody(type);
        } catch (RuntimeException conversionFailure) {
            return Optional.empty();
        }
    }

    private static String describe(String scope, @Nullable ScopeTypeVO type) {
        return type == null ? scope : "%s (%s)".formatted(scope, type.getValue());
    }

    private record Page(int number, @Nullable Integer total, List<String> items) {

        // a short page, or reaching the total, ends the list
        boolean hasNext(int pageSize) {
            return total != null && items.size() >= pageSize && (long) (number + 1) * pageSize < total;
        }
    }

    // only return credentials intended for local
    private List<CredentialsVO> filterLocalCredentialsVO(
            List<CredentialsConfigResolver.CredentialConfig> credentialsConfig) {
        return credentialsConfig.stream()
                .filter(credentialConfig -> credentialConfig.contractManagement().isLocal())
                .map(CredentialsConfigResolver.CredentialConfig::credentialsVOS)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }
}
