package org.fiware.iam.dataspace;

import io.micronaut.http.HttpStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Outcome of authorizing a received data space event, with the status to answer and the metric outcome tag to count.
 */
@Getter
@RequiredArgsConstructor
public enum EventDecision {

    ACCEPT(HttpStatus.ACCEPTED, "accepted"),
    UNKNOWN_TYPE(HttpStatus.ACCEPTED, "unknown_type"),
    SELF(HttpStatus.ACCEPTED, "self"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated"),
    SOURCE_MISMATCH(HttpStatus.FORBIDDEN, "source_mismatch"),
    PUBLISHER_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "publisher_not_authorized"),
    DATASPACE_MISMATCH(HttpStatus.FORBIDDEN, "dataspace_mismatch");

    private final HttpStatus status;
    private final String outcome;

    /**
     * True if the event is refused, i.e. answered with an error status.
     */
    public boolean isRejected() {
        return status.getCode() >= HttpStatus.BAD_REQUEST.getCode();
    }
}
