package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.DataSpaceEventsProperties.TirWatcherSettings;
import org.fiware.iam.exception.TirEnumerationException;
import org.fiware.iam.http.HttpResponses;
import org.fiware.iam.tir.api.TirApiClient;
import org.fiware.iam.tir.model.IssuerEntryVO;
import org.fiware.iam.tir.model.IssuersResponseVO;
import org.fiware.iam.tir.model.LinksVO;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enumerates all DIDs of the trusted issuers registry, or throws {@link TirEnumerationException} if the enumeration is
 * not provably complete. Stateless; the paging cursor is only ever taken from the query of {@code links.next}.
 */
@Slf4j
@Singleton
@Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)
@RequiredArgsConstructor
public class TrustedIssuersRegistryReader {

    static final String PAGE_SIZE_PARAMETER = "page[size]";
    static final String PAGE_AFTER_PARAMETER = "page[after]";

    private static final int FIRST_PAGE = 1;

    private final TirApiClient tirApiClient;
    private final DataSpaceEventsProperties properties;

    /**
     * Blocks until all pages are read. An empty registry yields an empty set.
     */
    public Set<String> enumerate() {
        TirWatcherSettings settings = properties.getTirWatcher();
        int maxPages = settings.effectiveMaxPages();
        int pageSize = settings.effectivePageSize();
        Integer cursor = null;
        Set<Integer> requestedCursors = new HashSet<>();
        Set<String> dids = new HashSet<>();
        int total = 0;

        for (int page = FIRST_PAGE; ; page++) {
            IssuersResponseVO response = fetch(page, pageSize, cursor);
            List<IssuerEntryVO> items = response.getItems();
            if (items == null) {
                throw incomplete(page, "answered without items");
            }
            if (page == FIRST_PAGE) {
                if (items.isEmpty()) {
                    // an empty page carries no meaningful links, so only a total of 0 (or none) makes it an empty registry
                    if (response.getTotal() == null || response.getTotal() == 0) {
                        return Set.of();
                    }
                    throw incomplete(page, "answered no items but a total of " + response.getTotal());
                }
                if (response.getTotal() == null) {
                    throw incomplete(page, "answered without a total");
                }
                total = response.getTotal();
            } else if (items.isEmpty()) {
                // end of list; the total of an empty page means nothing
                break;
            } else if (response.getTotal() == null || response.getTotal() != total) {
                throw incomplete(page, "answered a total of %s instead of %s".formatted(response.getTotal(), total));
            }

            for (IssuerEntryVO item : items) {
                if (item == null || item.getDid() == null || item.getDid().isBlank()) {
                    throw incomplete(page, "answered an entry without a DID");
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
                throw incomplete(page, "needs more than the %s pages allowed".formatted(maxPages));
            }

            Map<String, String> query = queryOf(page, next);
            cursor = parseInteger(query.get(PAGE_AFTER_PARAMETER)).orElse(null);
            if (cursor == null) {
                throw incomplete(page, "linked a next page without an integer " + PAGE_AFTER_PARAMETER);
            }
            if (!requestedCursors.add(cursor)) {
                throw incomplete(page, "linked the already requested %s=%s".formatted(PAGE_AFTER_PARAMETER, query.get(PAGE_AFTER_PARAMETER)));
            }
            // an offset cursor is only valid with the size it was computed for, so the linked size is sent back
            pageSize = parseInteger(query.get(PAGE_SIZE_PARAMETER))
                    .map(size -> Math.clamp(size, TirWatcherSettings.MIN_PAGE_SIZE, TirWatcherSettings.MAX_PAGE_SIZE))
                    .orElse(settings.effectivePageSize());
        }

        if (dids.size() != total) {
            throw new TirEnumerationException(
                    "The trusted issuers registry listed %s distinct DIDs but a total of %s.".formatted(dids.size(), total),
                    FIRST_PAGE, null, null);
        }
        log.debug("Enumerated {} DIDs of the trusted issuers registry.", dids.size());
        return Set.copyOf(dids);
    }

    private IssuersResponseVO fetch(int page, int pageSize, @Nullable Integer cursor) {
        HttpResponse<IssuersResponseVO> response;
        try {
            response = tirApiClient.getIssuersV4(pageSize, cursor).block();
        } catch (HttpClientResponseException e) {
            throw new TirEnumerationException(message(page, "could not be read"), page, e.getStatus().getCode(), e);
        } catch (RuntimeException e) {
            throw new TirEnumerationException(message(page, "could not be read"), page, null, e);
        }
        if (response == null) {
            throw incomplete(page, "answered nothing");
        }
        if (!HttpResponses.isSuccess(response)) {
            throw new TirEnumerationException(message(page, "answered " + response.getStatus().getCode()), page,
                    response.getStatus().getCode(), null);
        }
        return response.getBody().orElseThrow(() -> incomplete(page, "answered without a body"));
    }

    private static Map<String, String> queryOf(int page, URI link) {
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
            throw new TirEnumerationException(message(page, "linked a next page with a malformed query"), page, null, e);
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

    private static TirEnumerationException incomplete(int page, String detail) {
        return new TirEnumerationException(message(page, detail), page, null, null);
    }

    private static String message(int page, String detail) {
        return "Page %s of the trusted issuers registry %s.".formatted(page, detail);
    }
}
