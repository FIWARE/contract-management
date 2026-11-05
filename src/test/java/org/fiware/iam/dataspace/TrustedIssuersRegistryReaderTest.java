package org.fiware.iam.dataspace;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.TirEnumerationException;
import org.fiware.iam.tir.api.TirApiClient;
import org.fiware.iam.tir.model.IssuerEntryVO;
import org.fiware.iam.tir.model.IssuersResponseVO;
import org.fiware.iam.tir.model.LinksVO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedIssuersRegistryReaderTest {

    // the reader must only use the query of links.next, never this host
    private static final String UNREACHABLE_REGISTRY = "http://tir.unreachable.invalid/v4/issuers";
    private static final String FIRST = "first";
    private static final int PAGE_SIZE = 2;
    private static final int DEFAULT_PAGE_SIZE = DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_PAGE_SIZE;
    private static final int CAPPED_PAGE_SIZE = 50;
    private static final int LINKED_PAGE_SIZE = 50;
    private static final int UNLIMITED_PAGES = DataSpaceEventsProperties.DEFAULT_TIR_WATCHER_MAX_PAGES;
    private static final int MAX_PAGES = 2;
    private static final int SECOND_PAGE = 2;

    private record Request(Integer size, @Nullable Integer after) {
    }

    /**
     * A registry answering by the requested cursor; an unknown cursor gets the empty past-the-end page of FIWARE TIL.
     */
    private record Registry(Map<String, Mono<HttpResponse<IssuersResponseVO>>> pages, List<Request> requests) {

        @SuppressWarnings("unchecked")
        static Registry of(Object... cursorsAndPages) {
            Map<String, Mono<HttpResponse<IssuersResponseVO>>> pages = new HashMap<>();
            for (int i = 0; i < cursorsAndPages.length; i += 2) {
                Object answer = cursorsAndPages[i + 1];
                pages.put(String.valueOf(cursorsAndPages[i]), answer instanceof IssuersResponseVO body
                        ? Mono.just(HttpResponse.ok(body))
                        : (Mono<HttpResponse<IssuersResponseVO>>) answer);
            }
            return new Registry(pages, new ArrayList<>());
        }

        TirApiClient client() {
            TirApiClient client = mock(TirApiClient.class);
            when(client.getIssuersV4(any(), any())).thenAnswer(invocation -> {
                Integer size = (Integer) invocation.getArguments()[0];
                Integer after = (Integer) invocation.getArguments()[1];
                requests.add(new Request(size, after));
                return pages.getOrDefault(after == null ? FIRST : String.valueOf(after),
                        Mono.just(HttpResponse.ok(pastTheEnd())));
            });
            return client;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("completeEnumerations")
    void enumeratesCompletely(String name, int pageSize, int maxPages, Registry registry, Set<String> expectedDids,
            List<Request> expectedRequests) {
        assertEquals(expectedDids, reader(registry, pageSize, maxPages).enumerate());
        assertEquals(expectedRequests, registry.requests());
    }

    static Stream<Arguments> completeEnumerations() {
        return Stream.of(
                Arguments.of("single page", DEFAULT_PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, page(2, null, "did:a", "did:b")),
                        Set.of("did:a", "did:b"), List.of(new Request(DEFAULT_PAGE_SIZE, null))),
                Arguments.of("multi page", PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, page(5, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(5, next(PAGE_SIZE, 2), "did:c", "did:d"),
                                2, page(5, null, "did:e")),
                        Set.of("did:a", "did:b", "did:c", "did:d", "did:e"),
                        List.of(new Request(PAGE_SIZE, null), new Request(PAGE_SIZE, 1), new Request(PAGE_SIZE, 2))),
                Arguments.of("empty registry without links", DEFAULT_PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, pastTheEnd()), Set.of(), List.of(new Request(DEFAULT_PAGE_SIZE, null))),
                Arguments.of("empty registry without total", DEFAULT_PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, new IssuersResponseVO().items(List.of()).links(null)), Set.of(),
                        List.of(new Request(DEFAULT_PAGE_SIZE, null))),
                Arguments.of("exact multiple of the page size stops at the total", PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(4, next(PAGE_SIZE, 2), "did:c", "did:d")),
                        Set.of("did:a", "did:b", "did:c", "did:d"),
                        List.of(new Request(PAGE_SIZE, null), new Request(PAGE_SIZE, 1))),
                Arguments.of("1-based cursor", PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, page(3, next(PAGE_SIZE, 2), "did:a", "did:b"),
                                2, page(3, null, "did:c")),
                        Set.of("did:a", "did:b", "did:c"),
                        List.of(new Request(PAGE_SIZE, null), new Request(PAGE_SIZE, 2))),
                linkedPageSize(String.valueOf(LINKED_PAGE_SIZE), LINKED_PAGE_SIZE),
                linkedPageSize(null, PAGE_SIZE),
                linkedPageSize("fifty", PAGE_SIZE),
                linkedPageSize("0", DataSpaceEventsProperties.TirWatcherSettings.MIN_PAGE_SIZE),
                linkedPageSize("500", DataSpaceEventsProperties.TirWatcherSettings.MAX_PAGE_SIZE),
                Arguments.of("registry caps the page size", DEFAULT_PAGE_SIZE, UNLIMITED_PAGES,
                        Registry.of(FIRST, page(120, next(DEFAULT_PAGE_SIZE, 1), dids(0, CAPPED_PAGE_SIZE)),
                                1, page(120, next(DEFAULT_PAGE_SIZE, 2), dids(CAPPED_PAGE_SIZE, CAPPED_PAGE_SIZE)),
                                2, page(120, next(DEFAULT_PAGE_SIZE, 3), dids(2 * CAPPED_PAGE_SIZE, 20))),
                        Set.of(dids(0, 120)),
                        List.of(new Request(DEFAULT_PAGE_SIZE, null), new Request(DEFAULT_PAGE_SIZE, 1),
                                new Request(DEFAULT_PAGE_SIZE, 2))),
                Arguments.of("exactly max-pages pages", PAGE_SIZE, MAX_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(4, null, "did:c", "did:d")),
                        Set.of("did:a", "did:b", "did:c", "did:d"),
                        List.of(new Request(PAGE_SIZE, null), new Request(PAGE_SIZE, 1))),
                Arguments.of("max-pages 1 with a single page", PAGE_SIZE, 1,
                        Registry.of(FIRST, page(2, null, "did:a", "did:b")),
                        Set.of("did:a", "did:b"), List.of(new Request(PAGE_SIZE, null))));
    }

    private static Arguments linkedPageSize(@Nullable String linkedSize, int expectedSize) {
        URI link = linkedSize == null ? link("page[after]=1") : link("page[size]=" + linkedSize + "&page[after]=1");
        return Arguments.of("linked page[size] " + linkedSize, PAGE_SIZE, UNLIMITED_PAGES,
                Registry.of(FIRST, page(3, link, "did:a", "did:b"), 1, page(3, null, "did:c")),
                Set.of("did:a", "did:b", "did:c"),
                List.of(new Request(PAGE_SIZE, null), new Request(expectedSize, 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteEnumerations")
    void rejectsIncompleteEnumerations(String name, int maxPages, Registry registry, int expectedPage,
            @Nullable Integer expectedStatus) {
        TirEnumerationException exception = assertThrows(TirEnumerationException.class,
                () -> reader(registry, PAGE_SIZE, maxPages).enumerate());
        assertEquals(expectedPage, exception.getPage());
        assertEquals(expectedStatus, exception.getStatus());
    }

    static Stream<Arguments> incompleteEnumerations() {
        return Stream.of(
                Arguments.of("registry shrank mid-enumeration", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"), 1, pastTheEnd()), 1, null),
                Arguments.of("links.next missing before the total", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, null, "did:a", "did:b")), 1, null),
                Arguments.of("links.next without page[after]", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, link("page[size]=2"), "did:a", "did:b")), 1, null),
                Arguments.of("string cursor", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, link("page[size]=2&page[after]=abc"), "did:a", "did:b")), 1, null),
                Arguments.of("repeated cursor", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(6, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(6, next(PAGE_SIZE, 1), "did:c", "did:d")), SECOND_PAGE, null),
                Arguments.of("empty first page with a total", UNLIMITED_PAGES,
                        Registry.of(FIRST, new IssuersResponseVO().items(List.of()).total(5).links(null)), 1, null),
                Arguments.of("first page without a total", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(null, null, "did:a")), 1, null),
                Arguments.of("first page without items", UNLIMITED_PAGES,
                        Registry.of(FIRST, new IssuersResponseVO().items(null).total(1)), 1, null),
                Arguments.of("5xx on page 2", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, Mono.error(new HttpClientResponseException("unavailable",
                                        HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)))),
                        SECOND_PAGE, HttpStatus.SERVICE_UNAVAILABLE.getCode()),
                Arguments.of("connection error", UNLIMITED_PAGES,
                        Registry.of(FIRST, Mono.error(new HttpClientException("Connect Error"))), 1, null),
                Arguments.of("no response", UNLIMITED_PAGES, Registry.of(FIRST, Mono.empty()), 1, null),
                Arguments.of("no body", UNLIMITED_PAGES,
                        Registry.of(FIRST, Mono.just(HttpResponse.noContent())), 1, null),
                Arguments.of("total changes between non-empty pages", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(5, null, "did:c", "did:d")), SECOND_PAGE, null),
                Arguments.of("fewer DIDs than the total", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(3, null, "did:a", "did:b")), 1, null),
                Arguments.of("more DIDs than the total", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(1, null, "did:a", "did:b")), 1, null),
                Arguments.of("duplicate DID across pages", UNLIMITED_PAGES,
                        Registry.of(FIRST, page(4, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(4, null, "did:b", "did:c")), 1, null),
                Arguments.of("blank DID", UNLIMITED_PAGES, Registry.of(FIRST, page(2, null, "did:a", " ")), 1, null),
                Arguments.of("one page more than max-pages", MAX_PAGES,
                        Registry.of(FIRST, page(5, next(PAGE_SIZE, 1), "did:a", "did:b"),
                                1, page(5, next(PAGE_SIZE, 2), "did:c", "did:d"),
                                2, page(5, null, "did:e")), MAX_PAGES, null));
    }

    @ParameterizedTest(name = "max-pages {0}")
    @MethodSource("pageLimits")
    void neverRequestsMoreThanMaxPages(int maxPages) {
        Registry registry = Registry.of(FIRST, page(6, next(PAGE_SIZE, 1), "did:a", "did:b"),
                1, page(6, next(PAGE_SIZE, 2), "did:c", "did:d"),
                2, page(6, null, "did:e", "did:f"));

        assertThrows(TirEnumerationException.class, () -> reader(registry, PAGE_SIZE, maxPages).enumerate());
        assertEquals(maxPages, registry.requests().size());
    }

    static Stream<Arguments> pageLimits() {
        return Stream.of(Arguments.of(1), Arguments.of(MAX_PAGES));
    }

    private static TrustedIssuersRegistryReader reader(Registry registry, int pageSize, int maxPages) {
        DataSpaceEventsProperties properties = new DataSpaceEventsProperties();
        properties.getTirWatcher().setPageSize(pageSize);
        properties.getTirWatcher().setMaxPages(maxPages);
        return new TrustedIssuersRegistryReader(registry.client(), properties);
    }

    private static IssuersResponseVO page(@Nullable Integer total, @Nullable URI next, String... dids) {
        return new IssuersResponseVO()
                .items(Arrays.stream(dids).map(did -> new IssuerEntryVO().did(did)).toList())
                .total(total)
                .pageSize(dids.length)
                .links(new LinksVO().next(next));
    }

    private static IssuersResponseVO pastTheEnd() {
        return new IssuersResponseVO().items(List.of()).total(0).pageSize(0).links(null);
    }

    private static URI next(int size, int after) {
        return link("page[size]=%s&page[after]=%s".formatted(size, after));
    }

    private static URI link(String query) {
        return URI.create(UNREACHABLE_REGISTRY + "?" + query);
    }

    private static String[] dids(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> "did:web:participant-" + i).toArray(String[]::new);
    }
}
