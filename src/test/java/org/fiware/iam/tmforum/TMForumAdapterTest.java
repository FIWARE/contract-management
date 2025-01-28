package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.dsp.RainbowAdapter;
import org.fiware.iam.tmforum.agreement.api.AgreementApiClient;
import org.fiware.iam.tmforum.agreement.model.*;
import org.fiware.iam.tmforum.productorder.api.ProductOrderApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TMForumAdapterTest {

	private ProductOrderApiClient productOrderApiClient = mock(ProductOrderApiClient.class);
	private AgreementApiClient agreementApiClient = mock(AgreementApiClient.class);

	private TMForumAdapter tmForumAdapter;

	@BeforeEach
	public void prepare() {
		tmForumAdapter = new TMForumAdapter(productOrderApiClient, agreementApiClient);
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
}
