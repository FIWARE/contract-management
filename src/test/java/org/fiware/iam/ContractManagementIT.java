package org.fiware.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micronaut.context.ApplicationContext;
import kong.unirest.*;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.AgreementTmfVO;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteUpdateVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.fiware.rainbow.model.ContractAgreementVO;
import org.fiware.rainbow.model.NegotiationVO;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public abstract class ContractManagementIT {

    public static final String TEST_CONSUMER_DID = "did:web:bunnyinc.dsba.fiware.dev:did";
    public static final String TEST_MARKETPLACE_DID = "did:web:marketplace.io";
    public static final String TEST_PROVIDER_DID = "did:web:provider.io";
    public static final String TEST_SERVICE = "did:some:service";
    public static final String TEST_ENDPOINT = "https://the-service.org";
    public final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    protected final TestConfiguration testConfiguration;

    // required to parse date time strings to instants
    @BeforeAll
    public static void configureUnirest() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Unirest.config().setObjectMapper(new kong.unirest.ObjectMapper() {
            private final ObjectMapper mapper = objectMapper;

            @Override
            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return mapper.readValue(value, valueType);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String writeValue(Object value) {
                try {
                    return mapper.writeValueAsString(value);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @BeforeEach
    public void cleanUpAndWait() {

        Unirest.delete(testConfiguration.getTilHost() + "/issuer/" + TEST_CONSUMER_DID).asString();
        getPolicies()
                .stream()
                .map(p -> p.get("id"))
                .forEach(id -> {
                    HttpResponse<String> result = Unirest.delete(testConfiguration.getOdrlPapHost() + "/policy/" + id).asString();
                    log.warn("Clean up result for {} is {}", id, result.getStatus());
                });

        contractManagementHealthy();
    }

    protected ContractManagementIT(com.fasterxml.jackson.databind.ObjectMapper objectMapper, TestConfiguration testConfiguration) {
        this.objectMapper = objectMapper;
        this.testConfiguration = testConfiguration;
    }

    /**
     * Checks if the contract management is running and healthy
     */
    public abstract void contractManagementHealthy();

    /**
     * Is rainbow enabled and should its results be checked?
     */
    public abstract boolean rainbowEnabled();

    @DisplayName("Test Happy Path")
    @Test
    public void testCreateProductOrder() {

        String providerOrganizationID = createProviderOrganization();
        String consumerOrganizationId = createConsumerOrganization();
        String offeringId = createTestOffer(Optional.empty(), providerOrganizationID);
        String productOrder = orderProduct(offeringId, consumerOrganizationId);
        completeProductOrder(productOrder);

        if (rainbowEnabled()) {
            assertAgreementCreated(TEST_CONSUMER_DID, TEST_MARKETPLACE_DID, TEST_ENDPOINT);
            assertAgreementReferenced(productOrder);
        }
        assertTilEntry(TEST_CONSUMER_DID, "MyCredential", TEST_SERVICE, Set.of("Consumer", "Admin"));
        assertEquals(1, getPolicies().size(), "The policy should have been created.");
    }

    @DisplayName("Test Happy Path no policy")
    @Test
    public void testCreateProductOrderNoPolicy() {

        String providerOrganizationID = createProviderOrganization();
        String consumerOrganizationId = createConsumerOrganization();
        String offeringId = createTestOfferWithoutPolicy(Optional.empty(), providerOrganizationID);
        String productOrder = orderProduct(offeringId, consumerOrganizationId);
        completeProductOrder(productOrder);

        if (rainbowEnabled()) {
            assertAgreementCreated(TEST_CONSUMER_DID, TEST_MARKETPLACE_DID, TEST_ENDPOINT);
            assertAgreementReferenced(productOrder);
        }
        assertTilEntry(TEST_CONSUMER_DID, "MyCredential", TEST_SERVICE, Set.of("Consumer", "Admin"));

        assertEquals(0, getPolicies().size(), "The policy should have been created.");
    }

    @DisplayName("Test Contract Negotiation with Rainbow.")
    @Test
    public void testContractNegotiation() {
        if (!rainbowEnabled()) {
            fail("DSP Contract negotiation requires rainbow to be enabled.");
        }

        String providerOrganizationID = createProviderOrganization();
        String consumerOrganizationId = createConsumerOrganization();
        String priceId = createPrice();
        String offeringId = createTestOffer(Optional.of(priceId), providerOrganizationID);

        // state requested
        String quoteId = createQuote(consumerOrganizationId, offeringId, priceId);

        Awaitility.await()
                .alias("Quote was not properly updated.")
                .atMost(2, TimeUnit.MINUTES)
                .until(() -> getQuote(quoteId).getExternalId() != null && !getQuote(quoteId).getExternalId().isEmpty());

        QuoteVO theCreatedQuote = getQuote(quoteId);

        assertNegotiationInState(theCreatedQuote.getExternalId(), "dspace:REQUESTED");

        // state offered
        changeQuoteState(quoteId, "approved");

        Awaitility.await()
                .alias("Negotiation state should be OFFERED.")
                .atMost(1, TimeUnit.MINUTES)
                .untilAsserted(() -> assertNegotiationInState(theCreatedQuote.getExternalId(), "dspace:OFFERED"));

        // state accepted
        changeQuoteAndItemState(quoteId, QuoteStateTypeVO.ACCEPTED, "accepted");
        // ACCEPTED only happens implicitly, since the provider takes the "ACCEPTED", validates it and sets it to "AGREED"
        Awaitility.await()
                .alias("Negotiation state should be AGREED.")
                .atMost(1, TimeUnit.MINUTES)
                .untilAsserted(() -> assertNegotiationInState(theCreatedQuote.getExternalId(), "dspace:AGREED"));
        // TODO: ->"create agreement"
        // state verified
        String orderId = orderProductWithQuote(consumerOrganizationId, quoteId);
        Awaitility.await()
                .alias("Negotiation state should be VERIFIED.")
                .atMost(1, TimeUnit.MINUTES)
                .untilAsserted(() -> assertNegotiationInState(theCreatedQuote.getExternalId(), "dspace:VERIFIED"));

        // state finalized
        completeProductOrder(orderId);
        Awaitility.await()
                .alias("Negotiation state should be FINALIZED.")
                .atMost(1, TimeUnit.MINUTES)
                .untilAsserted(() -> assertNegotiationInState(theCreatedQuote.getExternalId(), "dspace:FINALIZED"));

        assertAgreementCreated(TEST_CONSUMER_DID, TEST_MARKETPLACE_DID, TEST_ENDPOINT);
        assertAgreementReferenced(orderId);
        assertTilEntry(TEST_CONSUMER_DID, "MyCredential", TEST_SERVICE, Set.of("Consumer", "Admin"));

        assertEquals(1, getPolicies().size(), "The policy should have been created.");
    }


    private void assertTilEntry(String expectedDid, String expectedCredentialsType, String expectedService, Set<String> expectedValues) {
        JSONObject tilConfig = Awaitility.await()
                .atMost(1, TimeUnit.MINUTES)
                .until(() -> getTrustedIssuersListEntry(TEST_CONSUMER_DID), Optional::isPresent)
                .get();

        assertEquals(expectedDid, tilConfig.getString("did"), "Trusted Issuer should be properly properly configured");

        JSONArray credentials = tilConfig.getJSONArray("credentials");
        assertNotNull(credentials, "No credentials entry was included in the TIL Config.");
        assertEquals(1, credentials.length(), "Exactly one credential should be included in the config.");

        JSONObject credential = credentials.getJSONObject(0);
        assertEquals(expectedCredentialsType, credential.getString("credentialsType"), "Correct credentials type should have been configured.");

        JSONArray claims = credential.getJSONArray("claims");
        assertEquals(1, claims.length(), "Exactly one claim should be configured.");

        JSONObject claim = claims.getJSONObject(0);
        assertEquals(expectedService, claim.getString("name"), "The ordered service should have been included.");
        assertEquals(expectedValues, Set.of(claim.getJSONArray("allowedValues").toList().toArray()), "The requested roles should have been configured.");
    }

    @BeforeEach
    public void waitTilReady() throws Exception {
        Awaitility.await().atMost(5, TimeUnit.MINUTES).until(this::trustedIssuersListServiceReady);
    }

    private boolean trustedIssuersListServiceReady() {
        try {
            return Optional.ofNullable(Unirest.get(testConfiguration.getTilHost() + "/v4/issuers/")
                            .asJson())
                    .filter(HttpResponse::isSuccess)
                    .isPresent();
        } catch (UnirestException e) {
            return false;
        }
    }

    private Optional<JSONObject> getTrustedIssuersListEntry(String did) {
        try {
            return Optional.ofNullable(Unirest.get(testConfiguration.getTilHost() + "/issuer/" + did)
                            .asJson())
                    .filter(HttpResponse::isSuccess)
                    .map(HttpResponse::getBody)
                    .map(JsonNode::getObject);
        } catch (UnirestException e) {
            return Optional.empty();
        }
    }

    private void assertNegotiationInState(String providerId, String expectedState) {
        HttpResponse<NegotiationVO> response = Unirest.get(testConfiguration.getProviderRainbowHost() + "/negotiations/" + providerId)
                .asObject(NegotiationVO.class);
        assertTrue(response.isSuccess(), "The negotiation should have been successfully returned.");
        assertEquals(expectedState, response.getBody().getDspaceColonState(), String.format("The negotiation should be in state %s.", expectedState));
    }

    private void assertAgreementCreated(String consumerId, String providerId, String target) {

        Awaitility.await().alias("The agreement was not created.").atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
                    boolean match = getAgreements().stream()
                            .anyMatch(agreementVO ->
                                    agreementVO.getConsumerParticipantId().equals("urn:" + consumerId)
                                            && agreementVO.getProviderParticipantId().equals("urn:" + providerId)
                                            && agreementVO.getAgreementContent().getOdrlColonTarget().equals(target));
                    assertTrue(match, "No agreement found.");
                }
        );
    }


    private void assertAgreementReferenced(String orderId) {
        Awaitility.await()
                .alias("The agreement was not referenced.")
                .atMost(1, TimeUnit.MINUTES).untilAsserted(() -> {
                            ProductOrderVO productOrderVO = getProductOrder(orderId);
                            assertNotNull(productOrderVO.getAgreement(), "An agreement should be linked to the order.");
                            assertTrue(
                                    productOrderVO.getAgreement()
                                            .stream()
                                            .map(AgreementRefVO::getId)
                                            .map(this::getTmfAgreement)
                                            .map(AgreementTmfVO::getCharacteristic)
                                            .flatMap(List::stream)
                                            .filter(characteristicTmfVO -> Objects.nonNull(characteristicTmfVO.getName()))
                                            .anyMatch(characteristicTmfVO ->
                                                    characteristicTmfVO.getName().equals(TMForumAdapter.DATA_SPACE_PROTOCOL_AGREEMENT_ID)
                                            ),
                                    "An agreement should be linked in the order.");
                        }
                );
    }

    private QuoteVO getQuote(String quoteId) {
        HttpResponse<QuoteVO> response = Unirest.get(testConfiguration.getQuoteHost() + "/tmf-api/quote/v4/quote/" + quoteId)
                .asObject(QuoteVO.class);
        assertTrue(response.isSuccess(), "The quote should have been returned successfully.");
        return response.getBody();
    }

    private AgreementTmfVO getTmfAgreement(String agreementId) {
        HttpResponse<AgreementTmfVO> response = Unirest.get(testConfiguration.getAgreementHost() + "/tmf-api/agreementManagement/v4/agreement/" + agreementId).asObject(AgreementTmfVO.class);
        assertTrue(response.isSuccess(), "The agreement should have been returned successfully.");
        return response.getBody();
    }

    private void changeQuoteState(String quoteId, String state) {
        assertTrue(Unirest.patch(testConfiguration.getQuoteHost() + "/tmf-api/quote/v4/quote/" + quoteId)
                .header("Content-Type", "application/json")
                .body(String.format("{ \n" +
                        "    \"state\": \"%s\"     \n" +
                        "}", state))
                .asString()
                .isSuccess());

    }

    private void changeQuoteAndItemState(String quoteId, QuoteStateTypeVO quoteState, String itemState) {
        QuoteVO quoteVO = getQuote(quoteId)
                .state(quoteState);
        quoteVO.setQuoteItem(quoteVO.getQuoteItem().stream().map(qi -> qi.state(itemState)).toList());

        QuoteUpdateVO quoteUpdateVO = objectMapper.convertValue(quoteVO, QuoteUpdateVO.class);
        quoteUpdateVO.unknownProperties(null);

        assertTrue(Unirest.patch(testConfiguration.getQuoteHost() + "/tmf-api/quote/v4/quote/" + quoteId)
                .header("Content-Type", "application/json")
                .body(quoteUpdateVO)
                .asString()
                .isSuccess());

    }

    private String orderProduct(String productOfferingId, String organizationId) {
        return getResponseId(Unirest.post(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"productOrderItem\": [\n" +
                        "        {\n" +
                        "            \"id\":\"order-item\",\n" +
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
                        "}", productOfferingId, organizationId))).get();
    }


    private String orderProductWithQuote(String organizationId, String quoteId) {
        return getResponseId(Unirest.post(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "    \"quote\": [\n" +
                        "       {\n" +
                        "          \"id\":\"%s\"\n" +
                        "       }\n" +
                        "    ],\n" +
                        "    \"relatedParty\": [\n" +
                        "        {\n" +
                        "            \"id\": \"%s\",\n" +
                        "            \"name\": \"BunnyInc\"\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}", quoteId, organizationId))).get();
    }

    private String createPrice() {
        return getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productOfferingPrice")
                .header("Content-Type", "application/json")
                .body("{\n" +
                        "    \"priceType\": \"recurring\", " +
                        "    \"recurringChargePeriodType\": \"month\", " +
                        "    \"name\": \"Product Price\", " +
                        "    \"price\": {\n" +
                        "          \"unit\": \"EUR\",\n" +
                        "          \"value\": 10.0\n" +
                        "     }\n" +
                        "}")).get();
    }


    private String createQuote(String organizationId, String offeringId, String priceId) {
        return getResponseId(Unirest.post(testConfiguration.getQuoteHost() + "/tmf-api/quote/v4/quote")
                .header("Content-Type", "application/json")
                .body(String.format("{\n" +
                        "        \"description\": \"Request for Test Offering\",\n" +
                        "        \"version\": \"1\",\n" +
                        "		 \"relatedParty\":[{ " +
                        "          \"id\":\"" + organizationId + "\"," +
                        "          \"role\":\"Consumer\"" +
                        "        }], " +
                        "        \"quoteItem\": [\n" +
                        "            {\n" +
                        "                \"id\": \"item-id\",\n" +
                        "                \"@schemaLocation\": \"https://raw.githubusercontent.com/FIWARE/contract-management/refs/heads/tpp-integration/schemas/policies.json\",\n" +
                        "                \"policy\": [{\n" +
                        "                	\"odrl:permission\": [{\n" +
                        "                    	\"odrl:action\": \"odrl:use\",\n" +
                        "               		\"odrl:constraint\": [{\n" +
                        "                    		\"odrl:leftOperand\": \"A\",\n" +
                        "                    		\"odrl:operator\": \"odrl:eq\",\n" +
                        "                    		\"odrl:rightOperand\": \"B\"\n" +
                        "                		}]\n" +
                        "                	}]\n" +
                        "                }],\n" +
                        "                \"productOffering\": {\n" +
                        "                    \"id\": \"%s\"\n" +
                        "                },\n" +
                        "                \"action\": \"modify\",\n" +
                        "                \"state\": \"inProgress\",\n" +
                        "                \"note\": [{\n" +
                        "                    \"id\": \"uri:random:note\",\n" +
                        "                    \"text\": \"We would prefer weekly pricing and a discount\"\n" +
                        "                }],\n" +
                        "                \"quoteItemPrice\": [{\n" +
                        "                    \"priceType\": \"recurring\",\n" +
                        "                    \"name\": \"alternative price\",\n" +
                        "                    \"recurringChargePeriod\": \"weekly\",\n" +
                        "                    \"price\": {\n" +
                        "                        \"taxIncludedAmount\": {\n" +
                        "                            \"unit\": \"EUR\",\n" +
                        "                            \"value\": 2.0\n" +
                        "                        }\n" +
                        "                    }\n" +
                        "                }]\n" +
                        "            }\n" +
                        "        ]\n" +
                        "     }", offeringId, priceId))).get();
    }

    private ProductOrderVO getProductOrder(String productOrderId) throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder/" + productOrderId)
                .header("Content-Type", "application/json").asString();
        assertTrue(response.isSuccess(), "The productOrder should exist.");

        return objectMapper.readValue(response.getBody(), ProductOrderVO.class);
    }


    private void completeProductOrder(String productOrderId) {
        getResponseId(
                Unirest.patch(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder/%s".formatted(productOrderId))
                        .header("Content-Type", "application/json")
                        .body("{\n" +
                                "    \"state\": \"completed\" " +
                                "}"));
    }


    private Optional<String> changeStateProductOrder(String productOrderId) {
        return getResponseId(Unirest.patch(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder/" + productOrderId)
                .header("Content-Type", "application/json")
                .body("{\"state\": \"cancelled\" }"));
    }

    private Optional<String> updateProductOrder(String productOrderId) {
        return getResponseId(Unirest.patch(testConfiguration.getProductOrderingManagementHost() + "/tmf-api/productOrderingManagement/v4/productOrder/" + productOrderId)
                .header("Content-Type", "application/json")
                .body("{\n" +
                        "    \"state\": \"completed\",\n" +
                        "    \"priority\": \"3\"" +
                        "}"));
    }

    private String createTestOffer(Optional<String> priceId, String providerId) {
        String categoryId = createCategory();
        createProductCatalog(categoryId);
        String productSpecId = createProductSpec(providerId);
        return createProductOffering(productSpecId, categoryId, priceId);
    }

    private String createTestOfferWithoutPolicy(Optional<String> priceId, String providerId) {
        String categoryId = createCategory();
        createProductCatalog(categoryId);
        String productSpecId = createProductSpecWithoutPolicy(providerId);
        return createProductOffering(productSpecId, categoryId, priceId);
    }

    public String createConsumerOrganization() {

        Optional<String> optionalId = getResponseId(Unirest.post(testConfiguration.getPartyCatalogHost() + "/tmf-api/party/v4/organization")
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
                        "}", TEST_CONSUMER_DID)));
        return optionalId.get();
    }

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
                        "        }\n" +
                        "    ]\n" +
                        "}", TEST_PROVIDER_DID)));
        return optionalId.get();
    }

    private void createProductCatalog(String category) {
        assertTrue(getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/catalog")
                .header("Content-Type", "application/json")
                .body(String.format("{\"description\": \"My catalog\",\"name\": \"my Catalog\",\n" +
                        "\"category\": [ {" +
                        "\"id\": \"%s\"" +
                        "} ]" +
                        "}", category))).isPresent(), "The product catalog was not created.");
    }

    private String createCategory() {
        Optional<String> categoryId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/category")
                .header("Content-Type", "application/json")
                .body("{\"description\": \"My category\",\"name\": \"my category\"\n}"));
        assertTrue(categoryId.isPresent(), "The category should have been created.");
        return categoryId.get();
    }

    private String createProductOffering(String productSpec, String categoryId, Optional<String> priceId) {

        String offeringBody = String.format("{\n" +
                "    \"description\": \"My Offering description\",\n" +
                "    \"isBundle\": false,\n" +
                "    \"isSellable\": true,\n" +
                "    \"lifecycleStatus\": \"Active\",\n" +
                "    \"name\": \"Packet Delivery Premium Service\",\n" +
                "    \"productSpecification\": {\n" +
                "        \"id\": \"%s\",\n" +
                "        \"name\": \"Packet Delivery Premium Service Spec\"\n" +
                "    },\n" +
                "    \"category\": [{\n" +
                "        \"id\": \"%s\"\n" +
                "    }]\n" +
                "}", productSpec, categoryId);
        if (priceId.isPresent()) {
            offeringBody = String.format("{\n" +
                    "    \"description\": \"My Offering description\",\n" +
                    "    \"isBundle\": false,\n" +
                    "    \"isSellable\": true,\n" +
                    "    \"lifecycleStatus\": \"Active\",\n" +
                    "    \"name\": \"Packet Delivery Premium Service\",\n" +
                    "    \"productSpecification\": {\n" +
                    "        \"id\": \"%s\",\n" +
                    "        \"name\": \"Packet Delivery Premium Service Spec\"\n" +
                    "    },\n" +
                    "    \"category\": [{\n" +
                    "        \"id\": \"%s\"\n" +
                    "    }],\n" +
                    "    \"productOfferingPrice\": [{\n" +
                    "        \"id\": \"%s\"\n" +
                    "    }]\n" +
                    "}", productSpec, categoryId, priceId.get());
        }

        Optional<String> productOfferingId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productOffering")
                .header("Content-Type", "application/json")
                .body(offeringBody));
        assertTrue(productOfferingId.isPresent(), "The product offering was not properly created.");

        return productOfferingId.get();
    }

    private List<Map<String, Object>> getPolicies() {
        HttpResponse<List> response = Unirest.get(testConfiguration.getOdrlPapHost() + "/policy").asObject(List.class);
        assertTrue(response.isSuccess(), "The agreements should have been returned");
        return response.getBody()
                .stream()
                .map(a -> objectMapper.convertValue(a, new TypeReference<Map<String, Object>>() {
                }))
                .peek(p -> log.warn("Got policy {}", ((Map<String, Object>) p).get("id")))
                .toList();
    }

    private List<ContractAgreementVO> getAgreements() {
        HttpResponse<List> response = Unirest.get(testConfiguration.getProviderRainbowHost() + "/api/v1/contract-negotiation/agreements").asObject(List.class);
        assertTrue(response.isSuccess(), "The agreements should have been returned");
        return response.getBody()
                .stream()
                .map(a -> objectMapper.convertValue(a, ContractAgreementVO.class)).toList();
    }

    private String createProductSpec(String providerId) {
        Optional<String> productSpecId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productSpecification")
                .header("Content-Type", "application/json")
                .body("{\n" +
                        "    \"name\": \"Packet Delivery Premium Service Spec\",\n" +
                        "    \"relatedParty\": [\n" +
                        "        {\n" +
                        "            \"id\": \"" + providerId + "\",\n" +
                        "            \"role\": \"provider\"\n" +
                        "        }\n" +
                        "    ],\n" +
                        "    \"productSpecCharacteristic\": [\n" +
                        "        {\n" +
                        "            \"id\": \"credentialsConfig\",\n" +
                        "            \"name\": \"Credentials Config for the Target Service\",\n" +
                        "            \"@schemaLocation\": \"https://raw.githubusercontent.com/FIWARE/contract-management/refs/heads/policy-support/schemas/credentials/credentialConfigCharacteristic.json\",\n" +
                        "            \"valueType\": \"credentialsConfiguration\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\":  " +
                        "						{\n" +
                        "						  \"credentialsType\":\"MyCredential\",\n" +
                        "						  \"claims\": [\n" +
                        "						  	{\n" +
                        "								\"name\": \"did:some:service\",\n" +
                        "								\"allowedValues\": [\"Consumer\",\"Admin\"]\n" +
                        " 							}\n" +
                        "						  ]\n" +
                        "        				},\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"policyConfig\",\n" +
                        "            \"name\": \"Policies for the Target Service\",\n" +
                        "            \"@schemaLocation\": \"https://raw.githubusercontent.com/FIWARE/contract-management/refs/heads/policy-support/schemas/odrl/policyCharacteristic.json\",\n" +
                        "            \"valueType\": \"authorizationPolicy\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\":  " +
                        "						{\n" +
                        "              \"@context\": {\n" +
                        "                \"dc\": \"http://purl.org/dc/elements/1.1/\",\n" +
                        "                \"dct\": \"http://purl.org/dc/terms/\",\n" +
                        "                \"owl\": \"http://www.w3.org/2002/07/owl#\",\n" +
                        "                \"odrl\": \"http://www.w3.org/ns/odrl/2/\",\n" +
                        "                \"rdfs\": \"http://www.w3.org/2000/01/rdf-schema#\",\n" +
                        "                \"skos\": \"http://www.w3.org/2004/02/skos/core#\"\n" +
                        "              },\n" +
                        "              \"@id\": \"https://mp-operation.org/policy/common/type\",\n" +
                        "              \"odrl:uid\": \"https://mp-operation.org/policy/common/type\",\n" +
                        "              \"@type\": \"odrl:Policy\",\n" +
                        "              \"odrl:permission\": {\n" +
                        "                \"odrl:assigner\": {\n" +
                        "                  \"@id\": \"https://www.mp-operation.org/\"\n" +
                        "                },\n" +
                        "                \"odrl:target\": {\n" +
                        "                  \"@type\": \"odrl:AssetCollection\",\n" +
                        "                  \"odrl:source\": \"urn:asset\",\n" +
                        "                  \"odrl:refinement\": [\n" +
                        "                    {\n" +
                        "                      \"@type\": \"odrl:Constraint\",\n" +
                        "                      \"odrl:leftOperand\": \"ngsi-ld:entityType\",\n" +
                        "                      \"odrl:operator\": {\n" +
                        "                        \"@id\": \"odrl:eq\"\n" +
                        "                      },\n" +
                        "                      \"odrl:rightOperand\": \"K8SCluster\"\n" +
                        "                    }\n" +
                        "                  ]\n" +
                        "                },\n" +
                        "                \"odrl:assignee\": {\n" +
                        "                  \"@type\": \"odrl:PartyCollection\",\n" +
                        "                  \"odrl:source\": \"urn:user\",\n" +
                        "                  \"odrl:refinement\": {\n" +
                        "                    \"@type\": \"odrl:LogicalConstraint\",\n" +
                        "                    \"odrl:and\": [\n" +
                        "                      {\n" +
                        "                        \"@type\": \"odrl:Constraint\",\n" +
                        "                        \"odrl:leftOperand\": {\n" +
                        "                          \"@id\": \"vc:role\"\n" +
                        "                        },\n" +
                        "                        \"odrl:operator\": {\n" +
                        "                          \"@id\": \"odrl:hasPart\"\n" +
                        "                        },\n" +
                        "                        \"odrl:rightOperand\": {\n" +
                        "                          \"@value\": \"OPERATOR\",\n" +
                        "                          \"@type\": \"xsd:string\"\n" +
                        "                        }\n" +
                        "                      },\n" +
                        "                      {\n" +
                        "                        \"@type\": \"odrl:Constraint\",\n" +
                        "                        \"odrl:leftOperand\": {\n" +
                        "                          \"@id\": \"vc:type\"\n" +
                        "                        },\n" +
                        "                        \"odrl:operator\": {\n" +
                        "                          \"@id\": \"odrl:hasPart\"\n" +
                        "                        },\n" +
                        "                        \"odrl:rightOperand\": {\n" +
                        "                          \"@value\": \"OperatorCredential\",\n" +
                        "                          \"@type\": \"xsd:string\"\n" +
                        "                        }\n" +
                        "                      }\n" +
                        "                    ]\n" +
                        "                  }\n" +
                        "                },\n" +
                        "                \"odrl:action\": {\n" +
                        "                  \"@id\": \"odrl:use\"\n" +
                        "                }\n" +
                        "              }\n" +
                        "            },\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"endpointUrl\",\n" +
                        "            \"name\": \"Service Endpoint URL\",\n" +
                        "            \"valueType\": \"endpointUrl\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"" + TEST_ENDPOINT + "\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"allowedAction\",\n" +
                        "            \"name\": \"Allowed Action\",\n" +
                        "            \"valueType\": \"allowedAction\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"odrl:use\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"endpointDescription\",\n" +
                        "            \"name\": \"Service Endpoint Description\",\n" +
                        "            \"valueType\": \"endpointDescription\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"The service\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}"));
        assertTrue(productSpecId.isPresent(), "The product spec should have been created.");

        return productSpecId.get();
    }

    private String createProductSpecWithoutPolicy(String providerId) {
        Optional<String> productSpecId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productSpecification")
                .header("Content-Type", "application/json")
                .body("{\n" +
                        "    \"name\": \"Packet Delivery Premium Service Spec\",\n" +
                        "    \"relatedParty\": [\n" +
                        "        {\n" +
                        "            \"id\": \"" + providerId + "\",\n" +
                        "            \"role\": \"provider\"\n" +
                        "        }\n" +
                        "    ],\n" +
                        "    \"productSpecCharacteristic\": [\n" +
                        "        {\n" +
                        "            \"id\": \"credentialsConfig\",\n" +
                        "            \"name\": \"Credentials Config for the Target Service\",\n" +
                        "            \"@schemaLocation\": \"https://raw.githubusercontent.com/FIWARE/contract-management/refs/heads/policy-support/schemas/credentials/credentialConfigCharacteristic.json\",\n" +
                        "            \"valueType\": \"credentialsConfiguration\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\":  " +
                        "						{\n" +
                        "						  \"credentialsType\":\"MyCredential\",\n" +
                        "						  \"claims\": [\n" +
                        "						  	{\n" +
                        "								\"name\": \"did:some:service\",\n" +
                        "								\"allowedValues\": [\"Consumer\",\"Admin\"]\n" +
                        " 							}\n" +
                        "						  ]\n" +
                        "        				},\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"endpointUrl\",\n" +
                        "            \"name\": \"Service Endpoint URL\",\n" +
                        "            \"valueType\": \"endpointUrl\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"" + TEST_ENDPOINT + "\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"allowedAction\",\n" +
                        "            \"name\": \"Allowed Action\",\n" +
                        "            \"valueType\": \"allowedAction\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"odrl:use\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        },\n" +
                        "        {\n" +
                        "            \"id\": \"endpointDescription\",\n" +
                        "            \"name\": \"Service Endpoint Description\",\n" +
                        "            \"valueType\": \"endpointDescription\",\n" +
                        "            \"productSpecCharacteristicValue\": [\n" +
                        "                {\n" +
                        "                    \"value\": \"The service\",\n" +
                        "                    \"isDefault\": true\n" +
                        "                }" +
                        "			]\n" +
                        "        }\n" +
                        "    ]\n" +
                        "}"));
        assertTrue(productSpecId.isPresent(), "The product spec should have been created.");

        return productSpecId.get();
    }

    public Optional<String> getResponseId(RequestBodyEntity request) {

        HttpResponse<JsonNode> response = request.asJson();
        assertTrue(response.isSuccess(),
                String.format("Req was %s Response %s: %s", request.getUrl(), response.getStatus(), response.getBody().toPrettyString()));

        return Optional.of(response)
                .filter(HttpResponse::isSuccess)
                .map(HttpResponse::getBody)
                .map(JsonNode::getObject)
                .map(obj -> obj.getString("id"));
    }
}
