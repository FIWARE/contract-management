package org.fiware.iam.exception;

/**
 * The verifier's JWKS could not be fetched, so a bearer token can neither be accepted nor rejected right now.
 */
public class VerifierUnavailableException extends ContractManagementException {

	public VerifierUnavailableException(String message, Throwable cause) {
		super(FailureReason.VERIFIER_UNAVAILABLE, message, cause);
	}
}
