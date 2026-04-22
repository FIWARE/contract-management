package org.fiware.iam.http;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.inject.Singleton;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Filter("/**")
@RequiredArgsConstructor
public class AccessLogServerFilter implements HttpServerFilter {

    private final AccessLogConfiguration accessLogConfiguration;

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (accessLogConfiguration.getExcludePaths().stream().anyMatch(request.getUri().getPath()::startsWith)) {
            return chain.proceed(request);
        }

        long start = System.currentTimeMillis();

        return Flux.from(chain.proceed(request))
                .doOnNext(res -> {
                    long duration = System.currentTimeMillis() - start;
                    String remoteIp = request.getRemoteAddress().getAddress().getHostAddress();
                    String protocol = request.getHttpVersion().toString();
                    String method = request.getMethod().name();
                    String uri = request.getUri().toString();
                    int status = res.getStatus().getCode();
                    String forwardedFor = request.getHeaders().get("X-Forwarded-For");

                    if (forwardedFor != null) {
                        log.info("{} [{}] - {} - {} {} {} - {}ms", forwardedFor, remoteIp, protocol, method, uri, status, duration);
                    } else {
                        log.info("{} - {} - {} {} {} - {}ms", remoteIp, protocol, method, uri, status, duration);
                    }
                })
                .doOnError(e -> {
                    long duration = System.currentTimeMillis() - start;
                    String remoteIp = request.getRemoteAddress().getAddress().getHostAddress();
                    String forwardedFor = request.getHeaders().get("X-Forwarded-For");
                    String status = e instanceof HttpStatusException hse ? Integer.toString(hse.getStatus().getCode()) : "ERROR";

                    if (forwardedFor != null) {
                        log.error("{} [{}] - {} - {} {} {} - {}ms", forwardedFor, remoteIp, request.getHttpVersion(), request.getMethod(), request.getUri(), status, duration, e);
                    } else {
                        log.error("{} - {} - {} {} {} - {}ms", remoteIp, request.getHttpVersion(), request.getMethod(), request.getUri(), status, duration, e);
                    }
                });
    }

    @Data
    @Singleton
    @ConfigurationProperties("http.server.log")
    public static class AccessLogConfiguration {
        private List<String> excludePaths = List.of();
    }
}