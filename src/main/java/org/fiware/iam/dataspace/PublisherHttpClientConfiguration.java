package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClientConfiguration;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.time.Duration;

/**
 * Client settings for calls to participant-chosen sinks: the global client settings, but never following a redirect,
 * so a sink cannot point CM at another (e.g. internal) host. Set in code, so no configuration file can turn it off.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class PublisherHttpClientConfiguration extends HttpClientConfiguration {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final ConnectionPoolConfiguration connectionPoolConfiguration;

    public PublisherHttpClientConfiguration(DefaultHttpClientConfiguration defaults) {
        super(defaults);
        this.connectionPoolConfiguration = defaults.getConnectionPoolConfiguration();
        setFollowRedirects(false);
        setConnectTimeout(CONNECT_TIMEOUT);
        setReadTimeout(READ_TIMEOUT);
    }

    @Override
    public ConnectionPoolConfiguration getConnectionPoolConfiguration() {
        return connectionPoolConfiguration;
    }
}
