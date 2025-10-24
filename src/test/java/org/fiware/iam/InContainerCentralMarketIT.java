package org.fiware.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.notification.SubscriptionHealthIndicator;
import org.junit.jupiter.api.Disabled;

import java.util.Optional;

@Requires(condition = TestConfiguration.InContainerCondition.class)
@MicronautTest(startApplication = false, environments = {"central-ga"})
@Slf4j
public class InContainerCentralMarketIT extends InContainerContractManagementIT {

    public InContainerCentralMarketIT(TestConfiguration testConfiguration, ObjectMapper objectMapper) {
        super(testConfiguration, objectMapper);
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
                        "               \"address\": \"http://contract-management-provider:8080\"" +
                        "           }\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}", TEST_PROVIDER_DID)));
        return optionalId.get();
    }

}
