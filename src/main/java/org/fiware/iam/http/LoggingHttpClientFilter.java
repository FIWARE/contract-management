package org.fiware.iam.http;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Slf4j
@Filter("/**")
public class LoggingHttpClientFilter implements HttpClientFilter {

    @Value("${http.client.log-exception:false}")
    private boolean logException;

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        long start = System.currentTimeMillis();

        return Flux.from(chain.proceed(request))
                .doOnNext(res -> log.info(
                        "{} {} {} - {} ms",
                        request.getMethod(),
                        request.getUri(),
                        res.getStatus().getCode(),
                        System.currentTimeMillis() - start))
                .doOnError(e -> {
                    Throwable cause = logException ? e : null;
                    log.warn("{} {} ERROR: {} - {} ms", request.getMethod(), request.getUri(), e.getMessage(), System.currentTimeMillis() - start, cause);
                });
    }
}