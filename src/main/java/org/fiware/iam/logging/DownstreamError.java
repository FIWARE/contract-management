package org.fiware.iam.logging;

import io.micronaut.http.client.exceptions.HttpClientResponseException;

import reactor.core.Exceptions;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adds what a stacktrace does not show to a log line: the status and body a downstream service answered with. That
 * body usually holds the actual reason of a failure (e.g. a validation message of the PAP or the TIL), while the
 * trace of a {@link HttpClientResponseException} only carries the status.
 * <p>
 * Log the throwable itself next to it, so the standard stacktrace is printed as well.
 */
public final class DownstreamError {

	static final int MAX_BODY_LENGTH = 1000;
	private static final int MAX_CAUSE_DEPTH = 10;

	private DownstreamError() {
	}

	/**
	 * The message of the throwable, followed by status and body of the downstream answer it was caused by - if any.
	 * E.g. {@code [pap_rejected_policy] The PAP rejected policy x. - downstream answered with status=400 body={"detail":"..."}}
	 * <p>
	 * The reasons of a composite of several failures are joined with {@code "; "}.
	 */
	public static String reason(Throwable throwable) {
		if (Exceptions.isMultiple(throwable)) {
			// several failures collected by a zipDelayError - each of them is a reason
			return Exceptions.unwrapMultipleExcludingTracebacks(throwable).stream()
					.map(DownstreamError::reason)
					.collect(Collectors.joining("; "));
		}
		String message = Optional.ofNullable(throwable.getMessage()).orElseGet(() -> throwable.getClass().getSimpleName());
		return httpError(throwable)
				.map(httpError -> "%s - downstream answered with %s".formatted(message, httpError))
				.orElse(message);
	}

	/**
	 * Status and body of the first downstream http error in the causes of the throwable, empty if it was not caused by one.
	 */
	public static Optional<String> httpError(Throwable throwable) {
		Throwable current = throwable;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (current instanceof HttpClientResponseException hcre) {
				return Optional.of("status=%s body=%s".formatted(hcre.getStatus().getCode(), body(hcre)));
			}
			current = current.getCause();
		}
		return Optional.empty();
	}

	private static String body(HttpClientResponseException hcre) {
		String body;
		try {
			body = hcre.getResponse().getBody(String.class).orElse("");
		} catch (RuntimeException e) {
			body = "";
		}
		if (body.isBlank()) {
			return "<empty>";
		}
		body = body.replaceAll("\\s*\\R\\s*", " ");
		return body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : body;
	}
}
