package org.fiware.iam.http;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.logging.DownstreamError;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Logs every outgoing request. Successful calls are only of interest when tracing (DEBUG), failed calls are logged
 * with the status and body the downstream service answered, since that usually is the actual reason of a failure.
 */
@Slf4j
@Filter("/**")
public class LoggingHttpClientFilter implements HttpClientFilter {

    @Value("${http.client.log-exception:false}")
    private boolean logException;

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        long start = System.currentTimeMillis();

        return Flux.from(chain.proceed(request))
                .doOnNext(res -> log.debug(
                        "{} {} {} - {} ms",
                        request.getMethod(),
                        request.getUri(),
                        res.getStatus().getCode(),
                        System.currentTimeMillis() - start))
                .doOnError(e -> {
                    Throwable cause = logException ? e : null;
                    log.warn("Downstream call {} {} failed after {} ms: {}", request.getMethod(), request.getUri(),
                            System.currentTimeMillis() - start, DownstreamError.describe(e), cause);
                });
    }
}
