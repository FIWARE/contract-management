package org.fiware.iam.pap;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.fiware.iam.tmforum.OrganizationResolver;
import org.fiware.iam.tmforum.PolicyResolver;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PapProductOrderHandlerTest {

	private static final String ORDER_ID = "urn:ngsi-ld:product-order:the-order";
	private static final String CONSUMER_ID = "urn:ngsi-ld:organization:the-consumer";
	private static final String CONSUMER_DID = "did:web:the-consumer.org";

	private PolicyResolver policyResolver;
	private PAPAdapter papAdapter;
	private PapProductOrderHandler handler;

	@BeforeEach
	public void prepare() {
		policyResolver = mock(PolicyResolver.class);
		papAdapter = mock(PAPAdapter.class);
		OrganizationResolver organizationResolver = mock(OrganizationResolver.class);
		when(organizationResolver.getDID(any())).thenReturn(Mono.just(CONSUMER_DID));
		handler = new PapProductOrderHandler(policyResolver, organizationResolver, papAdapter);
	}

	@Test
	public void anOrderWithoutPolicyIsAnsweredInsteadOfLeftEmpty() {
		// completing empty would render as 404 and the TM Forum API would redeliver the notification
		// indefinitely, re-running every order handler on each delivery
		when(policyResolver.getAuthorizationPolicy(any())).thenReturn(Mono.just(List.of()));

		HttpResponse<?> response = handler
				.handleProductOrderComplete(CONSUMER_ID, new ProductOrderVO().id(ORDER_ID)).block();

		assertNotNull(response, "An order without a policy must still be answered.");
		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "Nothing to publish is not a failure.");
		verify(papAdapter, never()).createPolicy(any(), any(), any());
	}

	@Test
	public void aStoppedOrderWithoutPolicyIsAnsweredInsteadOfLeftEmpty() {
		when(policyResolver.getAuthorizationPolicy(any())).thenReturn(Mono.just(List.of()));

		HttpResponse<?> response = handler
				.handleProductOrderStop(CONSUMER_ID, new ProductOrderVO().id(ORDER_ID)).block();

		assertNotNull(response, "A stopped order without a policy must still be answered.");
		assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "Nothing to delete is not a failure.");
		verify(papAdapter, never()).deletePolicy(any(), any());
	}
}
