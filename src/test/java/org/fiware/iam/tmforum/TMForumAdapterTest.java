package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.configuration.ConsentProperties;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.AgreementTmfVO;
import org.fiware.iam.tmforum.agreement.model.AgreementCreateTmfVO;
import org.fiware.iam.tmforum.agreement.model.AgreementUpdateTmfVO;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.agreement.model.CharacteristicTmfVO;
import org.fiware.iam.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderUpdateVO;
import org.mockito.ArgumentCaptor;
import org.fiware.iam.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.iam.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TMForumAdapterTest {

	private OrganizationResolver organizationResolver;
	private ProductOrderApiClient productOrderApiClient;
	private AgreementApiClient agreementApiClient;
	private ProductSpecificationApiClient productSpecificationApiClient;
	private ProductOfferingApiClient productOfferingApiClient;
	private QuoteApiClient quoteApiClient;

	private ConsentProperties consentProperties;

	private TMForumAdapter tmForumAdapter;

	@BeforeEach
	public void prepare() {
		organizationResolver = mock(OrganizationResolver.class);
		productOrderApiClient = mock(ProductOrderApiClient.class);
		agreementApiClient = mock(AgreementApiClient.class);
		quoteApiClient = mock(QuoteApiClient.class);
		productSpecificationApiClient = mock(ProductSpecificationApiClient.class);
		productOfferingApiClient = mock(ProductOfferingApiClient.class);
		consentProperties = new ConsentProperties();
		tmForumAdapter = new TMForumAdapter(new ObjectMapper(), organizationResolver, productOrderApiClient, productOfferingApiClient, productSpecificationApiClient, agreementApiClient, quoteApiClient, consentProperties);
	}

	/** An order that references no agreement yet - nothing to reuse. */
	private void givenOrderWithoutAgreement(String productOrderId) {
		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO().id(productOrderId))));
	}

	@Test
	public void test_createAgreement_success() {
		String productId = "test-product";
		String offeringId = "test-offering";
		String agreementId = "test-agreement";

		givenOrderWithoutAgreement(productId);
		AgreementTmfVO expectedAgreement = new AgreementTmfVO()
				.id("agreement-id");
		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.ok().body(expectedAgreement)));

		assertEquals("agreement-id", tmForumAdapter.createAgreement(productId, offeringId, agreementId, List.of(), CONSUMER_ORG).block(), "The agreement should have been created.");
	}

	private static final String PROVIDER_ORG = "urn:org:provider";
	private static final String CONSUMER_ORG = "urn:org:consumer";
	private static final String FACADE_BASE = "https://consent-facade.example.org";

	/** An offering whose specification names a provider and declares an ODRL policy. */
	private void givenOfferingWithSpecification(Object odrlPolicy) {
		when(productOfferingApiClient.retrieveProductOffering(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOfferingVO()
						.id("test-offering")
						.productSpecification(new ProductSpecificationRefVO().id("test-spec")))));

		ProductSpecificationVO specification = new ProductSpecificationVO()
				.id("test-spec")
				.relatedParty(List.of(new org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO()
						.id(PROVIDER_ORG)
						.role("provider")));
		if (odrlPolicy != null) {
			specification.productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
					.valueType("authorizationPolicy")
					.productSpecCharacteristicValue(List.of(
							new CharacteristicValueSpecificationVO().value(odrlPolicy)))));
		}
		when(productSpecificationApiClient.retrieveProductSpecification(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(specification)));
		when(organizationResolver.hasProviderRole("provider")).thenReturn(true);
	}

	private List<RelatedPartyTmfVO> orderParties() {
		return List.of(new RelatedPartyTmfVO().id(CONSUMER_ORG), new RelatedPartyTmfVO().id(PROVIDER_ORG));
	}

	private AgreementCreateTmfVO captureCreatedAgreement() {
		ArgumentCaptor<AgreementCreateTmfVO> captor = ArgumentCaptor.forClass(AgreementCreateTmfVO.class);
		verify(agreementApiClient).createAgreement(captor.capture());
		return captor.getValue();
	}

	private static Optional<Object> characteristic(AgreementCreateTmfVO agreement, String name) {
		return agreement.getCharacteristic().stream()
				.filter(c -> name.equals(c.getName()))
				.map(CharacteristicTmfVO::getValue)
				.findFirst();
	}

	@Test
	public void test_addAgreementToOrder_keepsTheAgreementsAlreadyOnTheOrder() {
		// the patch replaces the list, so an existing ref must be carried over - otherwise an
		// agreement written by anyone else is lost, and so is the evidence that prevents duplicates
		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO()
						.id("test-order")
						.agreement(List.of(new AgreementRefVO().id("pre-existing"))))));
		when(productOrderApiClient.patchProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO().id("test-order"))));

		tmForumAdapter.addAgreementToOrder("test-order", List.of("newly-created")).block();

		ArgumentCaptor<ProductOrderUpdateVO> captor = ArgumentCaptor.forClass(ProductOrderUpdateVO.class);
		verify(productOrderApiClient).patchProductOrder(any(), captor.capture());
		List<String> patchedIds = captor.getValue().getAgreement().stream().map(AgreementRefVO::getId).toList();
		assertEquals(List.of("pre-existing", "newly-created"), patchedIds);
	}

	@Test
	public void test_addAgreementToOrder_doesNotDuplicateAnId() {
		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO()
						.id("test-order")
						.agreement(List.of(new AgreementRefVO().id("same-agreement"))))));
		when(productOrderApiClient.patchProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO().id("test-order"))));

		tmForumAdapter.addAgreementToOrder("test-order", List.of("same-agreement")).block();

		ArgumentCaptor<ProductOrderUpdateVO> captor = ArgumentCaptor.forClass(ProductOrderUpdateVO.class);
		verify(productOrderApiClient).patchProductOrder(any(), captor.capture());
		assertEquals(1, captor.getValue().getAgreement().size(), "A repeated delivery must not double the ref.");
	}

	@Test
	public void test_terminateAgreement_dropsTheSigningDateAndCancels() {
		consentProperties.setEnabled(true);
		when(agreementApiClient.retrieveAgreement(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO()
						.id("agreement-1")
						.characteristic(List.of(
								new CharacteristicTmfVO().name(TMForumAdapter.SIGNING_DATE).value(1L),
								new CharacteristicTmfVO().name(TMForumAdapter.PROVIDER_ID).value("sd"))))));
		when(agreementApiClient.patchAgreement(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("agreement-1"))));

		assertTrue(tmForumAdapter.terminateAgreement("agreement-1").block());

		ArgumentCaptor<AgreementUpdateTmfVO> captor = ArgumentCaptor.forClass(AgreementUpdateTmfVO.class);
		verify(agreementApiClient).patchAgreement(any(), captor.capture());
		assertEquals("cancelled", captor.getValue().getStatus());
		List<String> names = captor.getValue().getCharacteristic().stream()
				.map(CharacteristicTmfVO::getName).toList();
		assertEquals(List.of(TMForumAdapter.PROVIDER_ID), names,
				"signing-date must be gone: its presence alone makes the contract read as signed.");
	}

	@Test
	public void test_terminateAgreement_isANoOpWithoutConsent() {
		assertTrue(tmForumAdapter.terminateAgreement("agreement-1").block());
		verify(agreementApiClient, never()).patchAgreement(any(), any());
	}

	@Test
	public void test_createAgreement_carriesNoConsentCharacteristicsByDefault() {
		givenOrderWithoutAgreement("test-order");
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		tmForumAdapter.createAgreement("test-order", "test-offering", "dsp-1", orderParties(), CONSUMER_ORG).block();

		AgreementCreateTmfVO created = captureCreatedAgreement();
		assertEquals(1, created.getCharacteristic().size(),
				"With consent disabled the agreement must carry only the data-space-protocol id.");
		assertEquals(Optional.empty(), characteristic(created, TMForumAdapter.PROVIDER_ID));
	}

	@Test
	public void test_createAgreement_carriesTheConsentCharacteristics() {
		consentProperties.setEnabled(true);
		consentProperties.setSelfDescriptionBaseUrl(FACADE_BASE);
		Map<String, Object> odrlPolicy = Map.of("@type", "Set", "uid", "urn:policy:profile");

		givenOrderWithoutAgreement("test-order");
		givenOfferingWithSpecification(odrlPolicy);
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		assertEquals("new-agreement",
				tmForumAdapter.createAgreement("test-order", "test-offering", "dsp-1", orderParties(), CONSUMER_ORG).block());

		AgreementCreateTmfVO created = captureCreatedAgreement();
		assertEquals(Optional.of(FACADE_BASE + "/participants/" + PROVIDER_ORG),
				characteristic(created, TMForumAdapter.PROVIDER_ID),
				"provider-id must be the participant's self-description URL, not the organization id.");
		assertEquals(Optional.of(FACADE_BASE + "/participants/" + CONSUMER_ORG),
				characteristic(created, TMForumAdapter.CONSUMER_ID));
		assertTrue(characteristic(created, TMForumAdapter.SIGNING_DATE).isPresent(),
				"Its presence is what marks the contract concluded for the consent-facade.");
		assertEquals(Optional.of(odrlPolicy), characteristic(created, TMForumAdapter.POLICY),
				"The ODRL declared on the specification is what the consent is scoped by.");

		// roles are deliberately NOT set on engagedParty: the TM Forum API does not persist
		// `role` there, so the characteristics above are the only reliable carrier.
	}

	@Test
	public void test_createAgreement_isUnenrichedWithoutABaseUrl() {
		// enabling without the facade's base url cannot produce usable identifiers
		consentProperties.setEnabled(true);
		givenOrderWithoutAgreement("test-order");
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		tmForumAdapter.createAgreement("test-order", "test-offering", "dsp-1", orderParties(), CONSUMER_ORG).block();

		assertEquals(1, captureCreatedAgreement().getCharacteristic().size());
	}

	@Test
	public void test_createAgreement_isStillCreatedWhenTheSpecificationCannotBeRead() {
		// a consent concern must not block the order
		consentProperties.setEnabled(true);
		consentProperties.setSelfDescriptionBaseUrl(FACADE_BASE);
		givenOrderWithoutAgreement("test-order");
		when(productOfferingApiClient.retrieveProductOffering(any(), any()))
				.thenReturn(Mono.error(new RuntimeException("catalog down")));
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		assertEquals("new-agreement",
				tmForumAdapter.createAgreement("test-order", "test-offering", "dsp-1", orderParties(), CONSUMER_ORG).block());
		assertNotNull(captureCreatedAgreement());
	}

	@Test
	public void test_createAgreement_isNotCreatedWhenTheOrderAlreadyReferencesOne() {
		String productOrderId = "test-order";

		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO()
						.id(productOrderId)
						.agreement(List.of(new AgreementRefVO().id("existing-agreement"))))));

		assertEquals("existing-agreement",
				tmForumAdapter.createAgreement(productOrderId, "test-offering", "test-agreement", List.of(), CONSUMER_ORG).block(),
				"The id of the agreement the order already references should be returned.");
		verify(agreementApiClient, never()).createAgreement(any());
	}

	@Test
	public void test_createAgreement_isCreatedWhenTheOrderReferencesNone() {
		String productOrderId = "test-order";

		// an empty ref list is as good as none
		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new ProductOrderVO()
						.id(productOrderId)
						.agreement(List.of()))));
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		assertEquals("new-agreement",
				tmForumAdapter.createAgreement(productOrderId, "test-offering", "test-agreement", List.of(), CONSUMER_ORG).block());
	}

	@Test
	public void test_createAgreement_isCreatedWhenTheOrderCannotBeRead() {
		// failing to read the order is not evidence that no agreement exists, but refusing to
		// create would strand the order entirely - so it falls through to creation.
		when(productOrderApiClient.retrieveProductOrder(any(), any()))
				.thenReturn(Mono.error(new RuntimeException("order api down")));
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(new AgreementTmfVO().id("new-agreement"))));

		assertEquals("new-agreement",
				tmForumAdapter.createAgreement("test-order", "test-offering", "test-agreement", List.of(), CONSUMER_ORG).block());
	}

	@Test
	public void test_createAgreement_clientFailure() {
		givenOrderWithoutAgreement("test-order");
		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.badRequest()));

		assertThrows(TMForumException.class,
				() -> tmForumAdapter.createAgreement("test-order", "test-offering", "test-agreement", List.of(), CONSUMER_ORG).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

	@Test
	public void test_createAgreement_invalidResponse() {
		givenOrderWithoutAgreement("test-order");
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok()));

		assertThrows(TMForumException.class,
				() -> tmForumAdapter.createAgreement("test-order", "test-offering", "test-agreement", List.of(), CONSUMER_ORG).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

	@Test
	public void test_addAgreementToOrder_success() {
		givenOrderWithoutAgreement("test-order");
		ProductOrderVO expectedProductOrder = new ProductOrderVO();
		when(productOrderApiClient.patchProductOrder(any(), any())).thenReturn(Mono.just(HttpResponse.ok().body(expectedProductOrder)));

		assertEquals(expectedProductOrder, tmForumAdapter.addAgreementToOrder("test-order", List.of("agreement-id")).block(), "The agreements should have been added.");
	}

	@Test
	public void test_addAgreementToOrder_clientFailure() {
		givenOrderWithoutAgreement("test-order");
		when(productOrderApiClient.patchProductOrder(any(), any())).thenReturn(Mono.just(HttpResponse.badRequest()));
		assertThrows(TMForumException.class,
				() -> tmForumAdapter.addAgreementToOrder("test-order", List.of("agreement-id")).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

}
