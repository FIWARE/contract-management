package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClientConfiguration;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

/**
 * Client settings for calls to participant-chosen sinks: the global client settings, but never following a redirect,
 * so a sink cannot point CM at another (e.g. internal) host. Set in code, so no configuration file can turn it off.
 * Timeouts come from {@code dataspace.events.publisher.sink-*-timeout}, read once when the client is created.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class PublisherHttpClientConfiguration extends HttpClientConfiguration {

    private final ConnectionPoolConfiguration connectionPoolConfiguration;

    public PublisherHttpClientConfiguration(DefaultHttpClientConfiguration defaults, DataSpaceEventsProperties properties) {
        super(defaults);
        this.connectionPoolConfiguration = defaults.getConnectionPoolConfiguration();
        setFollowRedirects(false);
        setConnectTimeout(properties.getPublisher().getSinkConnectTimeout());
        setReadTimeout(properties.getPublisher().getSinkReadTimeout());
    }

    @Override
    public ConnectionPoolConfiguration getConnectionPoolConfiguration() {
        return connectionPoolConfiguration;
    }
}
