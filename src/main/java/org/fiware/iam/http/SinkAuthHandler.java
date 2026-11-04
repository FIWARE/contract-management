package org.fiware.iam.http;

/**
 * Authenticates calls to participant-chosen sinks. Separate from the general {@link AuthHandler}, because such a call
 * must not be allowed to move the OID4VP flow to another host.
 */
public interface SinkAuthHandler extends AuthHandler {

    /**
     * Qualifier of the OID4VP beans used for sinks; they never follow a redirect.
     */
    String SINK_QUALIFIER = "sink";
}
