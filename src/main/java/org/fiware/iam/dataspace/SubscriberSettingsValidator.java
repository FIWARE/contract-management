package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the refreshable subscriber settings every registration needs, shared by the startup guard and each reconcile.
 * Returns the problem naming the offending key, empty when the settings are usable.
 */
final class SubscriberSettingsValidator {

    static final String SINK_KEY = "dataspace.events.subscriber.sink";
    static final String PROTOCOL_CLIENT_ID_KEY = "dataspace.events.subscriber.protocol-settings.client-id";
    private static final Set<String> SINK_SCHEMES = Set.of("http", "https");

    private SubscriberSettingsValidator() {
    }

    static Optional<String> problem(DataSpaceEventsProperties.SubscriberSettings subscriber) {
        if (!isHttpUrl(subscriber.getSink())) {
            return Optional.of("The subscriber needs %s set to the absolute http(s) URL of the own POST /events, was '%s'."
                    .formatted(SINK_KEY, subscriber.getSink()));
        }
        String protocolClientId = subscriber.getProtocolSettings().getClientId();
        if (protocolClientId == null || protocolClientId.isBlank()) {
            return Optional.of(("The subscriber needs %s, the client at the own verifier the publishers use "
                    + "when the sink answers 401.").formatted(PROTOCOL_CLIENT_ID_KEY));
        }
        return Optional.empty();
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
