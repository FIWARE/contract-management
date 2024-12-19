package org.fiware.iam;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import kong.unirest.*;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@MicronautTest
public class ContractManagementIT {

	private static final String TEST_DID = "did:web:bunnyinc.dsba.fiware.dev:did";

	@DisplayName("Test Happy Path")
	@Test
	public void testCreateProductOrder() {
		String categoryId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(this::createCategory, Optional::isPresent).get();
		String catalogId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> createProductCatalog(categoryId), Optional::isPresent).get();

		String productSpecId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(this::createProductSpec, Optional::isPresent).get();
		System.out.println("productSpecId: " + productSpecId);
		String productOfferingId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> createProductOffering(productSpecId, categoryId), Optional::isPresent).get();
		System.out.println("productOfferingId: " + productOfferingId);
		String organizationId = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(this::createOrganization, Optional::isPresent).get();
		System.out.println("organizationId: " + organizationId);
		String productOrder = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> orderProduct(productOfferingId, organizationId), Optional::isPresent).get();
		System.out.println("productOrder: " + productOrder);
		Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> completeProductOrder(productOrder), Optional::isPresent).get();

		JSONObject tilConfig = Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> getTrustedIssuersListEntry(TEST_DID), Optional::isPresent).get();
		System.out.println("tilConfig: " + tilConfig);
		Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> changeStateProductOrder(productOrder), Optional::isPresent).get();
		Assertions.assertEquals("did:web:bunnyinc.dsba.fiware.dev:did", tilConfig.getString("did"));
		JSONArray credentials = tilConfig.getJSONArray("credentials");
		Assertions.assertNotNull(credentials);
		Assertions.assertEquals(1, credentials.length());
		JSONObject credential = credentials.getJSONObject(0);

		Assertions.assertEquals("MyCredential", credential.getString("credentialsType"));
		JSONArray claims = credential.getJSONArray("claims");
		Assertions.assertEquals(1, claims.length());
		JSONObject claim = claims.getJSONObject(0);
		Assertions.assertEquals("did:some:service", claim.getString("name"));
		Assertions.assertEquals(List.of("Consumer", "Admin"), claim.getJSONArray("allowedValues").toList());

		Awaitility.await().atMost(2, TimeUnit.MINUTES).until(() -> deleteProductOffering(productOfferingId));
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


	private Optional<String> completeProductOrder(String productOrderId) {
		return getResponseId(
				Unirest.patch("http://localhost:8081/tmf-api/productOrderingManagement/v4/productOrder/%s".formatted(productOrderId))
						.header("Content-Type", "application/json")
						.body(String.format("{\n" +
								"    \"state\": \"completed\" " +
								"}")));
	}


	private Optional<String> changeStateProductOrder(String productOrderId) {
		return getResponseId(Unirest.patch("http://localhost:8081/tmf-api/productOrderingManagement/v4/productOrder/" + productOrderId)
				.header("Content-Type", "application/json")
				.body("{\"state\": \"cancelled\" }"));
	}

	private Optional<String> updateProductOrder(String productOrderId) {
		return getResponseId(Unirest.patch("http://localhost:8081/tmf-api/productOrderingManagement/v4/productOrder/" + productOrderId)
				.header("Content-Type", "application/json")
				.body(String.format("{\n" +
						"    \"state\": \"completed\",\n" +
						"    \"priority\": \"3\"" +
						"}")));
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

	private Optional<String> createProductCatalog(String category) {
		return getResponseId(Unirest.post("http://localhost:8082/tmf-api/productCatalogManagement/v4/catalog")
				.header("Content-Type", "application/json")
				.body(String.format("{\"description\": \"My catalog\",\"name\": \"my Catalog\",\n" +
						"\"category\": [ {" +
						"\"id\": \"%s\"" +
						"} ]" +
						"}", category)));
	}

	private Optional<String> createCategory() {
		return getResponseId(Unirest.post("http://localhost:8082/tmf-api/productCatalogManagement/v4/category")
				.header("Content-Type", "application/json")
				.body("{\"description\": \"My category\",\"name\": \"my category\"\n}"));
	}

	private Optional<String> createProductOffering(String productSpec, String categoryId) {
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
						"    },\n" +
						"    \"category\": [{\n" +
						"        \"id\": \"%s\"\n" +
						"    }]\n" +
						"}", productSpec, categoryId)));

	}

	private boolean deleteProductOffering(String offeringId) {
		return Unirest.delete("http://localhost:8082/tmf-api/productCatalogManagement/v4/productOffering/%s".formatted(offeringId))
				.asJson()
				.isSuccess();
	}

	private Optional<String> createProductSpec() {
		return getResponseId(Unirest.post("http://localhost:8082/tmf-api/productCatalogManagement/v4/productSpecification")
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
	}

	private Optional<String> getResponseId(RequestBodyEntity request) {
		try {
			HttpResponse<JsonNode> response = request.asJson();
			if (!response.isSuccess()) {
				log.info("Req was {} Response {}: {}", response.getStatus(), response.getBody().toPrettyString());
			}

			return Optional.ofNullable(response)
					.filter(HttpResponse::isSuccess)
					.map(HttpResponse::getBody)
					.map(JsonNode::getObject)
					.map(obj -> obj.getString("id"));
		} catch (UnirestException e) {
			log.warn("Error.", e);
			return Optional.empty();
		}
	}
}
