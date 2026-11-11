package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.TirLookupException;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks a publisher's trusted issuers registry (TIR API v4) whether it lists a participant, so a late removal event about
 * a re-admitted participant writes nothing.
 */
@Singleton
@Requires(condition = DataSpaceEventsProperties.ParticipantRemovedCondition.class)
public class PublisherRegistryLookup {

    static final String ISSUERS_PATH = "/v4/issuers/";
    // bounds a registry that trickles its answer, so the publisher's retry is not outlived by the lookup
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public PublisherRegistryLookup(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * True if the registry lists the DID, false on 404. Fails with {@link TirLookupException} on any other answer.
     */
    public Mono<Boolean> isListed(String registryUrl, String did) {
        return Mono.defer(() -> Mono.from(httpClient.exchange(HttpRequest.GET(issuerUrl(registryUrl, did)), String.class)))
                .timeout(LOOKUP_TIMEOUT)
                .map(response -> {
                    if (response.getStatus() == HttpStatus.OK) {
                        return true;
                    }
                    throw new TirLookupException(did, registryUrl, response.code(), null);
                })
                .onErrorResume(HttpClientResponseException.class,
                        e -> e.getStatus() == HttpStatus.NOT_FOUND
                                ? Mono.just(false)
                                : Mono.error(new TirLookupException(did, registryUrl, e.getStatus().getCode(), e)))
                .onErrorMap(e -> !(e instanceof TirLookupException), e -> new TirLookupException(did, registryUrl, null, e));
    }

    static String issuerUrl(String registryUrl, String did) {
        String base = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
        // URLEncoder encodes form values, where a space is '+'; a path segment needs %20
        return base + ISSUERS_PATH + URLEncoder.encode(did, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
