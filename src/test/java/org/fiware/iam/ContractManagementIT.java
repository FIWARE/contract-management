package org.fiware.iam;

import kong.unirest.*;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ContractManagementIT {

    private static final String TEST_DID = "did:web:bunnyinc.dsba.fiware.dev:did";

    @DisplayName("Test Happy Path")
    @Test
    public void testCreateProductOrder() {
        String productSpecId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(this::createProductSpec, Optional::isPresent).get();
        System.out.println("productSpecId: " + productSpecId);
        String productOfferingId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> createProductOffering(productSpecId), Optional::isPresent).get();
        System.out.println("productOfferingId: " + productOfferingId);
        String organizationId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(this::createOrganization, Optional::isPresent).get();
        System.out.println("organizationId: " + organizationId);
        String productOrder = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> orderProduct(productOfferingId, organizationId), Optional::isPresent).get();
        System.out.println("productOrder: " + productOrder);

        JSONObject tilConfig = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> getTrustedIssuersListEntry(TEST_DID), Optional::isPresent).get();
        System.out.println("tilConfig: " + tilConfig);
        Assertions.assertEquals("did:web:bunnyinc.dsba.fiware.dev:did", tilConfig.getString("did"));
        JSONArray credentials = tilConfig.getJSONArray("credentials");
        Assertions.assertNotNull(credentials);
        Assertions.assertEquals(1, credentials.length());
        JSONObject credential = credentials.getJSONObject(0);

        Assertions.assertEquals("MyCredential", credential.getString("credentialsType"));
        JSONArray claims = credential.getJSONArray("claims");
        Assertions.assertEquals(1, claims.length());
        JSONObject claim = claims.getJSONObject(0);
        Assertions.assertEquals("did:some:service",claim.getString("name"));
        Assertions.assertEquals(List.of("Consumer", "Admin"), claim.getJSONArray("allowedValues").toList());
    }

    @BeforeEach
    public void waitTilReady() throws Exception {
        Awaitility.await().atMost(5, TimeUnit.MINUTES).until(this::subscriptionExists);
        Awaitility.await().atMost(5, TimeUnit.MINUTES).until(this::trustedIssuersListServiceReady);

    }

    private boolean trustedIssuersListServiceReady() {
        try {
            return Optional.ofNullable(Unirest.get("http://localhost:8085/v4/issuers/")
                            .asJson())
                    .filter(HttpResponse::isSuccess)
                    .isPresent();
        } catch (UnirestException e) {
            //System.err.println(e);
            return false;
        }
    }

    private boolean subscriptionExists() {
        try {
            return Optional.ofNullable(Unirest
                            .get("http://localhost:1026/ngsi-ld/v1/entities")
                            .queryString(Map.of("type", "subscription"))
                            .header("type", "application/ld+json")
                            .asJson())
                    .map(HttpResponse::getBody)
                    .map(JsonNode::getArray)
                    .map(JSONArray::length)
                    .filter(length -> length > 0)
                    .isPresent();
        } catch (UnirestException e) {
            //System.err.println(e);
            return false;
        }
    }

    private Optional<JSONObject> getTrustedIssuersListEntry(String did) {
        try {
            return Optional.ofNullable(Unirest.get("http://localhost:8085/issuer/" + did)
                            .asJson())
                    .filter(HttpResponse::isSuccess)
                    .map(HttpResponse::getBody)
                    .map(JsonNode::getObject);
        } catch (UnirestException e) {
            //System.err.println(e);
            return Optional.empty();
        }
    }

    private Optional<String> orderProduct(String productOfferingId, String organizationId) {
        return getResponseId(Unirest.post("http://localhost:8081/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"productOrderItem\": [\n" +
                        "        {\n" +
                        "            \"action\": \"add\",\n" +
                        "            \"productOffering\": {\n" +
                        "                \"id\":\"%s\",\n" +
                        "                \"name\": \"Packet Delivery Premium Service\"\n" +
                        "            }\n" +
                        "        }\n" +
                        "    ],\n" +
                        "    \"relatedParty\": [\n" +
                        "        {\n" +
                        "            \"id\": \"%s\",\n" +
                        "            \"name\": \"BunnyInc\"\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}", productOfferingId, organizationId)));
    }

    private Optional<String> createOrganization() {
        return getResponseId(Unirest.post("http://localhost:8083/tmf-api/party/v4/organization")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"name\": \"BunnyInc\",\n" +
                        "    \"tradingName\": \"BunnyInc\",\n" +
                        "    \"partyCharacteristic\": [\n" +
                        "        {\n" +
                        "            \"name\": \"did\",\n" +
                        "            \"valueType\": \"string\",\n" +
                        "            \"value\": \"%s\"\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}", TEST_DID)));
    }

    private Optional<String> createProductOffering(String productSpec) {
        return getResponseId(Unirest.post("http://localhost:8082/tmf-api/productCatalogManagement/v4/productOffering")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"description\": \"My Offering description\",\n" +
                        "    \"isBundle\": false,\n" +
                        "    \"isSellable\": true,\n" +
                        "    \"lifecycleStatus\": \"Active\",\n" +
                        "    \"name\": \"Packet Delivery Premium Service\",\n" +
                        "    \"productSpecification\": {\n" +
                        "        \"id\": \"%s\",\n" +
                        "        \"name\": \"Packet Delivery Premium Service Spec\"\n" +
                        "    }\n" +
                        "}", productSpec)));

    }

    private Optional<String> createProductSpec() {
        return getResponseId(Unirest.post("http://localhost:8082/tmf-api/productCatalogManagement/v4/productSpecification")
                .header("Content-Type", "application/json")
                .body("{\n" +
                        "    \"name\": \"Packet Delivery Premium Service Spec\",\n" +
                        "    \"productSpecCharacteristic\": [\n" +
                        "        {\n" +
                        "            \"name\": \"Service Endpoint\",\n" +
                        "            \"valueType\": \"string\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"valueType\": \"string\",\n" +
                        "                    \"value\": \"https://<provider_NGSI-LD_endpoint>\"\n" +
                        "                }\n" +
                        "            ]\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}"));
    }

    private Optional<String> getResponseId(RequestBodyEntity request) {
        try {
            HttpResponse<JsonNode> response = request.asJson();

            return Optional.ofNullable(response)
                    .filter(HttpResponse::isSuccess)
                    .map(HttpResponse::getBody)
                    .map(JsonNode::getObject)
                    .map(obj -> obj.getString("id"));
        } catch (UnirestException e) {
            //System.err.println(e);
            return Optional.empty();
        }
    }
}
