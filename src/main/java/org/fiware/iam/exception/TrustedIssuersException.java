package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of issues with the trusted-issuers-list
 */
public class TrustedIssuersException extends RuntimeException {

	public TrustedIssuersException(String message) {
		super(message);
	}

	public TrustedIssuersException(String message, Throwable cause) {
		super(message, cause);
	}
}
