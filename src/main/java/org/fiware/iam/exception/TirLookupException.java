package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

/**
 * A publisher's trusted issuers registry could not tell whether it lists a participant. The status is null unless the
 * registry answered with an unexpected status.
 */
@Getter
public class TirLookupException extends ContractManagementException {

	private final String did;
	private final String registryUrl;

	@Nullable
	private final Integer status;

	public TirLookupException(String did, String registryUrl, @Nullable Integer status, @Nullable Throwable cause) {
		super(FailureReason.TIR_LOOKUP_FAILED, "The registry %s could not be asked whether it lists %s%s.".formatted(
				registryUrl, did, status == null ? "" : " (status " + status + ")"), cause);
		this.did = did;
		this.registryUrl = registryUrl;
		this.status = status;
	}
}
