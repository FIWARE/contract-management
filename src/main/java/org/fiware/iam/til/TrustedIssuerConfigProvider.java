package org.fiware.iam.til;


import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;

import java.util.Arrays;
import java.util.List;

@Singleton
@RequiredArgsConstructor
public class TrustedIssuerConfigProvider {

    private final TrustedIssuerConfig trustedIssuerConfig;

    public CredentialsVO createCredentialConfigForTargetService(){
        List<ClaimVO> claims = trustedIssuerConfig
                .getClaims()
                .stream()
                .map(c -> new ClaimVO().name(c.getTarget()).allowedValues(Arrays.asList(c.getRoles().toArray())))
                .toList();

        return new CredentialsVO().credentialsType(trustedIssuerConfig.getCredentialType()).claims(claims);
    }
}
