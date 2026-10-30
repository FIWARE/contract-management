package org.fiware.iam.dataspace;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;

import java.util.Optional;

/**
 * Classifies the answers of the downstream services the data space eventing calls (sinks and publishers).
 */
final class DownstreamStatus {

    private DownstreamStatus() {
    }

    /**
     * The status a failed call was answered with; empty for transport errors, a failed token fetch and anything else.
     */
    static Optional<Integer> statusOf(Throwable e) {
        return e instanceof HttpClientResponseException hcre ? Optional.of(hcre.getResponse().code()) : Optional.empty();
    }

    // a 3xx that reaches us (not followed by the caller's client) is a failure as well
    static boolean isSuccess(int status) {
        return status >= HttpStatus.OK.getCode() && status < HttpStatus.MULTIPLE_CHOICES.getCode();
    }
}
