package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

/**
 * Base of all expected failures of the contract management. Its message already explains the problem, so it is
 * logged as a single line without a stack trace.
 */
@Getter
public abstract class ContractManagementException extends RuntimeException {

	@Nullable
	private final FailureReason reason;

	protected ContractManagementException(String message) {
		this(null, message, null);
	}

	protected ContractManagementException(String message, Throwable cause) {
		this(null, message, cause);
	}

	protected ContractManagementException(@Nullable FailureReason reason, String message, @Nullable Throwable cause) {
		super(reason == null ? message : reason.format(message), cause);
		this.reason = reason;
	}
}
