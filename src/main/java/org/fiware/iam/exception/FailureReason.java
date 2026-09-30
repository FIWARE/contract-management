package org.fiware.iam.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Machine-readable reasons for a failure while handling an order. The code is part of the exception message
 * (and therefore of the log line and the error response), so a failure can be searched for and matched exactly.
 */
@Getter
@RequiredArgsConstructor
public enum FailureReason {

	ORGANIZATION_NOT_FOUND("organization_not_found"),
	ORGANIZATION_DID_MISSING("organization_did_missing"),
	OFFERING_NOT_RESOLVABLE("offering_not_resolvable"),
	SPECIFICATION_NOT_RESOLVABLE("specification_not_resolvable"),
	QUOTE_NOT_RESOLVABLE("quote_not_resolvable"),
	PROVIDER_NOT_RESOLVABLE("provider_not_resolvable"),
	CONFLICTING_POLICIES("conflicting_policies"),
	CONFLICTING_PROVIDERS("conflicting_providers"),
	POLICY_MISSING_UID("policy_missing_uid"),
	POLICY_MISSING_PERMISSION("policy_missing_permission"),
	POLICY_INVALID_ASSIGNEE("policy_invalid_assignee"),
	PAP_REJECTED_POLICY("pap_rejected_policy"),
	TIL_REJECTED_ISSUER("til_rejected_issuer"),
	REMOTE_CM_REJECTED("remote_cm_rejected"),
	AGREEMENT_FAILED("agreement_failed"),
	RAINBOW_ERROR("rainbow_error"),
	TMFORUM_ERROR("tmforum_error");

	private final String code;

	/**
	 * Prefix the message with the code, the way it is written to logs and responses.
	 */
	public String format(String message) {
		return "[%s] %s".formatted(code, message);
	}
}
