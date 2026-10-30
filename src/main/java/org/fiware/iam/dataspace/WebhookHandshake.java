package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.WebhookHandshakeException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Publisher side of the CloudEvents webhook validation handshake: asks a sink whether it accepts events from this CM.
 * Its client never follows redirects (see {@link PublisherHttpClientConfiguration}).
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class WebhookHandshake {

    static final String ANY = "*";
    // bounds a sink that trickles its answer, which the per-read timeout of the client does not
    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final DataSpaceEventsProperties properties;

    public WebhookHandshake(@Client(configuration = PublisherHttpClientConfiguration.class) HttpClient httpClient, DataSpaceEventsProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Validates the sink; emits its allowed rate in requests per minute, empty if unlimited.
     * Fails with a {@link WebhookHandshakeException} unless the sink answers 2xx and allows the own origin.
     */
    public Mono<Optional<Integer>> validate(URI sink) {
        String origin = properties.getPublisher().getOrigin();
        if (origin == null || origin.isBlank()) {
            return Mono.error(new WebhookHandshakeException(sink, "no publisher origin is configured", null));
        }
        HttpRequest<?> request = HttpRequest.OPTIONS(sink.toString()).header(WebhookHeaders.REQUEST_ORIGIN, origin);
        return Mono.from(httpClient.exchange(request, Void.class))
                .timeout(HANDSHAKE_TIMEOUT)
                .switchIfEmpty(Mono.error(() -> new WebhookHandshakeException(sink, "no response", null)))
                .map(response -> allowedRate(sink, origin, response))
                .onErrorMap(e -> !(e instanceof WebhookHandshakeException), e -> new WebhookHandshakeException(sink,
                        e instanceof HttpClientResponseException hcre ? "answered " + hcre.getStatus().getCode() : "request failed", e));
    }

    private static Optional<Integer> allowedRate(URI sink, String origin, HttpResponse<?> response) {
        int status = response.code();
        if (status < 200 || status >= 300) {
            throw new WebhookHandshakeException(sink, "answered " + status, null);
        }
        String allowedOrigin = response.getHeaders().get(WebhookHeaders.ALLOWED_ORIGIN);
        if (allowedOrigin == null || !(ANY.equals(allowedOrigin.trim()) || origin.equalsIgnoreCase(allowedOrigin.trim()))) {
            throw new WebhookHandshakeException(sink, "did not allow origin %s, allowed: %s".formatted(origin, allowedOrigin), null);
        }
        return parseRate(sink, response.getHeaders().get(WebhookHeaders.ALLOWED_RATE));
    }

    private static Optional<Integer> parseRate(URI sink, String allowedRate) {
        if (allowedRate == null || ANY.equals(allowedRate.trim())) {
            return Optional.empty();
        }
        try {
            int rate = Integer.parseInt(allowedRate.trim());
            if (rate > 0) {
                return Optional.of(rate);
            }
        } catch (NumberFormatException e) {
            // reported below, like a non-positive rate
        }
        throw new WebhookHandshakeException(sink, "announced the invalid rate " + allowedRate, null);
    }
}
