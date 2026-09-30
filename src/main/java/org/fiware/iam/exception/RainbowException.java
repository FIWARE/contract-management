package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of problems with rainbow
 */
public class RainbowException extends ContractManagementException {

	public RainbowException(String message) {
		super(message);
	}

	public RainbowException(String message, Throwable cause) {
		super(message, cause);
	}

	public RainbowException(FailureReason reason, String message) {
		super(reason, message, null);
	}

	public RainbowException(FailureReason reason, String message, Throwable cause) {
		super(reason, message, cause);
	}
}
