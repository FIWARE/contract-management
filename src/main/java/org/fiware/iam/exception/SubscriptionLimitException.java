package org.fiware.iam.exception;

/**
 * A subscription was refused because the per-subscriber or the total subscription cap is reached.
 */
public class SubscriptionLimitException extends ContractManagementException {

	public SubscriptionLimitException(String message) {
		super(FailureReason.SUBSCRIPTION_LIMIT_REACHED, message, null);
	}
}
