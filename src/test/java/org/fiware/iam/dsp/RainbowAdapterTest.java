package org.fiware.iam.dsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.exception.RainbowException;
import org.fiware.rainbow.api.*;
import org.fiware.rainbow.model.AgreementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RainbowAdapterTest {

	private ObjectMapper objectMapper = new ObjectMapper();
	private AgreementApiClient agreementApiClient;
	private ContractApiClient contractApiClient;

	private RainbowAdapter rainbowAdapter;

	@BeforeEach
	public void prepare() {
		agreementApiClient = mock(AgreementApiClient.class);

		rainbowAdapter = new RainbowAdapter(agreementApiClient, contractApiClient, objectMapper);
	}

	@Test
	public void test_createAgreement_success() {

		AgreementVO expectedAgreement = new AgreementVO().agreementId("test-id");
		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.ok().body(expectedAgreement)));

		AgreementVO createdAgreement = rainbowAdapter.createAgreement("test-org", "test-offering").block();
		assertEquals(expectedAgreement, createdAgreement, "The agreement should have been created.");
	}

	@Test
	public void test_createAgreement_clientFailure() {

		when(agreementApiClient.createAgreement(any())).thenReturn(Mono.just(HttpResponse.badRequest()));

		assertThrows(RainbowException.class,
				() -> rainbowAdapter.createAgreement("test-org", "test-offering").block(),
				"For downstream errors, a Rainbow Exception should have been created.");
	}

	@Test
	public void test_createAgreement_invalidResponse() {

		when(agreementApiClient.createAgreement(any()))
				.thenReturn(Mono.just(HttpResponse.ok().body(Map.of("something", "else"))));

		assertThrows(RainbowException.class,
				() -> rainbowAdapter.createAgreement("test-org", "test-offering").block(),
				"For downstream errors, a Rainbow Exception should have been created.");
	}

	@Test
	public void test_deleteAgreement_success() {
		when(agreementApiClient.deleteAgreementById(any())).thenReturn(Mono.just(HttpResponse.status(HttpStatus.ACCEPTED)));

		assertTrue(rainbowAdapter.deleteAgreement("the-id").block(), "The agreement should have been deleted.");
	}

	@Test
	public void test_deleteAgreement_invalidResponse() {
		when(agreementApiClient.deleteAgreementById(any())).thenReturn(Mono.just(HttpResponse.badRequest()));

		assertFalse(rainbowAdapter.deleteAgreement("the-id").block(), "The agreement should not have been deleted.");
	}

}
