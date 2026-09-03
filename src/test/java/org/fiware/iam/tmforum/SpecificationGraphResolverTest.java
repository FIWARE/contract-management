package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.BundledProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ServiceSpecificationRefVO;
import org.fiware.iam.tmforum.servicecatalog.api.ServiceSpecificationApiClient;
import org.fiware.iam.tmforum.servicecatalog.model.CharacteristicSpecificationVO;
import org.fiware.iam.tmforum.servicecatalog.model.ServiceSpecificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the walk over a composed {@code ProductSpecification}: what it collects, that it terminates,
 * and how it reports what it left out.
 */
class SpecificationGraphResolverTest {

	private static final String PRODUCT_ID = "urn:ngsi-ld:product-specification:product";
	private static final String SERVICE_ID = "urn:ngsi-ld:service-specification:service";
	private static final String OTHER_SERVICE_ID = "urn:ngsi-ld:service-specification:other-service";
	private static final String BUNDLED_ID = "urn:ngsi-ld:product-specification:bundled";
	private static final String AUTHORIZATION_POLICY = "authorizationPolicy";
	private static final String PROVIDER_ROLE = "provider";

	private GeneralProperties generalProperties;
	private ProductSpecificationApiClient productSpecificationApiClient;
	private ServiceSpecificationApiClient serviceSpecificationApiClient;

	private SpecificationGraphResolver specificationGraphResolver;

	@BeforeEach
	void prepare() {
		generalProperties = new GeneralProperties();
		generalProperties.setEnableSpecificationComposition(true);
		productSpecificationApiClient = mock(ProductSpecificationApiClient.class);
		serviceSpecificationApiClient = mock(ServiceSpecificationApiClient.class);
		specificationGraphResolver = new SpecificationGraphResolver(generalProperties,
				productSpecificationApiClient, serviceSpecificationApiClient);
	}

