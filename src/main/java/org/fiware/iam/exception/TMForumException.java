package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of issues with the tmforum api
 */
public class TMForumException extends ContractManagementException {

	public TMForumException(String message) {
		super(message);
	}

	public TMForumException(String message, Throwable cause) {
		super(message, cause);
	}

	public TMForumException(FailureReason reason, String message) {
		super(reason, message, null);
	}

	public TMForumException(FailureReason reason, String message, Throwable cause) {
		super(reason, message, cause);
	}
}
