package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.management.endpoint.annotation.Endpoint;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup when the management port is unset or the server port while an unauthenticated data space management
 * endpoint is exposed: {@link PublishEndpoint} would collide with the listener's {@code POST /events}, and
 * {@link RemovedParticipantsEndpoint} would show the trusted issuers publicly. An endpoint disabled through
 * {@code endpoints.<id>.enabled} (or {@code endpoints.all.enabled}) is not exposed and needs no separate port.
 */
@Context
@Requires(condition = ManagementPortGuard.ExposedEndpointsCondition.class)
public class ManagementPortGuard {

    static final int DEFAULT_SERVER_PORT = 8080;
    static final String PUBLISH_ENDPOINT = "POST /" + PublishEndpoint.ID;
    static final String REPORT_ENDPOINT = "GET /" + RemovedParticipantsEndpoint.ID;

    public ManagementPortGuard(DataSpaceEventsProperties properties,
                               Environment environment,
                               @Nullable @Value("${endpoints.all.port}") Integer managementPort,
                               @Value("${micronaut.server.port:" + DEFAULT_SERVER_PORT + "}") int serverPort) {
        if (managementPort == null || managementPort == serverPort) {
            throw new ConfigurationException(("The unauthenticated management endpoints %s need endpoints.all.port (%s) set "
                    + "to a cluster-internal port other than micronaut.server.port (%s), or they would be public.")
                    .formatted(exposedEndpoints(properties, environment), managementPort, serverPort));
        }
    }

    static List<String> exposedEndpoints(DataSpaceEventsProperties properties, PropertyResolver resolver) {
        List<String> endpoints = new ArrayList<>();
        if (properties.getPublisher().isEnabled() && endpointEnabled(resolver, PublishEndpoint.ID)) {
            endpoints.add(PUBLISH_ENDPOINT);
        }
        if (properties.participantRemovedEnabled() && endpointEnabled(resolver, RemovedParticipantsEndpoint.ID)) {
            endpoints.add(REPORT_ENDPOINT);
        }
        return endpoints;
    }

    // the rule of micronaut-management's EndpointEnabledCondition: the endpoint's own flag, then endpoints.all
    private static boolean endpointEnabled(PropertyResolver resolver, String id) {
        return resolver.getProperty(endpointProperty(id), Boolean.class)
                .or(() -> resolver.getProperty(endpointProperty(Endpoint.ALL), Boolean.class))
                .orElse(Endpoint.ENABLED);
    }

    private static String endpointProperty(String id) {
        return Endpoint.DEFAULT_PREFIX + "." + id + ".enabled";
    }

    /**
     * Enables the guard while the publisher's or the participant.removed handling's management endpoint is exposed.
     */
    public static class ExposedEndpointsCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return !exposedEndpoints(context.getBean(DataSpaceEventsProperties.class), context).isEmpty();
        }
    }
}
