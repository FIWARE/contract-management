package org.fiware.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import lombok.extern.slf4j.Slf4j;

/**
 * Integration test running against a ContractManagement inside K3s
 */
@Requires(condition = TestConfiguration.InContainerCondition.class)
// when contract-management runs inside k3s, it should not be started locally
@MicronautTest(startApplication = false, environments = {"ga"})
@Slf4j
public class InContainerContractManagementIT extends ContractManagementIT {

	protected InContainerContractManagementIT(TestConfiguration testConfiguration, ObjectMapper objectMapper) {
		super(objectMapper, testConfiguration);
	}

	@Override
	public void contractManagementHealthy() {
		// NO-OP - k3s health check already takes care of that check
	}

	@Override
	public boolean rainbowEnabled() {
		return true;
	}
}
