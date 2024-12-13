package org.fiware.iam.til;

import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;

@MicronautTest(packages = {"org.fiware.iam.til"})
class TrustedIssuerConfigProviderTest {

	@Inject
	private TrustedIssuerConfigProvider classUnderTest;

	private TrustedIssuerConfig trustedIssuerConfig = new TrustedIssuerConfig();

	@MockBean(TrustedIssuerConfig.class)
	public TrustedIssuerConfig issuerConfig() {
		return trustedIssuerConfig;
	}

	@Test
	void createCredentialConfigForTargetService() {

		// avoids hard coded dependencies to the yaml files
		trustedIssuerConfig.setCredentialType("MyCredential");
		Claim claim = new Claim();
		claim.setTarget("did:some:service");
		claim.setRoles(List.of("Consumer", "Admin"));
		trustedIssuerConfig.setClaims(List.of(claim));

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