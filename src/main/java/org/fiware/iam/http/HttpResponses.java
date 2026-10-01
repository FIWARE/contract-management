package org.fiware.iam.http;

import io.micronaut.http.HttpResponse;

/**
 * Helpers for evaluating http responses.
 */
public final class HttpResponses {

	private HttpResponses() {
	}

	/**
	 * Whether the response has a 2xx status.
	 */
	public static boolean isSuccess(HttpResponse<?> response) {
		int code = response.getStatus().getCode();
		return code >= 200 && code < 300;
	}
}
