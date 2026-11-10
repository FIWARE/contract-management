package org.fiware.iam.exception;

import lombok.Getter;
import org.fiware.iam.til.model.CredentialsVO;

import java.util.List;

/**
 * An adoption granted the membership but did not revoke the unattributed originals, so the issuer holds them twice.
 * Completing it needs the copied credentials; the cause holds the failing call.
 */
@Getter
public class AdoptionIncompleteException extends ContractManagementException {

	private final String publisherId;
	private final String did;
	private final List<CredentialsVO> copied;

	public AdoptionIncompleteException(String publisherId, String did, List<CredentialsVO> copied, Throwable cause) {
		super(FailureReason.ADOPTION_INCOMPLETE, "Participant %s was granted the membership of %s, but its unattributed originals were not revoked: %s"
				.formatted(did, publisherId, cause.getMessage()), cause);
		this.publisherId = publisherId;
		this.did = did;
		this.copied = List.copyOf(copied);
	}
}
