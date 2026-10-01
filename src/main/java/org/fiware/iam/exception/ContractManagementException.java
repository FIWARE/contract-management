package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

/**
 * Base of all expected failures of the contract management. Its message carries a {@link FailureReason} code and the
 * ids involved; it is logged once, with its stack trace, at the place that handles it.
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
