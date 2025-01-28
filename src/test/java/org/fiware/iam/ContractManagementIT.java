package org.fiware.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micronaut.context.annotation.Value;
import kong.unirest.*;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.AgreementTmfVO;
import org.fiware.iam.tmforum.handlers.ProductOfferingEventHandler;
import org.fiware.iam.tmforum.handlers.ProductOrderEventHandler;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.rainbow.model.AgreementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public abstract class ContractManagementIT {

	private static final String TEST_DID = "did:web:bunnyinc.dsba.fiware.dev:did";
	private static final String TEST_SERVICE = "did:some:service";
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	protected final TestConfiguration testConfiguration;

	@Value("${general.til.credentialType}")
	private String credentialType;


	protected ContractManagementIT(com.fasterxml.jackson.databind.ObjectMapper objectMapper, TestConfiguration testConfiguration) {
		this.objectMapper = objectMapper;
		this.testConfiguration = testConfiguration;
	}

	/**
	 * Checks if the contract management is running and healthy
	 */
	abstract void contractManagementHealthy();

	@DisplayName("Test Happy Path")
	@Test
	public void testCreateProductOrder() {
		contractManagementHealthy();

		String organizationId = createOrganization();
		String offeringId = createTestOffer();
		String productOrder = orderProduct(offeringId, organizationId);
		completeProductOrder(productOrder);

		assertAgreementCreated(offeringId);
		assertAgreementReferenced(productOrder);
		assertTilEntry(TEST_DID, credentialType, TEST_SERVICE, Set.of("Consumer", "Admin"));
	}

	private void assertTilEntry(String expectedDid, String expectedCredentialsType, String expectedService, Set<String> expectedValues) {
		JSONObject tilConfig = Awaitility.await()
				.atMost(1, TimeUnit.MINUTES)
				.until(() -> getTrustedIssuersListEntry(TEST_DID), Optional::isPresent)
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

	private void assertAgreementCreated(String offerId) {

		Awaitility.await().atMost(1, TimeUnit.MINUTES).untilAsserted(() -> {
					boolean match = getAgreements().stream()
							.anyMatch(agreementVO -> agreementVO.getDataServiceId().equals(offerId));
					assertTrue(match, String.format("No agreement for %s", offerId));
				}
		);
	}


	private void assertAgreementReferenced(String orderId) {
		Awaitility.await().atMost(1, TimeUnit.MINUTES).untilAsserted(() -> {
					ProductOrderVO productOrderVO = getProductOrder(orderId);
					assertNotNull(productOrderVO.getAgreement(), "An agreement should be linked to the order.");
					assertTrue(
							productOrderVO.getAgreement()
									.stream()
									.map(AgreementRefVO::getId)
									.map(this::getTmfAgreement)
									.map(AgreementTmfVO::getCharacteristic)
									.flatMap(List::stream)
									.anyMatch(characteristicTmfVO ->
											characteristicTmfVO.getName().equals(TMForumAdapter.DATA_SPACE_PROTOCOL_AGREEMENT_ID)
									),
							"An agreement should be linked in the order.");
				}
		);
	}

	private AgreementTmfVO getTmfAgreement(String agreementId) {
		HttpResponse<AgreementTmfVO> response = Unirest.get(testConfiguration.getAgreementHost() + "/tmf-api/agreementManagement/v4/agreement/" + agreementId).asObject(AgreementTmfVO.class);
		assertTrue(response.isSuccess(), "The agreement should have been returned successfully.");
		return response.getBody();
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

	private String createTestOffer() {
		String categoryId = createCategory();
		createProductCatalog(categoryId);
		String productSpecId = createProductSpec();
		return createProductOffering(productSpecId, categoryId);
	}

	private String createOrganization() {

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
						"}", TEST_DID)));
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

	private String createProductOffering(String productSpec, String categoryId) {
		Optional<String> productOfferingId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productOffering")
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
						"    },\n" +
						"    \"category\": [{\n" +
						"        \"id\": \"%s\"\n" +
						"    }]\n" +
						"}", productSpec, categoryId)));
		assertTrue(productOfferingId.isPresent(), "The product offering was not properly created.");

		return productOfferingId.get();
	}

	private List<AgreementVO> getAgreements() {
		HttpResponse<List> response = Unirest.get(testConfiguration.getProviderRainbowHost() + "/api/v1/agreements").asObject(List.class);
		assertTrue(response.isSuccess(), "The agreements should have been returned");
		return response.getBody()
				.stream()
				.map(a -> objectMapper.convertValue(a, AgreementVO.class)).toList();
	}

	private String createProductSpec() {
		Optional<String> productSpecId = getResponseId(Unirest.post(testConfiguration.getProductCatalogHost() + "/tmf-api/productCatalogManagement/v4/productSpecification")
				.header("Content-Type", "application/json")
				.body("{\n" +
						"    \"name\": \"Packet Delivery Premium Service Spec\",\n" +
						"    \"productSpecCharacteristic\": [\n" +
						"        {\n" +
						"            \"id\": \"endpointUrl\",\n" +
						"            \"name\": \"Service Endpoint URL\",\n" +
						"            \"valueType\": \"endpointUrl\",\n" +
						"            \"productSpecCharacteristicValue\": [\n" +
						"                {\n" +
						"                    \"value\": \"https://the-service.org\",\n" +
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
						"                    \"value\": \"The service\"\n" +
						"                }" +
						"			]\n" +
						"        }\n" +
						"    ]\n" +
						"}"));
		assertTrue(productSpecId.isPresent(), "The product spec should have been created.");

		return productSpecId.get();
	}

	private Optional<String> getResponseId(RequestBodyEntity request) {

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
