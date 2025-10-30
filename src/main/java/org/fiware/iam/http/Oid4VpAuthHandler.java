package org.fiware.iam.http;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.Oid4VpConfiguration;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
@Singleton
@RequiredArgsConstructor
public class Oid4VpAuthHandler implements AuthHandler {

    public static final String CLIENT_ID_ATTRIBUTE = "clientId";
    public static final String SCOPE_ATTRIBUTE = "scope";

    private final OID4VPClient oid4VPClient;

    private Set<String> getScope(HttpRequest request) {
        return request.getAttribute(SCOPE_ATTRIBUTE, Set.class)
                .map(set -> (Set<?>) set) // Optional<Set<?>>
                .orElse(Set.of())
                .stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }

    private String getClientId(HttpRequest request) {
        return request.getAttribute(CLIENT_ID_ATTRIBUTE)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse("");
    }

    @Override
    public Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request, Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor) {
        return executor.apply(request)
                .onErrorResume(t -> {
                    if (t instanceof HttpClientResponseException hcre) {
                        return Mono.just(hcre.getResponse());
                    } else {
                        throw new BadGatewayException("Was not able to call downstream service.", t);
                    }
                })
                .flatMap(response -> {
                    if (response.getStatus() == HttpStatus.UNAUTHORIZED) {
                        RequestParameters params = new RequestParameters(
                                URI.create(request.getUri().getScheme() + "://" + request.getUri().getHost() + ":" + request.getUri().getPort()),
                                request.getPath(),
                                getClientId(request),
                                getScope(request)
                        );
                        return Mono.fromFuture(oid4VPClient.getAccessToken(params))
                                .map(TokenResponse::getAccessToken)
                                .flatMap(token -> {
                                    request.bearerAuth(token);
                                    return executor.apply(request);
                                });
                    }
                    return Mono.just(response);
                });
    }
}
