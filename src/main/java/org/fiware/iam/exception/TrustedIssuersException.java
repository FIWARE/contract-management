package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of issues with the trusted-issuers-list
 */
public class TrustedIssuersException extends ContractManagementException {

	public TrustedIssuersException(FailureReason reason, String message) {
		super(reason, message, null);
	}

	public TrustedIssuersException(FailureReason reason, String message, Throwable cause) {
		super(reason, message, cause);
	}
}
