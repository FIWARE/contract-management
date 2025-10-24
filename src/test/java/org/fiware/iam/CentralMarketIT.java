package org.fiware.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.notification.SubscriptionHealthIndicator;
import org.junit.jupiter.api.Disabled;

import java.util.Optional;

@MicronautTest(environments = {"central"})
@Slf4j
public class CentralMarketIT extends LocalContractManagementIT {

    public CentralMarketIT(SubscriptionHealthIndicator subscriptionHealthIndicator, TestConfiguration testConfiguration, ObjectMapper objectMapper) {
        super(subscriptionHealthIndicator, testConfiguration, objectMapper);
    }

    @Disabled
    @Override
    public void testContractNegotiation() {
        // rainbow support is not enabled for central marketplace setups
    }

    @Override
    public boolean rainbowEnabled() {
        return false;
    }

    @Override
    public String createProviderOrganization() {

        Optional<String> optionalId = getResponseId(Unirest.post(testConfiguration.getPartyCatalogHost() + "/tmf-api/party/v4/organization")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"name\": \"Provider\",\n" +
                        "    \"tradingName\": \"Provider\",\n" +
                        "    \"partyCharacteristic\": [\n" +
                        "        {\n" +
                        "            \"name\": \"did\",\n" +
                        "            \"valueType\": \"string\",\n" +
                        "            \"value\": \"%s\"\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"name\": \"contractManagement\",\n" +
                        "            \"value\": {" +
                        "               \"address\": \"http://localhost:8089\"" +
                        "           }\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}", TEST_PROVIDER_DID)));
        return optionalId.get();
    }

}
