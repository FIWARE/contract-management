package org.fiware.iam.exception;

/**
 * An event could not be delivered to the sink of a subscription and is dropped; delivery is best-effort.
 */
public class EventDeliveryException extends ContractManagementException {

	public EventDeliveryException(String message, Throwable cause) {
		super(FailureReason.EVENT_DELIVERY_FAILED, message, cause);
	}
}
