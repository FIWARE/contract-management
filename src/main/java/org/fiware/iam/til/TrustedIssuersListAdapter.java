package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Grants and revokes credentials at the trusted-issuers-list on behalf of a product order.
 * <p>
 * Every credential is attributed to the order that granted it - its <i>scope</i> - so that revoking
 * one order cannot remove a credential another order still requires. That matters as soon as two
 * products share configuration, which is the normal case once the configuration lives on a
 * {@code ServiceSpecification} several products are composed of.
 * <p>
 * Requires trusted-issuers-list {@code 0.9.0} or newer for the scope-addressed endpoints.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

    private final IssuerApiClient apiClient;

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
        return Mono.defer(() -> apiClient.replaceCredentialsByScope(issuerDid, orderId, credentialsVOS))
                .map(TrustedIssuersListAdapter::isSuccess)
                .onErrorMap(e -> {
                    log.warn("Failed to allow.", e);
                    throw new TrustedIssuersException("Was not able to allow the issuer.", e);
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
        return Mono.defer(() -> apiClient.deleteCredentialsByScope(issuerDid, orderId))
                .<HttpResponse<?>>map(response -> response)
                .onErrorResume(e -> {
                    if (e instanceof HttpClientResponseException hcr && hcr.getStatus() == HttpStatus.NOT_FOUND) {
                        // nothing was ever granted to that issuer, so there is nothing to revoke
                        log.debug("Issuer {} is unknown to the trusted-issuers-list, nothing to revoke for order {}.",
                                issuerDid, orderId);
                        return Mono.just(HttpResponse.noContent());
                    }
                    throw new TrustedIssuersException("Was not able to deny the issuer.", e);
                });
    }

    private static boolean isSuccess(HttpResponse<?> response) {
        return response.getStatus().getCode() > 199 && response.getStatus().getCode() < 300;
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
