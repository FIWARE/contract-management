package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest(packages = {"org.fiware.iam.til"})
class TrustedIssuersListAdapterTest {

	private IssuerApiClient apiClient = mock(IssuerApiClient.class);

	@MockBean(IssuerApiClient.class)
	public IssuerApiClient apiClient() {
		return apiClient;
	}

	@Inject
	private TrustedIssuersListAdapter classUnderTest;

	@Test
	void allowIssuer_create() {
		when(apiClient.getIssuer(anyString())).thenReturn(Mono.just(HttpResponse.notFound()));
		when(apiClient.createTrustedIssuer(any())).thenReturn(Mono.just(HttpResponse.ok()));
		classUnderTest.allowIssuer("testDID").block();
		verify(apiClient).createTrustedIssuer(any());
	}

	@Test
	void allowIssuer_create_fault() {
		when(apiClient.getIssuer(anyString())).thenReturn(Mono.just(HttpResponse.notFound()));
		when(apiClient.createTrustedIssuer(any())).thenReturn(Mono.just(HttpResponse.ok()));
		doThrow(new HttpClientException("test")).when(apiClient).createTrustedIssuer(any());
		Assertions.assertThrows(TrustedIssuersException.class, () -> classUnderTest.allowIssuer("testDID").block());
	}

	@Test
	void allowIssuer_update() {
		when(apiClient.getIssuer(anyString()))
				.thenReturn(Mono.just(HttpResponse.ok(new TrustedIssuerVO()
						.did("testDID")
						.addCredentialsItem(new CredentialsVO()
								.credentialsType("existingCredentialType")
								.addClaimsItem(new ClaimVO().name("target1").addAllowedValuesItem("Role1"))))));
		when(apiClient.updateIssuer(any(), any())).thenReturn(Mono.just(HttpResponse.ok()));
		classUnderTest.allowIssuer("testDID").block();
		verify(apiClient).updateIssuer(eq("testDID"), any());
	}

	@Test
	void allowIssuer_update_fault() {
		when(apiClient.getIssuer(anyString()))
				.thenReturn(Mono.just(HttpResponse.ok(new TrustedIssuerVO()
						.did("testDID")
						.addCredentialsItem(new CredentialsVO()
								.credentialsType("existingCredentialType")
								.addClaimsItem(new ClaimVO().name("target1").addAllowedValuesItem("Role1"))))));
		doThrow(new HttpClientException("test")).when(apiClient).updateIssuer(anyString(), any());
		Assertions.assertThrows(TrustedIssuersException.class, () -> classUnderTest.allowIssuer("testDID").block());
	}

}