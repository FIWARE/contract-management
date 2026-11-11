package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.ParticipantRemovedSettings;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;
import org.fiware.iam.exception.TirEnumerationException;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.tir.api.TirApiClient;
import org.fiware.iam.tir.model.IssuerEntryVO;
import org.fiware.iam.tir.model.IssuersResponseVO;
import org.fiware.iam.tir.model.LinksVO;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enumerates all DIDs of a trusted issuers registry, or throws {@link TirEnumerationException} if the enumeration is
 * not provably complete. Stateless; the paging cursor is only ever taken from the query of {@code links.next}.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.RegistryReaderCondition.class)
@RequiredArgsConstructor
public class TrustedIssuersRegistryReader {

    static final String PAGE_SIZE_PARAMETER = "page[size]";
    static final String PAGE_AFTER_PARAMETER = "page[after]";
    static final String ISSUERS_PATH = "/v4/issuers";
    // bounds a registry that trickles a page, so one publisher cannot stall the sweep of the others
    static final Duration PAGE_TIMEOUT = Duration.ofSeconds(30);

    private static final int FIRST_PAGE = 1;
    private static final String TRUST_ANCHOR_REGISTRY = "the trusted issuers registry";

    private final TirApiClient tirApiClient;
    private final HttpClient httpClient;
    private final DataSpaceEventsProperties properties;

    /**
     * One page of a registry listing; a cursor of null asks for the first page.
     */
    @FunctionalInterface
    interface PageSource {
        Mono<HttpResponse<IssuersResponseVO>> page(int pageSize, @Nullable Integer cursor);
    }

    /**
     * The trust anchor's own registry (the configured client), paged as tir-watcher configures. Blocks until all pages
     * are read. An empty registry yields an empty set.
     */
    public Set<String> enumerate() {
        TirWatcherSettings settings = properties.getTirWatcher();
        return enumerate(tirApiClient::getIssuersV4, TRUST_ANCHOR_REGISTRY, settings.effectivePageSize(),
                settings.effectiveMaxPages());
    }

    /**
     * A publisher's registry at {@code {registryUrl}/v4/issuers}, paged as participant-removed configures. Blocks like
     * {@link #enumerate()}.
     */
    public Set<String> enumerate(String registryUrl) {
        ParticipantRemovedSettings settings = properties.getParticipantRemoved();
        return enumerate((pageSize, cursor) -> page(registryUrl, pageSize, cursor),
                TRUST_ANCHOR_REGISTRY + " " + registryUrl, settings.effectivePageSize(), settings.effectiveMaxPages());
    }

    private Mono<HttpResponse<IssuersResponseVO>> page(String registryUrl, int pageSize, @Nullable Integer cursor) {
        String base = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
        UriBuilder uri = UriBuilder.of(base + ISSUERS_PATH).queryParam(PAGE_SIZE_PARAMETER, pageSize);
        if (cursor != null) {
            uri.queryParam(PAGE_AFTER_PARAMETER, cursor);
        }
        return Mono.from(httpClient.exchange(HttpRequest.GET(uri.build()), IssuersResponseVO.class)).timeout(PAGE_TIMEOUT);
    }

