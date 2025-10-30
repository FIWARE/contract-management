package org.fiware.iam.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public interface AuthHandler {

    /**
     * Execute the given request with authentication. In case no auth is required, the request should be executed without any
     * other interaction.
     */
    Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request, Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor);
}
