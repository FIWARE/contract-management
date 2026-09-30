package org.fiware.iam.tmforum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.tmforum.party.api.OrganizationApiClient;
import org.fiware.iam.tmforum.party.model.ExternalReferenceVO;
import org.fiware.iam.tmforum.party.model.OrganizationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OrganizationResolverTest {

	private static final String ORGANIZATION_ID = "urn:ngsi-ld:organization:the-consumer";
	private static final String DID = "did:web:the-consumer.org";

	private OrganizationApiClient apiClient;
	private OrganizationResolver organizationResolver;

	@BeforeEach
	public void prepare() {
		apiClient = mock(OrganizationApiClient.class);
		organizationResolver = new OrganizationResolver(new GeneralProperties(), new ObjectMapper(), apiClient);
	}

	@Test
	public void theDidIsTakenFromTheExternalReference() {
		when(apiClient.retrieveOrganization(any(), any())).thenReturn(Mono.just(HttpResponse.ok(new OrganizationVO()
				.externalReference(List.of(new ExternalReferenceVO().externalReferenceType("idm_id").name(DID))))));

		assertEquals(DID, organizationResolver.getDID(ORGANIZATION_ID).block());
	}

	@Test
	public void anUnexpectedStatusIsAFailureInsteadOfAnEmptyResult() {
		// an empty result used to be answered as success, without any policy or credential being granted
		when(apiClient.retrieveOrganization(any(), any())).thenReturn(Mono.just(HttpResponse.accepted()));

		TMForumException exception = assertThrows(TMForumException.class, () -> organizationResolver.getDID(ORGANIZATION_ID).block());
		assertEquals(FailureReason.ORGANIZATION_NOT_FOUND, exception.getReason());
		assertTrue(exception.getMessage().contains(ORGANIZATION_ID), "The organization has to be named.");
	}

	@Test
	public void anUnknownOrganizationIsNamed() {
		when(apiClient.retrieveOrganization(any(), any())).thenReturn(Mono.error(
				new HttpClientResponseException("Not Found", HttpResponse.notFound())));

		TMForumException exception = assertThrows(TMForumException.class, () -> organizationResolver.getDID(ORGANIZATION_ID).block());
		assertEquals(FailureReason.ORGANIZATION_NOT_FOUND, exception.getReason());
		assertTrue(exception.getMessage().contains(ORGANIZATION_ID));
	}

	@Test
	public void anOrganizationWithoutDidTellsWhereTheDidIsExpected() {
		when(apiClient.retrieveOrganization(any(), any())).thenReturn(Mono.just(HttpResponse.ok(new OrganizationVO())));

		TMForumException exception = assertThrows(TMForumException.class, () -> organizationResolver.getDID(ORGANIZATION_ID).block());
		assertEquals(FailureReason.ORGANIZATION_DID_MISSING, exception.getReason());
		assertTrue(exception.getMessage().contains("idm_id"), "The expected location of the DID has to be named.");
	}
}
