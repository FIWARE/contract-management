package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the credential configuration resolution's two distinct outcomes.
 * <p>
 * Every case in {@link #incompleteSpecifications()} is a specification shape that occurs in deployed
 * catalogs and used to either raise a {@link NullPointerException} or make the resolution complete
 * empty - both of which abort the activation of the <i>whole</i> order. They must resolve to
 * "nothing configured".
 * <p>
 * A reference that cannot be read, in contrast, must fail loudly - one test per reference kind
 * (offering, specification, quote, provider).
 */
class CredentialsConfigResolverTest {

	private static final String OFFER_ID = "urn:ngsi-ld:product-offering:offer";
	private static final String SPEC_ID = "urn:ngsi-ld:product-specification:spec";
	private static final String QUOTE_ID = "urn:ngsi-ld:quote:quote";
	private static final String CREDENTIALS_CONFIGURATION = "credentialsConfiguration";
	private static final String CREDENTIAL_TYPE = "OperatorCredential";
	private static final String PROVIDER_ROLE = "provider";
	private static final String PROVIDER_ORG_ID = "urn:ngsi-ld:organization:provider";

	private ProductOfferingApiClient productOfferingApiClient;
	private ProductSpecificationApiClient productSpecificationApiClient;
	private QuoteApiClient quoteApiClient;
	private OrganizationResolver organizationResolver;

	private CredentialsConfigResolver credentialsConfigResolver;

	@BeforeEach
	void prepare() {
		productOfferingApiClient = mock(ProductOfferingApiClient.class);
		productSpecificationApiClient = mock(ProductSpecificationApiClient.class);
		quoteApiClient = mock(QuoteApiClient.class);
		organizationResolver = mock(OrganizationResolver.class);
		when(organizationResolver.hasProviderRole(any(String.class))).thenReturn(false);
		credentialsConfigResolver = new CredentialsConfigResolver(new ObjectMapper(), organizationResolver,
				productOfferingApiClient, productSpecificationApiClient, quoteApiClient);
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
				Arguments.of("a credentials characteristic without values",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.valueType(CREDENTIALS_CONFIGURATION)))),
				Arguments.of("a credentials characteristic with a null value",
						new ProductSpecificationVO().id(SPEC_ID)
								.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
										.valueType(CREDENTIALS_CONFIGURATION)
										.productSpecCharacteristicValue(
												List.of(new CharacteristicValueSpecificationVO()))))));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("incompleteSpecifications")
	void getCredentialsConfig_toleratesIncompleteSpecification(String description,
			ProductSpecificationVO specification) {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specification);

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithItem())
				.block();

		assertNotNull(configs, "The resolution should not complete empty.");
		assertEquals(1, configs.size(), "The offering should have been resolved.");
		assertEquals(List.of(), configs.get(0).credentialsVOS(), "No credential should have been resolved.");
	}

	@Test
	void getCredentialsConfig_readsCredentialFromSingleValue() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithCredentialsValue(Map.of(
				"credentialsType", CREDENTIAL_TYPE,
				"claims", List.of(Map.of("name", "roles", "allowedValues", List.of("OPERATOR"))))));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithItem())
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.get(0).credentialsVOS().size(), "The single credential object should have been read.");
		assertEquals(CREDENTIAL_TYPE, configs.get(0).credentialsVOS().get(0).getCredentialsType());
		assertEquals(1, configs.get(0).credentialsVOS().get(0).getClaims().size(), "The claims should be preserved.");
	}

	@Test
	void getCredentialsConfig_readsCredentialsFromValueArray() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithCredentialsValue(List.of(
				Map.of("credentialsType", CREDENTIAL_TYPE),
				Map.of("credentialsType", "UserCredential"))));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithItem())
				.block();

		assertNotNull(configs);
		assertEquals(2, configs.get(0).credentialsVOS().size(), "Both credentials of the array should have been read.");
	}

	@Test
	void getCredentialsConfig_bundleOfferingDoesNotAbortTheOrder() {
		// a bundled offering carries no productSpecification of its own
		mockOffer(new ProductOfferingVO().id(OFFER_ID).isBundle(true));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithItem())
				.block();

		assertNotNull(configs, "A bundle offering must not abort the resolution.");
		assertEquals(1, configs.size());
		assertEquals(List.of(), configs.get(0).credentialsVOS());
	}

	@Test
	void getCredentialsConfig_orderWithoutItemsResolvesToEmptyList() {
		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(new ProductOrderVO().id("urn:ngsi-ld:product-order:order"))
				.block();

		assertNotNull(configs, "An order without items must not complete empty.");
		assertEquals(List.of(), configs);
	}

	@Test
	void getCredentialsConfig_notAcceptedQuoteResolvesToEmptyList() {
		mockQuote(new QuoteVO().id(QUOTE_ID).state(QuoteStateTypeVO.IN_PROGRESS));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithQuote())
				.block();

		assertNotNull(configs, "A quote that is not accepted must not abort the resolution.");
		assertEquals(List.of(), configs);
	}

	@Test
	void getCredentialsConfig_readsCredentialFromAcceptedQuoteItem() {
		mockQuote(new QuoteVO()
				.id(QUOTE_ID)
				.state(QuoteStateTypeVO.ACCEPTED)
				.quoteItem(List.of(new QuoteItemVO()
						.state(QuoteStateTypeVO.ACCEPTED.getValue())
						.action("add")
						.productOffering(new org.fiware.iam.tmforum.quote.model.ProductOfferingRefVO().id(OFFER_ID)))));
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithCredentialsValue(Map.of("credentialsType", CREDENTIAL_TYPE)));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithQuote())
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.size());
		assertEquals(CREDENTIAL_TYPE, configs.get(0).credentialsVOS().get(0).getCredentialsType());
	}

	@Test
	void getCredentialsConfig_unresolvableProviderFailsTheResolution() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithCredentialsValue(Map.of("credentialsType", CREDENTIAL_TYPE))
				.relatedParty(List.of(new RelatedPartyVO()
						.id(PROVIDER_ORG_ID)
						.role(PROVIDER_ROLE))));
		when(organizationResolver.hasProviderRole(PROVIDER_ROLE)).thenReturn(true);
		// the provider's contract-management cannot be resolved
		when(organizationResolver.getContractManagement(any(String.class))).thenReturn(Mono.empty());

		TMForumException exception = assertThrows(TMForumException.class,
				() -> credentialsConfigResolver.getCredentialsConfig(orderWithItem()).block(),
				"A referenced provider that cannot be resolved must fail the resolution.");
		assertEquals(true, exception.getMessage().contains(PROVIDER_ORG_ID),
				"The message should name the provider that could not be resolved.");
		assertEquals(true, exception.getMessage().contains(SPEC_ID),
				"The message should name the specification holding the reference.");
	}

	@Test
	void getCredentialsConfig_unreadableOfferingFailsTheResolution() {
		when(productOfferingApiClient.retrieveProductOffering(eq(OFFER_ID), any())).thenReturn(Mono.empty());

		TMForumException exception = assertThrows(TMForumException.class,
				() -> credentialsConfigResolver.getCredentialsConfig(orderWithItem()).block(),
				"A referenced offering that cannot be read must fail the resolution.");
		assertEquals(true, exception.getMessage().contains(OFFER_ID),
				"The message should name the offering that could not be resolved.");
	}

	@Test
	void getCredentialsConfig_offeringWithoutBodyFailsTheResolution() {
		when(productOfferingApiClient.retrieveProductOffering(eq(OFFER_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok()));

		assertThrows(TMForumException.class,
				() -> credentialsConfigResolver.getCredentialsConfig(orderWithItem()).block(),
				"An offering response without a body must fail the resolution.");
	}

	@Test
	void getCredentialsConfig_unreadableSpecificationFailsTheResolution() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		when(productSpecificationApiClient.retrieveProductSpecification(eq(SPEC_ID), any())).thenReturn(Mono.empty());

		TMForumException exception = assertThrows(TMForumException.class,
				() -> credentialsConfigResolver.getCredentialsConfig(orderWithItem()).block(),
				"A referenced specification that cannot be read must fail the resolution.");
		assertEquals(true, exception.getMessage().contains(SPEC_ID),
				"The message should name the specification that could not be resolved.");
	}

	@Test
	void getCredentialsConfig_unreadableQuoteFailsTheResolution() {
		when(quoteApiClient.retrieveQuote(eq(QUOTE_ID), any())).thenReturn(Mono.empty());

		TMForumException exception = assertThrows(TMForumException.class,
				() -> credentialsConfigResolver.getCredentialsConfig(orderWithQuote()).block(),
				"A referenced quote that cannot be read must fail the resolution.");
		assertEquals(true, exception.getMessage().contains(QUOTE_ID),
				"The message should name the quote that could not be resolved.");
	}

	@Test
	void getCredentialsConfig_resolvesTheProvidersContractManagement() {
		mockOffer(new ProductOfferingVO().id(OFFER_ID).productSpecification(new ProductSpecificationRefVO().id(SPEC_ID)));
		mockSpecification(specificationWithCredentialsValue(Map.of("credentialsType", CREDENTIAL_TYPE))
				.relatedParty(List.of(new RelatedPartyVO()
						.id(PROVIDER_ORG_ID)
						.role(PROVIDER_ROLE))));
		when(organizationResolver.hasProviderRole(PROVIDER_ROLE)).thenReturn(true);
		when(organizationResolver.getContractManagement(any(String.class)))
				.thenReturn(Mono.just(new ContractManagement(true)));

		List<CredentialsConfigResolver.CredentialConfig> configs = credentialsConfigResolver
				.getCredentialsConfig(orderWithItem())
				.block();

		assertNotNull(configs);
		assertEquals(1, configs.get(0).credentialsVOS().size());
		assertEquals(true, configs.get(0).contractManagement().isLocal());
	}

	private ProductSpecificationVO specificationWithCredentialsValue(Object value) {
		return new ProductSpecificationVO()
				.id(SPEC_ID)
				.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
						.id(CREDENTIALS_CONFIGURATION)
						.valueType(CREDENTIALS_CONFIGURATION)
						.productSpecCharacteristicValue(List.of(new CharacteristicValueSpecificationVO()
								.isDefault(true)
								.value(value)))));
	}

	private ProductOrderVO orderWithItem() {
		return new ProductOrderVO()
				.id("urn:ngsi-ld:product-order:order")
				.productOrderItem(List.of(new ProductOrderItemVO()
						.action(OrderItemActionTypeVO.ADD)
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

	private void mockSpecification(ProductSpecificationVO specification) {
		when(productSpecificationApiClient.retrieveProductSpecification(eq(SPEC_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok(specification)));
	}

	private void mockQuote(QuoteVO quote) {
		when(quoteApiClient.retrieveQuote(eq(QUOTE_ID), any()))
				.thenReturn(Mono.just(HttpResponse.ok(quote)));
	}
}
