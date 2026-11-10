package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup when the management port is unset or the server port while an unauthenticated data space management
 * endpoint is enabled: {@link PublishEndpoint} would collide with the listener's {@code POST /events}, and
 * {@link RemovedParticipantsEndpoint} would show the trusted issuers publicly.
 */
@Context
@Requires(condition = ManagementPortGuard.ExposedEndpointsCondition.class)
public class ManagementPortGuard {

    static final int DEFAULT_SERVER_PORT = 8080;
    static final String PUBLISH_ENDPOINT = "POST /" + PublishEndpoint.ID;
    static final String REPORT_ENDPOINT = "GET /" + RemovedParticipantsEndpoint.ID;

    public ManagementPortGuard(DataSpaceEventsProperties properties,
                               @Nullable @Value("${endpoints.all.port}") Integer managementPort,
                               @Value("${micronaut.server.port:" + DEFAULT_SERVER_PORT + "}") int serverPort) {
        if (managementPort == null || managementPort == serverPort) {
            throw new ConfigurationException(("The unauthenticated management endpoints %s need endpoints.all.port (%s) set "
                    + "to a cluster-internal port other than micronaut.server.port (%s), or they would be public.")
                    .formatted(exposedEndpoints(properties), managementPort, serverPort));
        }
    }

    static List<String> exposedEndpoints(DataSpaceEventsProperties properties) {
        List<String> endpoints = new ArrayList<>();
        if (properties.getPublisher().isEnabled()) {
            endpoints.add(PUBLISH_ENDPOINT);
        }
        if (properties.participantRemovedEnabled()) {
            endpoints.add(REPORT_ENDPOINT);
        }
        return endpoints;
    }

    /**
     * Enables the guard while the publisher's or the participant.removed handling's management endpoint is enabled.
     */
    public static class ExposedEndpointsCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return !exposedEndpoints(context.getBean(DataSpaceEventsProperties.class)).isEmpty();
        }
    }
}
