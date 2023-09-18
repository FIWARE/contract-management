package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.til.api.IssuerApi;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;

import java.util.List;
import java.util.Optional;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

    private final IssuerApi apiClient;

    public void allowIssuer(String serviceDid, String credentialsType, List<ClaimVO> claims) {
        try {
            Optional<TrustedIssuerVO> issuer = getIssuer(serviceDid);
            if (issuer.isPresent()) {
                TrustedIssuerVO updatedIssuer = issuer.get().addCredentialsItem(new CredentialsVO().credentialsType(credentialsType).claims(claims));
                apiClient.updateIssuer(serviceDid, updatedIssuer);
            } else {
                apiClient.createTrustedIssuer(new TrustedIssuerVO().did(serviceDid).addCredentialsItem(new CredentialsVO().credentialsType(credentialsType).claims(claims)));
            }
        } catch (Exception e) {
            log.error("Could not write new issuer permission to Trusted Issuer List Service: {} {} {}",serviceDid,credentialsType,claims,e);
        }
    }

    private Optional<TrustedIssuerVO> getIssuer(String serviceDID) {
        try {
            HttpResponse<TrustedIssuerVO> response = apiClient.getIssuer(serviceDID);
            if (response.code() != 200) {
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
