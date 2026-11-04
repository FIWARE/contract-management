package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

/**
 * Fails startup of a publisher whose management port is unset or the server port: the unauthenticated
 * {@link PublishEndpoint} would then be public and collide with the listener's {@code POST /events}.
 */
@Context
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class ManagementPortGuard {

    static final int DEFAULT_SERVER_PORT = 8080;

    public ManagementPortGuard(@Nullable @Value("${endpoints.all.port}") Integer managementPort,
                               @Value("${micronaut.server.port:" + DEFAULT_SERVER_PORT + "}") int serverPort) {
        if (managementPort == null || managementPort == serverPort) {
            throw new ConfigurationException(("The publisher needs endpoints.all.port (%s) set to a cluster-internal port other "
                    + "than micronaut.server.port (%s), or its unauthenticated POST /events would be public.")
                    .formatted(managementPort, serverPort));
        }
    }
}
