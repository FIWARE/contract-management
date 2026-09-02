package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productorder.model.OrderItemActionTypeVO;
import org.fiware.iam.tmforum.productorder.model.ProductOfferingRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.productorder.model.QuoteRefVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.fiware.iam.tmforum.quote.model.QuoteItemVO;
import org.fiware.iam.tmforum.quote.model.QuoteStateTypeVO;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the tolerance of the policy resolution. Every case in {@link #incompleteSpecifications()} is
 * a specification shape that occurs in deployed catalogs and used to either raise a
 * {@link NullPointerException} or make the resolution complete empty - both of which abort the
 * activation of the <i>whole</i> order.
 */
class PolicyResolverTest {

	private static final String OFFER_ID = "urn:ngsi-ld:product-offering:offer";
	private static final String SPEC_ID = "urn:ngsi-ld:product-specification:spec";
	private static final String QUOTE_ID = "urn:ngsi-ld:quote:quote";
	private static final String POLICY_ID = "https://mp-operation.org/policy/test";
	private static final String AUTHORIZATION_POLICY = "authorizationPolicy";
	private static final String QUOTE_DELETE_ACTION = "delete";

	private ProductOfferingApiClient productOfferingApiClient;
	private ProductSpecificationApiClient productSpecificationApiClient;
	private QuoteApiClient quoteApiClient;
	private OrganizationResolver organizationResolver;

	private PolicyResolver policyResolver;

	@BeforeEach
	void prepare() {
		productOfferingApiClient = mock(ProductOfferingApiClient.class);
		productSpecificationApiClient = mock(ProductSpecificationApiClient.class);
		quoteApiClient = mock(QuoteApiClient.class);
		organizationResolver = mock(OrganizationResolver.class);
		when(organizationResolver.hasProviderRole(any(String.class))).thenReturn(false);
		policyResolver = new PolicyResolver(new ObjectMapper(), productOfferingApiClient,
				productSpecificationApiClient, quoteApiClient, organizationResolver);
	}

	private static Stream<Arguments> incompleteSpecifications() {
		return Stream.of(
				Arguments.of("no characteristics at all - the shape of a composed specification",
						new ProductSpecificationVO().id(SPEC_ID)),
				Arguments.of("empty characteristics list",
						new ProductSpecificationVO().id(SPEC_ID).productSpecCharacteristic(List.of())),
				Arguments.of("a characteristic without a valueType",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.id("someCharacteristic")))),
				Arguments.of("a policy characteristic without values",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.valueType(AUTHORIZATION_POLICY)))),
				Arguments.of("a policy characteristic with an empty value list",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.valueType(AUTHORIZATION_POLICY)
										.productSpecCharacteristicValue(List.of())))),
				Arguments.of("a policy characteristic with a null value",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.valueType(AUTHORIZATION_POLICY)
										.productSpecCharacteristicValue(
												List.of(new CharacteristicValueSpecificationVO()))))));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("incompleteSpecifications")
	void getAuthorizationPolicy_toleratesIncompleteSpecification(String description,
			ProductSpecificationVO specification) {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specification);

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs, "The resolution should not complete empty.");
		assertEquals(1, configs.size(), "The offering should have been resolved.");
		assertEquals(List.of(), configs.get(0).policies(), "No policy should have been resolved.");
	}

	@Test
	void getAuthorizationPolicy_readsPolicyFromSingleValue() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithPolicyValue(Map.of("odrl:uid", POLICY_ID)));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.get(0).policies().size(), "The single policy object should have been read.");
		assertEquals(POLICY_ID, configs.get(0).policies().get(0).get("odrl:uid"));
	}

	@Test
	void getAuthorizationPolicy_readsPolicyFromValueArray() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithPolicyValue(List.of(Map.of("odrl:uid", POLICY_ID),
				Map.of("odrl:uid", POLICY_ID + "-2"))));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs);
		assertEquals(2, configs.get(0).policies().size(), "Both policies of the array should have been read.");
	}

	@Test
	void getAuthorizationPolicy_bundleOfferingDoesNotAbortTheOrder() {
		// a bundled offering carries no productSpecification of its own
		mockOffer(new ProductOfferingVO().id(OFFER_ID).isBundle(true));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs, "A bundle offering must not abort the resolution.");
		assertEquals(1, configs.size());
		assertEquals(List.of(), configs.get(0).policies());
	}

	@Test
	void getAuthorizationPolicy_orderWithoutItemsResolvesToEmptyList() {
		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(new ProductOrderVO().id("urn:ngsi-ld:product-order:order"))
				.block();

		assertNotNull(configs, "An order without items must not complete empty.");
		assertEquals(List.of(), configs);
	}

	@Test
	void getAuthorizationPolicy_itemWithoutOfferingIsSkipped() {
		ProductOrderVO order = new ProductOrderVO()
				.id("urn:ngsi-ld:product-order:order")
				.productOrderItem(List.of(new ProductOrderItemVO().action(OrderItemActionTypeVO.ADD)));

		List<PolicyResolver.PolicyConfig> configs = policyResolver.getAuthorizationPolicy(order).block();

		assertNotNull(configs);
		assertEquals(List.of(), configs);
	}

	@Test
	void getAuthorizationPolicy_notAcceptedQuoteResolvesToEmptyList() {
		mockQuote(new QuoteVO().id(QUOTE_ID).state(QuoteStateTypeVO.IN_PROGRESS));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithQuote())
				.block();

		assertNotNull(configs, "A quote that is not accepted must not abort the resolution.");
		assertEquals(List.of(), configs);
	}

	@Test
	void getAuthorizationPolicy_acceptedQuoteWithoutItemsResolvesToEmptyList() {
		mockQuote(new QuoteVO().id(QUOTE_ID).state(QuoteStateTypeVO.ACCEPTED));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithQuote())
				.block();

		assertNotNull(configs, "An accepted quote without items must not abort the resolution.");
		assertEquals(List.of(), configs);
	}

	@Test
	void getAuthorizationPolicy_quoteItemWithoutStateIsSkipped() {
		mockQuote(new QuoteVO()
				.id(QUOTE_ID)
				.state(QuoteStateTypeVO.ACCEPTED)
				.quoteItem(List.of(new QuoteItemVO().action(QUOTE_DELETE_ACTION))));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithQuote())
				.block();

		assertNotNull(configs);
		assertEquals(List.of(), configs);
	}

	@Test
	void getAuthorizationPolicy_readsPolicyFromAcceptedQuoteItem() {
		mockQuote(new QuoteVO()
				.id(QUOTE_ID)
				.state(QuoteStateTypeVO.ACCEPTED)
				.quoteItem(List.of(new QuoteItemVO()
						.state(QuoteStateTypeVO.ACCEPTED.getValue())
						.action("add")
						.productOffering(new org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO().id(OFFER_ID)))));
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithPolicyValue(Map.of("odrl:uid", POLICY_ID)));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithQuote())
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.size());
		assertEquals(POLICY_ID, configs.get(0).policies().get(0).get("odrl:uid"));
	}

	@Test
	void getAuthorizationPolicy_unresolvableProviderDropsThePolicies() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		ProductSpecificationVO specification = specificationWithPolicyValue(Map.of("odrl:uid", POLICY_ID))
				.relatedParty(List.of(new org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO()
						.id("urn:ngsi-ld:organization:provider")
						.role("provider")));
		mockSpecification(specification);
		when(organizationResolver.hasProviderRole("provider")).thenReturn(true);
		// the provider's contract-management cannot be resolved
		when(organizationResolver.getContractManagement(any(String.class))).thenReturn(Mono.empty());

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs, "An unresolvable provider must not abort the resolution.");
		assertEquals(List.of(), configs.get(0).policies(),
				"The policies must be dropped rather than applied locally.");
	}

	@Test
	void getAuthorizationPolicy_resolvesTheProvidersContractManagement() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		ProductSpecificationVO specification = specificationWithPolicyValue(Map.of("odrl:uid", POLICY_ID))
				.relatedParty(List.of(new org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO()
						.id("urn:ngsi-ld:organization:provider")
						.role("provider")));
		mockSpecification(specification);
		when(organizationResolver.hasProviderRole("provider")).thenReturn(true);
		when(organizationResolver.getContractManagement(any(String.class)))
				.thenReturn(Mono.just(new ContractManagement(true)));

		List<PolicyResolver.PolicyConfig> configs = policyResolver
				.getAuthorizationPolicy(orderWithItem(OrderItemActionTypeVO.ADD))
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.get(0).policies().size());
		assertEquals(true, configs.get(0).contractManagement().isLocal());
	}

	private ProductSpecificationVO specificationWithPolicyValue(Object value) {
		return new ProductSpecificationVO()
				.id(SPEC_ID)
				.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
						.id(AUTHORIZATION_POLICY)
						.valueType(AUTHORIZATION_POLICY)
						.productSpecCharacteristicValue(List.of(new CharacteristicValueSpecificationVO()
								.isDefault(true)
								.value(value)))));
	}

	private ProductOrderVO orderWithItem(OrderItemActionTypeVO action) {
		return new ProductOrderVO()
				.id("urn:ngsi-ld:product-order:order")
				.productOrderItem(List.of(new ProductOrderItemVO()
						.action(action)
						.productOffering(new ProductOfferingRefVO().id(OFFER_ID))));
	}

	private ProductOrderVO orderWithQuote() {
		return new ProductOrderVO()
				.id("urn:ngsi-ld:product-order:order")
				.quote(List.of(new QuoteRefVO().id(QUOTE_ID)));
	}

	private void mockOffer(ProductOfferingVO offering) {
		when(productOfferingApiClient.retrieveProductOffering(eq(OFFER_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok(offering)));
	}

	private void mockQuote(QuoteVO quote) {
		when(quoteApiClient.retrieveQuote(eq(QUOTE_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok(quote)));
	}

	private void mockSpecification(ProductSpecificationVO specification) {
		when(productSpecificationApiClient.retrieveProductSpecification(eq(SPEC_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok(specification)));
	}
}
