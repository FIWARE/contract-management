package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

/**
 * The trusted issuers registry could not be enumerated completely, so no snapshot may be taken from it.
 * The page is 1-based; the status is null unless the registry answered with a non-2xx response.
 */
@Getter
public class TirEnumerationException extends ContractManagementException {

	private final int page;

	@Nullable
	private final Integer status;

	public TirEnumerationException(String message, int page, @Nullable Integer status, @Nullable Throwable cause) {
		super(FailureReason.TIR_ENUMERATION_FAILED, message, cause);
		this.page = page;
		this.status = status;
	}
}