	@Test
	void resolve_compositionDisabled_returnsOnlyTheProduct() {
		generalProperties.setEnableSpecificationComposition(false);

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID))
				.block();

		assertNotNull(graph);
		assertEquals(1, graph.nodes().size(), "Only the ordered specification should be resolved.");
		assertEquals(PRODUCT_ID, graph.nodes().get(0).id());
		assertFalse(graph.truncated(), "Not traversing at all is not a truncation.");
		verify(serviceSpecificationApiClient, never()).retrieveServiceSpecification(any(), any());
	}

	@Test
	void resolve_collectsTheServiceSpecifications() {
		mockService(serviceWithPolicy(SERVICE_ID, "policy-a"));
		mockService(serviceWithPolicy(OTHER_SERVICE_ID, "policy-b"));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID, OTHER_SERVICE_ID))
				.block();

		assertNotNull(graph);
		assertEquals(3, graph.nodes().size(), "The product and both services should be resolved.");
		assertEquals(SpecificationGraphResolver.SpecificationKind.PRODUCT, graph.nodes().get(0).kind());
		assertEquals(2, graph.characteristics().size(), "Both service policies should be aggregated.");
		assertFalse(graph.truncated());
	}

	@Test
	void resolve_readsTheServiceCharacteristicShape() {
		mockService(serviceWithPolicy(SERVICE_ID, "policy-a"));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID))
				.block();

		assertNotNull(graph);
		CharacteristicValues.Characteristic characteristic = graph.characteristics().get(0);
		assertEquals(AUTHORIZATION_POLICY, characteristic.valueType(),
				"specCharacteristic.valueType should be normalized like productSpecCharacteristic.valueType.");
		assertEquals(1, characteristic.values().size(),
				"characteristicValueSpecification.value should be normalized like productSpecCharacteristicValue.value.");
	}

	@Test
	void resolve_serviceWithoutCharacteristicsIsNotAnError() {
		mockService(new ServiceSpecificationVO().id(SERVICE_ID));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID))
				.block();

		assertNotNull(graph);
		assertEquals(2, graph.nodes().size());
		assertEquals(List.of(), graph.characteristics());
	}

	@Test
	void resolve_keepsTheRelatedPartiesOfEveryNode() {
		mockService(serviceWithPolicy(SERVICE_ID, "policy-a")
				.relatedParty(List.of(new org.fiware.iam.tmforum.servicecatalog.model.RelatedPartyVO()
						.id("urn:ngsi-ld:organization:service-provider")
						.role(PROVIDER_ROLE))));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID))
				.block();

		assertNotNull(graph);
		SpecificationGraphResolver.SpecificationNode serviceNode = graph.nodes().get(1);
		assertEquals(1, serviceNode.relatedParties().size(), "The service's provider should be available.");
		assertEquals(PROVIDER_ROLE, serviceNode.relatedParties().get(0).role());
	}

	@Test
	void resolve_unreadableServiceFailsTheResolution() {
		when(serviceSpecificationApiClient.retrieveServiceSpecification(eq(SERVICE_ID), any()))
				.thenReturn(Mono.empty());

		TMForumException exception = assertThrows(TMForumException.class,
				() -> specificationGraphResolver.resolve(productWithServices(SERVICE_ID)).block(),
				"A referenced service specification that cannot be read must fail the resolution.");
		assertTrue(exception.getMessage().contains(SERVICE_ID),
				"The message should name the service specification that could not be resolved.");
		assertTrue(exception.getMessage().contains(PRODUCT_ID),
				"The message should name the specification holding the reference.");
	}

	@Test
	void resolve_serviceWithoutBodyFailsTheResolution() {
		when(serviceSpecificationApiClient.retrieveServiceSpecification(eq(SERVICE_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok()));

		assertThrows(TMForumException.class,
				() -> specificationGraphResolver.resolve(productWithServices(SERVICE_ID)).block(),
				"A service response without a body must fail the resolution.");
	}

	@Test
	void resolve_followsBundledProductSpecifications() {
		ProductSpecificationVO bundled = specificationWithServices(BUNDLED_ID, SERVICE_ID);
		mockProduct(bundled);
		mockService(serviceWithPolicy(SERVICE_ID, "policy-a"));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productBundling(BUNDLED_ID))
				.block();

		assertNotNull(graph);
		assertEquals(3, graph.nodes().size(), "The product, the bundled product and its service should be resolved.");
		assertEquals(1, graph.characteristics().size());
		assertFalse(graph.truncated());
	}

	@Test
	void resolve_terminatesOnACycleAndVisitsEachSpecificationOnce() {
		// the bundled specification bundles the ordered one back
		ProductSpecificationVO bundled = new ProductSpecificationVO()
				.id(BUNDLED_ID)
				.bundledProductSpecification(List.of(new BundledProductSpecificationVO().id(PRODUCT_ID)));
		mockProduct(bundled);

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productBundling(BUNDLED_ID))
				.block();

		assertNotNull(graph, "A cycle must not loop forever.");
		assertEquals(2, graph.nodes().size(), "Each specification should appear exactly once.");
		verify(productSpecificationApiClient, times(1)).retrieveProductSpecification(eq(BUNDLED_ID), any());
	}

	@Test
	void resolve_resolvesAServiceReferencedTwiceOnlyOnce() {
		mockService(serviceWithPolicy(SERVICE_ID, "policy-a"));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID, SERVICE_ID))
				.block();

		assertNotNull(graph);
		assertEquals(2, graph.nodes().size());
		verify(serviceSpecificationApiClient, times(1)).retrieveServiceSpecification(eq(SERVICE_ID), any());
	}

	@ParameterizedTest(name = "maxDepth={0}")
	@ValueSource(ints = { 0, -1 })
	void resolve_truncatesAtTheDepthLimit(int maxDepth) {
		generalProperties.setSpecificationCompositionMaxDepth(maxDepth);

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productWithServices(SERVICE_ID))
				.block();

		assertNotNull(graph);
		assertEquals(1, graph.nodes().size(), "Nothing below the limit should be resolved.");
		assertTrue(graph.truncated(), "The truncation has to be reported.");
		verify(serviceSpecificationApiClient, never()).retrieveServiceSpecification(any(), any());
	}

	@Test
	void resolve_truncatesBelowTheBundledSpecification() {
		generalProperties.setSpecificationCompositionMaxDepth(1);
		// the bundled specification references a service, which is one level too deep
		mockProduct(specificationWithServices(BUNDLED_ID, SERVICE_ID));

		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(productBundling(BUNDLED_ID))
				.block();

		assertNotNull(graph);
		assertEquals(2, graph.nodes().size(), "The bundled product is resolved, its service is not.");
		assertTrue(graph.truncated());
		verify(serviceSpecificationApiClient, never()).retrieveServiceSpecification(any(), any());
	}

	@Test
	void resolve_productWithoutReferencesIsNotTruncated() {
		SpecificationGraphResolver.SpecificationGraph graph = specificationGraphResolver
				.resolve(new ProductSpecificationVO().id(PRODUCT_ID))
				.block();

		assertNotNull(graph);
		assertEquals(1, graph.nodes().size());
		assertFalse(graph.truncated());
	}

	private ProductSpecificationVO productWithServices(String... serviceIds) {
		return specificationWithServices(PRODUCT_ID, serviceIds);
	}

	private ProductSpecificationVO specificationWithServices(String productId, String... serviceIds) {
		return new ProductSpecificationVO()
				.id(productId)
				.serviceSpecification(List.of(serviceIds).stream()
						.map(id -> new ServiceSpecificationRefVO().id(id))
						.toList());
	}

	private ProductSpecificationVO productBundling(String bundledId) {
		return new ProductSpecificationVO()
				.id(PRODUCT_ID)
				.bundledProductSpecification(List.of(new BundledProductSpecificationVO().id(bundledId)));
	}

	private ServiceSpecificationVO serviceWithPolicy(String serviceId, String policyId) {
		return new ServiceSpecificationVO()
				.id(serviceId)
				.specCharacteristic(List.of(new CharacteristicSpecificationVO()
						.id(AUTHORIZATION_POLICY)
						.valueType(AUTHORIZATION_POLICY)
						.characteristicValueSpecification(List.of(
								new org.fiware.iam.tmforum.servicecatalog.model.CharacteristicValueSpecificationVO()
										.isDefault(true)
										.value(java.util.Map.of("odrl:uid", policyId))))));
	}

	private void mockService(ServiceSpecificationVO serviceSpecification) {
		when(serviceSpecificationApiClient.retrieveServiceSpecification(eq(serviceSpecification.getId()), any()))
				.thenReturn(Mono.just(HttpResponse.ok(serviceSpecification)));
	}

	private void mockProduct(ProductSpecificationVO productSpecification) {
		when(productSpecificationApiClient.retrieveProductSpecification(eq(productSpecification.getId()), any()))
				.thenReturn(Mono.just(HttpResponse.ok(productSpecification)));
	}
}
