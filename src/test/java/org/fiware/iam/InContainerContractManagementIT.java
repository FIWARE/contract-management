package org.fiware.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import lombok.extern.slf4j.Slf4j;

/**
 * Integration test running against a ContractManagement inside K3s
 */
@Requires(env = "ga")
// when contract-management runs inside k3s, it should not be started locally
@MicronautTest(startApplication = false)
@Slf4j
public class InContainerContractManagementIT extends ContractManagementIT {

	protected InContainerContractManagementIT(TestConfiguration testConfiguration, ObjectMapper objectMapper) {
		super(objectMapper, testConfiguration);
	}

	@Override
	void contractManagementHealthy() {
		// NO-OP - k3s health check already takes care of that check
	}
}