    Set<String> enumerate(PageSource source, String registry, int initialPageSize, int maxPages) {
        int pageSize = initialPageSize;
        Integer cursor = null;
        Set<Integer> requestedCursors = new HashSet<>();
        Set<String> dids = new HashSet<>();
        int total = 0;

        int page = FIRST_PAGE;
        for (; ; page++) {
            IssuersResponseVO response = fetch(source, registry, page, pageSize, cursor);
            List<IssuerEntryVO> items = response.getItems();
            if (items == null) {
                throw incomplete(registry, page, "answered without items");
            }
            if (page == FIRST_PAGE) {
                if (items.isEmpty()) {
                    // an empty page carries no meaningful links, so only a total of 0 (or none) makes it an empty registry
                    if (response.getTotal() == null || response.getTotal() == 0) {
                        return Set.of();
                    }
                    throw incomplete(registry, page, "answered no items but a total of " + response.getTotal());
                }
                if (response.getTotal() == null) {
                    throw incomplete(registry, page, "answered without a total");
                }
                total = response.getTotal();
            } else if (items.isEmpty()) {
                // end of list; the total of an empty page means nothing
                break;
            } else if (response.getTotal() == null || response.getTotal() != total) {
                throw incomplete(registry, page, "answered a total of %s instead of %s".formatted(response.getTotal(), total));
            }

            for (IssuerEntryVO item : items) {
                if (item == null || item.getDid() == null || item.getDid().isBlank()) {
                    throw incomplete(registry, page, "answered an entry without a DID");
                }
                dids.add(item.getDid());
            }
            if (dids.size() >= total) {
                break;
            }
            URI next = Optional.ofNullable(response.getLinks()).map(LinksVO::getNext).orElse(null);
            if (next == null) {
                break;
            }
            if (page >= maxPages) {
                throw incomplete(registry, page, "needs more than the %s pages allowed".formatted(maxPages));
            }

            Map<String, String> query = queryOf(registry, page, next);
            cursor = parseInteger(query.get(PAGE_AFTER_PARAMETER)).orElse(null);
            if (cursor == null) {
                throw incomplete(registry, page, "linked a next page without an integer " + PAGE_AFTER_PARAMETER);
            }
            if (!requestedCursors.add(cursor)) {
                throw incomplete(registry, page, "linked the already requested %s=%s".formatted(PAGE_AFTER_PARAMETER, query.get(PAGE_AFTER_PARAMETER)));
            }
            // an offset cursor is only valid with the size it was computed for, so the linked size is sent back
            pageSize = parseInteger(query.get(PAGE_SIZE_PARAMETER))
                    .map(size -> Math.clamp(size, TirWatcherSettings.MIN_PAGE_SIZE, TirWatcherSettings.MAX_PAGE_SIZE))
                    .orElse(initialPageSize);
        }

        if (dids.size() != total) {
            // reported at the last page fetched, where the list ended short or repeated a DID
            throw incomplete(registry, page, "ended the list with %s distinct DIDs instead of the total of %s".formatted(dids.size(), total));
        }
        log.debug("Enumerated {} DIDs of {}.", dids.size(), registry);
        return Set.copyOf(dids);
    }

    private static IssuersResponseVO fetch(PageSource source, String registry, int page, int pageSize,
                                           @Nullable Integer cursor) {
        HttpResponse<IssuersResponseVO> response;
        try {
            response = source.page(pageSize, cursor).block();
        } catch (HttpClientResponseException e) {
            throw new TirEnumerationException(message(registry, page, "could not be read"), page, e.getStatus().getCode(), e);
        } catch (RuntimeException e) {
            throw new TirEnumerationException(message(registry, page, "could not be read"), page, null, e);
        }
        if (response == null) {
            throw incomplete(registry, page, "answered nothing");
        }
        if (!HttpResponses.isSuccess(response)) {
            throw new TirEnumerationException(message(registry, page, "answered " + response.getStatus().getCode()), page,
                    response.getStatus().getCode(), null);
        }
        return response.getBody().orElseThrow(() -> incomplete(registry, page, "answered without a body"));
    }

    private static Map<String, String> queryOf(String registry, int page, URI link) {
        Map<String, String> parameters = new HashMap<>();
        String query = link.getRawQuery();
        if (query == null) {
            return parameters;
        }
        try {
            for (String parameter : query.split("&")) {
                int separator = parameter.indexOf('=');
                if (separator > 0) {
                    parameters.putIfAbsent(decode(parameter.substring(0, separator)), decode(parameter.substring(separator + 1)));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new TirEnumerationException(message(registry, page, "linked a next page with a malformed query"), page, null, e);
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Optional<Integer> parseInteger(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static TirEnumerationException incomplete(String registry, int page, String detail) {
        return new TirEnumerationException(message(registry, page, detail), page, null, null);
    }

    private static String message(String registry, int page, String detail) {
        return "Page %s of %s %s.".formatted(page, registry, detail);
    }
}
