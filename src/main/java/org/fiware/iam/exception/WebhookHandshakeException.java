package org.fiware.iam.exception;

import io.micronaut.core.annotation.Nullable;
import lombok.Getter;

import java.net.URI;

/**
 * The sink did not confirm the webhook handshake, so no subscription to it may be activated.
 */
@Getter
public class WebhookHandshakeException extends ContractManagementException {

	private final URI sink;

	public WebhookHandshakeException(URI sink, String message, @Nullable Throwable cause) {
		super(FailureReason.WEBHOOK_HANDSHAKE_FAILED, "Sink %s: %s".formatted(sink, message), cause);
		this.sink = sink;
	}
}
