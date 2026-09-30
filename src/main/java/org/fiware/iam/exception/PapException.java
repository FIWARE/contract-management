package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of problems with the odrl-pap
 */
public class PapException extends ContractManagementException {

	public PapException(String message) {
		super(message);
	}

	public PapException(String message, Throwable cause) {
		super(message, cause);
	}

	public PapException(FailureReason reason, String message) {
		super(reason, message, null);
	}

	public PapException(FailureReason reason, String message, Throwable cause) {
		super(reason, message, cause);
	}
}
