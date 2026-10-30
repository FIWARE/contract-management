package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

/**
 * Fails startup of a subscriber that could never register: the publishers' handshake needs the listener, and every
 * registration needs the sink and the protocol client id. Those two and the publisher entries are refreshable, so each
 * reconcile checks them again (see {@link SubscriberSettingsValidator}).
 */
@Context
@Requires(condition = DataSpaceEventsProperties.SubscriberCondition.class)
public class SubscriberStartupGuard {

    static final String LISTENER_ENABLED_KEY = "dataspace.events.listener.enabled";

    public SubscriberStartupGuard(DataSpaceEventsProperties properties) {
        if (!properties.getListener().isEnabled()) {
            throw new ConfigurationException(("The subscriber needs %s=true, since the publishers' webhook handshake is "
                    + "answered by the listener.").formatted(LISTENER_ENABLED_KEY));
        }
        SubscriberSettingsValidator.problem(properties.getSubscriber()).ifPresent(problem -> {
            throw new ConfigurationException(problem);
        });
    }
}
