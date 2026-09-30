package org.fiware.iam.logging;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.PapException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DownstreamErrorTest {

	@Test
	public void theStatusAndBodyOfADownstreamErrorAreDescribed() {
		HttpClientResponseException error = new HttpClientResponseException("Bad Request",
				HttpResponse.badRequest("{\"detail\":\"odrl:permission is invalid\"}"));

		assertEquals(Optional.of("status=400 body={\"detail\":\"odrl:permission is invalid\"}"), DownstreamError.httpError(error),
				"The status and body of the downstream answer are the actual reason of the failure.");
	}

	@Test
	public void theDownstreamAnswerIsFoundInTheCauses() {
		PapException error = new PapException(FailureReason.PAP_REJECTED_POLICY, "The PAP rejected policy p-1.",
				new HttpClientResponseException("Bad Request", HttpResponse.badRequest("invalid policy")));

		assertEquals("[pap_rejected_policy] The PAP rejected policy p-1. - downstream answered with status=400 body=invalid policy",
				DownstreamError.reason(error), "The code, the context and the downstream reason have to be in one line.");
	}

	@Test
	public void withoutDownstreamAnswerOnlyTheMessageIsUsed() {
		assertEquals("boom", DownstreamError.reason(new IllegalStateException("boom")));
		assertEquals(Optional.empty(), DownstreamError.httpError(new IllegalStateException("boom")));
	}

	@Test
	public void anEmptyBodyIsMarked() {
		HttpClientResponseException error = new HttpClientResponseException("Bad Gateway", HttpResponse.status(HttpStatus.BAD_GATEWAY));

		assertEquals(Optional.of("status=502 body=<empty>"), DownstreamError.httpError(error));
	}

	@Test
	public void longBodiesAreTruncated() {
		HttpClientResponseException error = new HttpClientResponseException("Bad Request", HttpResponse.badRequest("x".repeat(5000)));

		String description = DownstreamError.httpError(error).orElseThrow();
		assertTrue(description.endsWith("...(truncated)"), "A huge body must not flood the log.");
		assertTrue(description.length() < DownstreamError.MAX_BODY_LENGTH + 50);
	}
}
