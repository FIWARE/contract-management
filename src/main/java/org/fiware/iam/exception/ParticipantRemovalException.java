package org.fiware.iam.exception;

import lombok.Getter;

/**
 * The removal of a participant announced by a publisher could not be applied; the cause holds the failing call.
 */
@Getter
public class ParticipantRemovalException extends ContractManagementException {

	private final String publisherId;
	private final String did;

	public ParticipantRemovalException(String publisherId, String did, Throwable cause) {
		super(FailureReason.PARTICIPANT_REMOVAL_FAILED, "The removal of participant %s by %s failed: %s".formatted(
				did, publisherId, cause.getMessage()), cause);
		this.publisherId = publisherId;
		this.did = did;
	}
}
