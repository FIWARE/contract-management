package org.fiware.iam.exception;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.tmforum.productorder.model.ErrorVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CatchAllExceptionHandlerTest {

	private final CatchAllExceptionHandler handler = new CatchAllExceptionHandler();

	@Test
	public void aFailedDownstreamCallIsABadGatewayWithItsReason() {
		HttpResponse<ErrorVO> response = handler.handle(HttpRequest.POST("/listener/event", "{}"),
				new HttpClientResponseException("Not Found", HttpResponse.notFound("offering x does not exist")));

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus(), "A failing downstream service is not an internal error.");
		assertTrue(response.body().getMessage().contains("status=404 body=offering x does not exist"),
				"The reason of the downstream service has to be part of the answer.");
	}

	@Test
	public void theReasonCodeIsPartOfTheAnswer() {
		HttpResponse<ErrorVO> response = handler.handle(HttpRequest.POST("/listener/event", "{}"),
				new TMForumException(FailureReason.ORGANIZATION_DID_MISSING, "Organization o-1 has no DID."));

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
		assertTrue(response.body().getMessage().contains("[organization_did_missing] Organization o-1 has no DID."));
	}

	@Test
	public void aMessageWithPercentSignsDoesNotBreakTheAnswer() {
		HttpResponse<ErrorVO> response = handler.handle(HttpRequest.POST("/listener/event", "{}"),
				new IllegalArgumentException("100% invalid"));

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
		assertEquals("100% invalid", response.body().getMessage());
	}
}
