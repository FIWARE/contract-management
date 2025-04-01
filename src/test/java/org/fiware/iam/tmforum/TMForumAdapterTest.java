package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.AgreementTmfVO;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.fiware.iam.tmforum.quote.api.QuoteApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TMForumAdapterTest {

	private ProductOrderApiClient productOrderApiClient;
	private AgreementApiClient agreementApiClient;
	private QuoteApiClient quoteApiClient;

	private TMForumAdapter tmForumAdapter;

	@BeforeEach
	public void prepare() {
		productOrderApiClient = mock(ProductOrderApiClient.class);
		agreementApiClient = mock(AgreementApiClient.class);
		quoteApiClient = mock(QuoteApiClient.class);

		tmForumAdapter = new TMForumAdapter(new ObjectMapper(), productOrderApiClient, agreementApiClient, quoteApiClient);
	}

	@Test
	public void test_createAgreement_success() {
		String productId = "test-product";
		String offeringId = "test-offering";
		String agreementId = "test-agreement";

		AgreementTmfVO expectedAgreement = new AgreementTmfVO()
				.id("agreement-id");
		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.ok().body(expectedAgreement)));

		assertEquals("agreement-id", tmForumAdapter.createAgreement(productId, offeringId, agreementId, List.of()).block(), "The agreement should have been created.");
	}

	@Test
	public void test_createAgreement_clientFailure() {
		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.badRequest()));

		assertThrows(TMForumException.class,
				() -> tmForumAdapter.createAgreement("test-order", "test-offering", "test-agreement", List.of()).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

	@Test
	public void test_createAgreement_invalidResponse() {
		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok()));

		assertThrows(TMForumException.class,
				() -> tmForumAdapter.createAgreement("test-order", "test-offering", "test-agreement", List.of()).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

	@Test
	public void test_addAgreementToOrder_success() {
		ProductOrderVO expectedProductOrder = new ProductOrderVO();
		when(productOrderApiClient.patchProductOrder(any(), any())).thenReturn(Mono.just(HttpResponse.ok().body(expectedProductOrder)));

		assertEquals(expectedProductOrder, tmForumAdapter.addAgreementToOrder("test-order", List.of("agreement-id")).block(), "The agreements should have been added.");
	}

	@Test
	public void test_addAgreementToOrder_clientFailure() {
		when(productOrderApiClient.patchProductOrder(any(), any())).thenReturn(Mono.just(HttpResponse.badRequest()));
		assertThrows(TMForumException.class,
				() -> tmForumAdapter.addAgreementToOrder("test-order", List.of("agreement-id")).block(),
				"For downstream errors, a TMForum Exception should have been created.");
	}

}
