package org.fiware.iam.pap;

import org.fiware.iam.odrl.pap.api.PolicyApiClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class PAPAdapterTest {

	@Test
	public void aPolicyWithoutUidIsAnInvalidArgumentAndNotSentToThePap() {
		PolicyApiClient papClient = mock(PolicyApiClient.class);
		PAPAdapter papAdapter = new PAPAdapter(papClient);
		Map<String, Object> policy = new HashMap<>(Map.of("odrl:permission", new HashMap<>()));

		// invalid input of whoever provided the policy - answered with 400, not as a failure of the PAP
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> papAdapter.createPolicy("did:web:consumer", "order-1", policy).block());

		assertTrue(exception.getMessage().startsWith("[policy_missing_uid] The policy has no string odrl:uid"), exception.getMessage());
		verify(papClient, never()).createPolicy(any());
	}
}
