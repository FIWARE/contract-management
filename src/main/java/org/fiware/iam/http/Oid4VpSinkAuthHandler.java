package org.fiware.iam.http;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.Oid4VpConfiguration;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * OID4VP authentication for participant-chosen sinks: the same flow as {@link Oid4VpAuthHandler}, but through the
 * non-redirecting client, so a sink's 401 cannot send the token fetch to another (e.g. internal) host.
 */
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Singleton
public class Oid4VpSinkAuthHandler implements SinkAuthHandler {

    private final Oid4VpAuthHandler delegate;

    public Oid4VpSinkAuthHandler(@Named(SINK_QUALIFIER) OID4VPClient oid4VPClient) {
        this.delegate = new Oid4VpAuthHandler(oid4VPClient);
    }

    @Override
    public Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request, Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor) {
        return delegate.executeWithAuth(request, executor);
    }
}
