package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Fails startup of a subscriber that could never register: the publishers' handshake needs the listener, and every
 * registration needs the sink and the protocol client id. Publisher entries are refreshable and checked per reconcile instead.
 */
@Context
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
public class SubscriberStartupGuard {

    static final String LISTENER_ENABLED_KEY = "dataspace.events.listener.enabled";
    static final String SINK_KEY = "dataspace.events.subscriber.sink";
    static final String PROTOCOL_CLIENT_ID_KEY = "dataspace.events.subscriber.protocol-settings.client-id";
    private static final Set<String> SINK_SCHEMES = Set.of("http", "https");

    public SubscriberStartupGuard(DataSpaceEventsProperties properties) {
        if (!properties.getListener().isEnabled()) {
            throw new ConfigurationException(("The subscriber needs %s=true, since the publishers' webhook handshake is "
                    + "answered by the listener.").formatted(LISTENER_ENABLED_KEY));
        }
        DataSpaceEventsProperties.SubscriberSettings subscriber = properties.getSubscriber();
        if (!isHttpUrl(subscriber.getSink())) {
            throw new ConfigurationException("The subscriber needs %s set to the absolute http(s) URL of the own POST /events, was '%s'."
                    .formatted(SINK_KEY, subscriber.getSink()));
        }
        String protocolClientId = subscriber.getProtocolSettings().getClientId();
        if (protocolClientId == null || protocolClientId.isBlank()) {
            throw new ConfigurationException(("The subscriber needs %s, the client at the own verifier the publishers use "
                    + "when the sink answers 401.").formatted(PROTOCOL_CLIENT_ID_KEY));
        }
    }

    private static boolean isHttpUrl(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && SINK_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT)) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
