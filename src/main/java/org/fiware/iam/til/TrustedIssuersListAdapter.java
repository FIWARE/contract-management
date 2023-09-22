package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;

import java.util.List;
import java.util.Optional;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

    private final IssuerApiClient apiClient;
    private final TrustedIssuerConfigProvider trustedIssuerConfigProvider;

    public void allowIssuer(String serviceDid) {
        CredentialsVO credentialToBeAdded = trustedIssuerConfigProvider.createCredentialConfigForTargetService();
        try {
            Optional<TrustedIssuerVO> issuer = getIssuer(serviceDid);
            if (issuer.isPresent()) {
                TrustedIssuerVO updatedIssuer = issuer.get().addCredentialsItem(credentialToBeAdded);
                log.debug("Updating existing issuer with {}", updatedIssuer);
                apiClient.updateIssuer(serviceDid, updatedIssuer);
            } else {
                TrustedIssuerVO newIssuer = new TrustedIssuerVO().did(serviceDid).addCredentialsItem(credentialToBeAdded);
                log.debug("Adding new issuer with {}", newIssuer);
                apiClient.createTrustedIssuer(newIssuer);
            }
        } catch (Exception e) {
            log.error("Could not write new issuer permission to Trusted Issuer List Service: {} {}", serviceDid, credentialToBeAdded, e);
        }
    }

    private Optional<TrustedIssuerVO> getIssuer(String serviceDID) {
        try {
            HttpResponse<TrustedIssuerVO> response = apiClient.getIssuer(serviceDID);
            if (response.code() != HttpStatus.OK.getCode()) {
                log.debug("Could not find issuer {} in Trusted Issuers List. Status {}", serviceDID, response.code());
                return Optional.empty();
            }
            return Optional.ofNullable(response.body());
        } catch (Exception e) {
            log.error("Error while retrieving Issuer {}", serviceDID, e);
            return Optional.empty();
        }
    }
}
