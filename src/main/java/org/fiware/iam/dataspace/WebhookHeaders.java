package org.fiware.iam.dataspace;

/**
 * Header names of the CloudEvents webhook validation handshake, shared by the receiving and the publishing side.
 */
public final class WebhookHeaders {

    public static final String REQUEST_ORIGIN = "WebHook-Request-Origin";
    public static final String ALLOWED_ORIGIN = "WebHook-Allowed-Origin";
    public static final String ALLOWED_RATE = "WebHook-Allowed-Rate";

    private WebhookHeaders() {
    }
}
