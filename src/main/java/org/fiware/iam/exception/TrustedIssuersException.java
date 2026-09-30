package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of issues with the trusted-issuers-list
 */
public class TrustedIssuersException extends ContractManagementException {

	public TrustedIssuersException(String message) {
		super(message);
	}

	public TrustedIssuersException(String message, Throwable cause) {
		super(message, cause);
	}

	public TrustedIssuersException(FailureReason reason, String message) {
		super(reason, message, null);
	}

	public TrustedIssuersException(FailureReason reason, String message, Throwable cause) {
		super(reason, message, cause);
	}
}
