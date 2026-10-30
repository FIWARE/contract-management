package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Write;
import io.micronaut.management.endpoint.refresh.RefreshEndpoint;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;

/**
 * Micronaut's /refresh endpoint, run under the {@link ConfigurationRefreshLock} so it cannot overlap a file watch refresh.
 */
@Endpoint(SerializedRefreshEndpoint.ID)
@Replaces(RefreshEndpoint.class)
public class SerializedRefreshEndpoint extends RefreshEndpoint {

    /**
     * Same id as the replaced endpoint, so its endpoints.refresh.* configuration and path still apply.
     */
    static final String ID = "refresh";

    private final ConfigurationRefreshLock refreshLock;

    public SerializedRefreshEndpoint(Environment environment, ApplicationEventPublisher<RefreshEvent> eventPublisher,
                                     ConfigurationRefreshLock refreshLock) {
        super(environment, eventPublisher);
        this.refreshLock = refreshLock;
    }

    @Override
    @Write
    public String[] refresh(@Nullable Boolean force) {
        return refreshLock.exclusively(() -> super.refresh(force));
    }
}
