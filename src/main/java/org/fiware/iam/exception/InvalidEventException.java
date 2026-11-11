package org.fiware.iam.exception;

import lombok.Getter;

/**
 * A data space event lacks what its handler needs; a redelivery of the same event can never succeed.
 */
@Getter
public class InvalidEventException extends ContractManagementException {

	private final String eventId;
	private final String source;

	public InvalidEventException(String eventId, String source, String message) {
		super(FailureReason.INVALID_EVENT, "Event %s from %s: %s".formatted(eventId, source, message), null);
		this.eventId = eventId;
		this.source = source;
	}
}
