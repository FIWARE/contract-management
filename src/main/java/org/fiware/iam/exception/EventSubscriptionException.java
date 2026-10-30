package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

/**
 * A publisher did not accept the registration or removal of our subscription; the subscriber retries later.
 * {@code status} is the publisher's answer, null for transport errors and a failed token fetch.
 */
@Getter
public class EventSubscriptionException extends ContractManagementException {

	private final String publisherId;
	private final String subscriptionsUrl;
	@Nullable
	private final Integer status;

	public EventSubscriptionException(String publisherId, String subscriptionsUrl, String message, @Nullable Integer status,
									  @Nullable Throwable cause) {
		super(FailureReason.EVENT_SUBSCRIPTION_FAILED,
				"Publisher %s at %s: %s".formatted(publisherId, subscriptionsUrl, message), cause);
		this.publisherId = publisherId;
		this.subscriptionsUrl = subscriptionsUrl;
		this.status = status;
	}
}
