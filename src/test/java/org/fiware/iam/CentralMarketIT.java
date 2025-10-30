package org.fiware.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.notification.SubscriptionHealthIndicator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Requires(condition = TestConfiguration.LocalCondition.class)
@MicronautTest(environments = {"central"})
@Slf4j
public class CentralMarketIT extends LocalContractManagementIT {

    public CentralMarketIT(SubscriptionHealthIndicator subscriptionHealthIndicator, TestConfiguration testConfiguration, ObjectMapper objectMapper) {
        super(subscriptionHealthIndicator, testConfiguration, objectMapper);
    }

    @Disabled
    @Override
    @Test
    public void testContractNegotiation() {
        // rainbow support is not enabled for central marketplace setups
    }

    @Override
    public boolean rainbowEnabled() {
        return false;
    }

    @Override
    public String createProviderOrganization() {

        Optional<String> optionalId = getResponseId(
                Unirest.post(testConfiguration.getPartyCatalogHost() + "/tmf-api/party/v4/organization")
                        .header("Content-Type", "application/json")
                        .body(Map.of(
                                "name", "Provider",
                                "tradingName", "Provider",
                                "partyCharacteristic", List.of(
                                        Map.of(
                                                "name", "did",
                                                "valueType", "string",
                                                "value", TEST_PROVIDER_DID
                                        ),
                                        Map.of(
                                                "name", "contractManagement",
                                                "value", Map.of("address", "http://localhost:8089")
                                        )
                                )
                        )));
        return optionalId.get();
    }

}
