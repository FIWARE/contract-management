package org.fiware.iam.tmforum.handlers;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.tmforum.TMFMapper;
import org.fiware.iam.tmforum.TMForumAdapter;
import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.AgreementRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOfferingRefVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderItemVO;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.productorder.model.RelatedPartyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgreementProductOrderHandlerTest {

	private static final String ORDER_ID = "urn:ngsi-ld:product-order:the-order";
	private static final String OFFERING_ID = "urn:ngsi-ld:product-offering:the-offering";
	private static final String CONSUMER_ID = "urn:ngsi-ld:organization:the-consumer";
	private static final String AGREEMENT_ID = "urn:ngsi-ld:agreement:the-agreement";

	private TMForumAdapter tmForumAdapter;
	private AgreementProductOrderHandler handler;

	@BeforeEach
	public void prepare() {
		tmForumAdapter = mock(TMForumAdapter.class);
		TMFMapper tmfMapper = relatedPartyVO -> new RelatedPartyTmfVO().id(relatedPartyVO.getId())
				.role(relatedPartyVO.getRole());
		handler = new AgreementProductOrderHandler(tmForumAdapter, tmfMapper);
	}

	private ProductOrderVO orderWithOffering() {
		return new ProductOrderVO()
				.id(ORDER_ID)
				.relatedParty(List.of(new RelatedPartyVO().id(CONSUMER_ID).role("Customer")))
				.productOrderItem(List.of(new ProductOrderItemVO()
						.productOffering(new ProductOfferingRefVO().id(OFFERING_ID))));
	}

	@Test
	public void completedOrderCreatesTheAgreementAndLinksIt() {
		when(tmForumAdapter.createAgreement(any(), any(), any(), any(), any())).thenReturn(Mono.just(AGREEMENT_ID));
		when(tmForumAdapter.addAgreementToOrder(any(), any())).thenReturn(Mono.just(new ProductOrderVO().id(ORDER_ID)));

		HttpResponse<?> response = handler.handleProductOrderComplete(CONSUMER_ID, orderWithOffering()).block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "The order should have been handled.");
		verify(tmForumAdapter).addAgreementToOrder(ORDER_ID, List.of(AGREEMENT_ID));
	}

	@Test
	public void theAgreementIsCreatedWithoutADspAgreementId() {
		ArgumentCaptor<String> dspAgreementId = ArgumentCaptor.forClass(String.class);
		when(tmForumAdapter.createAgreement(any(), any(), dspAgreementId.capture(), any(), any()))
				.thenReturn(Mono.just(AGREEMENT_ID));
		when(tmForumAdapter.addAgreementToOrder(any(), any())).thenReturn(Mono.just(new ProductOrderVO()));

		handler.handleProductOrderComplete(CONSUMER_ID, orderWithOffering()).block();

		assertNull(dspAgreementId.getValue(), "The order is not concluded through a DSP negotiation.");
	}

	@Test
	public void theOrderingPartiesAreHandedToTheAgreement() {
		@SuppressWarnings("unchecked") ArgumentCaptor<List<RelatedPartyTmfVO>> parties = (ArgumentCaptor<List<RelatedPartyTmfVO>>) (ArgumentCaptor<?>) ArgumentCaptor
				.forClass(List.class);
		when(tmForumAdapter.createAgreement(any(), any(), any(), parties.capture(), any()))
				.thenReturn(Mono.just(AGREEMENT_ID));
		when(tmForumAdapter.addAgreementToOrder(any(), any())).thenReturn(Mono.just(new ProductOrderVO()));

		handler.handleProductOrderComplete(CONSUMER_ID, orderWithOffering()).block();

		assertEquals(List.of(CONSUMER_ID), parties.getValue().stream().map(RelatedPartyTmfVO::getId).toList(),
				"The related parties of the order should be engaged in the agreement.");
	}

	@Test
	public void anOrderWithoutOfferingCreatesNoAgreement() {
		HttpResponse<?> response = handler
				.handleProductOrderComplete(CONSUMER_ID, new ProductOrderVO().id(ORDER_ID).relatedParty(List.of()))
				.block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "Nothing to be done, but no failure either.");
		verify(tmForumAdapter, never()).createAgreement(any(), any(), any(), any(), any());
	}

	@Test
	public void aFailingCreationIsReportedAsServerError() {
		when(tmForumAdapter.createAgreement(any(), any(), any(), any(), any()))
				.thenReturn(Mono.error(new RuntimeException("The agreement api is down.")));

		HttpResponse<?> response = handler.handleProductOrderComplete(CONSUMER_ID, orderWithOffering()).block();

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus(),
				"A missing agreement should be visible to the caller.");
	}

	@Test
	public void aStoppedOrderTerminatesItsAgreements() {
		when(tmForumAdapter.terminateAgreement(any())).thenReturn(Mono.just(true));

		HttpResponse<?> response = handler.handleProductOrderStop(CONSUMER_ID,
				new ProductOrderVO().id(ORDER_ID).agreement(List.of(new AgreementRefVO().id(AGREEMENT_ID)))).block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "The stop should have been handled.");
		verify(tmForumAdapter).terminateAgreement(AGREEMENT_ID);
	}

	@Test
	public void aStoppedOrderWithoutAgreementsIsANoOp() {
		HttpResponse<?> response = handler
				.handleProductOrderStop(CONSUMER_ID, new ProductOrderVO().id(ORDER_ID)).block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "Nothing to terminate.");
		verify(tmForumAdapter, never()).terminateAgreement(any());
	}

	@Test
	public void negotiationDoesNotCreateAnAgreement() {
		HttpResponse<?> response = handler.handleProductOrderNegotiation(CONSUMER_ID, orderWithOffering()).block();

		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "A negotiated order is not concluded yet.");
		verify(tmForumAdapter, never()).createAgreement(any(), any(), any(), any(), any());
	}
}
