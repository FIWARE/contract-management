package org.fiware.iam.logging;

import io.micronaut.http.client.exceptions.HttpClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders a failure as one line that tells what went wrong - including the status and body a downstream service
 * answered with - so it can be logged without a stack trace.
 */
public final class DownstreamError {

	static final int MAX_BODY_LENGTH = 1000;
	private static final int MAX_CAUSE_DEPTH = 5;

	private DownstreamError() {
	}

	/**
	 * Describe the given throwable and its causes, e.g.
	 * {@code [pap_rejected_policy] PAP rejected policy x <- status=400 body={"detail":"..."}}
	 */
	public static String describe(Throwable throwable) {
		List<String> parts = new ArrayList<>();
		Throwable current = throwable;
		while (current != null && parts.size() < MAX_CAUSE_DEPTH) {
			String part = describeSingle(current);
			if (parts.isEmpty() || !parts.getLast().contains(part)) {
				parts.add(part);
			}
			if (current.getCause() == current) {
				break;
			}
			current = current.getCause();
		}
		return String.join(" <- ", parts);
	}

	/**
	 * Status and body of an http error, empty for all other throwables.
	 */
	public static Optional<String> describeHttpError(Throwable throwable) {
		if (throwable instanceof HttpClientResponseException hcre) {
			return Optional.of("status=%s body=%s".formatted(hcre.getStatus().getCode(), body(hcre)));
		}
		return Optional.empty();
	}

	private static String describeSingle(Throwable throwable) {
		return describeHttpError(throwable)
				.orElseGet(() -> Optional.ofNullable(throwable.getMessage())
						.map(message -> throwable instanceof RuntimeException && isOwnException(throwable)
								? message
								: "%s: %s".formatted(throwable.getClass().getSimpleName(), message))
						.orElse(throwable.getClass().getSimpleName()));
	}

	private static boolean isOwnException(Throwable throwable) {
		return throwable.getClass().getPackageName().startsWith("org.fiware.iam");
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
