package org.fiware.iam.til;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

@MicronautTest(packages = {"org.fiware.iam.til"})
class TrustedIssuerConfigProviderTest {

    @Inject
    private TrustedIssuerConfigProvider classUnderTest;

    @Test
    void createCredentialConfigForTargetService() {
        CredentialsVO config = classUnderTest.createCredentialConfigForTargetService();
        Assertions.assertNotNull(config);
        Assertions.assertEquals("MyCredential", config.getCredentialsType());
        Assertions.assertNotNull(config.getValidFor());
        List<ClaimVO> claims = config.getClaims();
        Assertions.assertNotNull(claims);
        Assertions.assertEquals(1, claims.size());
        Assertions.assertEquals(new ClaimVO().name("did:some:service").addAllowedValuesItem("Consumer").addAllowedValuesItem("Admin"), claims.get(0));
    }
}