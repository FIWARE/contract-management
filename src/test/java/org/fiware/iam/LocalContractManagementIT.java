package org.fiware.iam;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.fiware.iam.tmforum.notification.SubscriptionHealthIndicator;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration Test running against the local service instance, spun up by the test. Do not forget to properly set the
 * local.ip property inside the pom.xml, to receive events in the local service.
 */
// Micronaut's @Requires is only evaluated after the embedded server has been started, so it cannot keep the
// local setup from claiming ports that k3s already binds in the in-container profile.
@DisabledIfSystemProperty(named = TestConfiguration.IN_CONTAINER_PROPERTY, matches = TestConfiguration.IN_CONTAINER_ENABLED, disabledReason = "contract-management runs inside k3s, the local setup is not started.")
@Requires(condition = TestConfiguration.LocalCondition.class)
@MicronautTest
@Slf4j
public class LocalContractManagementIT extends ContractManagementIT {

	private final SubscriptionHealthIndicator subscriptionHealthIndicator;

	public LocalContractManagementIT(SubscriptionHealthIndicator subscriptionHealthIndicator, TestConfiguration testConfiguration, ObjectMapper objectMapper) {
		super(objectMapper, testConfiguration);
		this.subscriptionHealthIndicator = subscriptionHealthIndicator;
	}

	@Override
	public void contractManagementHealthy() {
		if (!testConfiguration.isInContainer()) {
			// to avoid race-conditions when testing the local service, check the local health endpoint
			// in a k3s setting, this is handled by the health-checker
			Awaitility.await()
					.atMost(1, TimeUnit.MINUTES)
					.untilAsserted(() -> {
						assertEquals(HealthStatus.UP, Mono.from(subscriptionHealthIndicator.getResult()).block().getStatus(), "The contract management should be up.");
					});
		}
	}

	@Override
	public boolean rainbowEnabled() {
		return true;
	}
}
