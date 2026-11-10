package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the reader with the generated client against a fake registry that pages and links like FIWARE TIL.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustedIssuersRegistryReaderHttpTest implements TestPropertyProvider {

    private static final String SPEC_NAME = "TrustedIssuersRegistryReaderHttpTest";
    private static final int PAGE_SIZE = 2;
    private static final int UNSET = -1;

    private final int port = freePort();

    @Inject
    private TrustedIssuersRegistryReader reader;

    @Inject
    private FakeRegistry registry;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spec.name", SPEC_NAME);
        properties.put("micronaut.server.port", String.valueOf(port));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("dataspace.events.publisher.enabled", "true");
        properties.put("dataspace.events.publisher.origin", "cm.provider.org");
        properties.put("dataspace.events.own-participant-id", "did:web:provider.org");
        properties.put("dataspace.events.tir-watcher.enabled", "true");
        properties.put("dataspace.events.tir-watcher.page-size", String.valueOf(PAGE_SIZE));
        // keeps the scheduled watcher from paging the fake registry during the test
        properties.put("dataspace.events.tir-watcher.initial-delay", "PT1H");
        // a publisher's registry is read by url, paged as participant-removed configures
        properties.put("dataspace.events.participant-removed.page-size", String.valueOf(PAGE_SIZE));
        properties.put("micronaut.http.services.trusted-issuers-registry.url", "http://localhost:" + port);
        properties.put("micronaut.http.services.trusted-issuers-registry.path", "/");
        return properties;
    }

    @BeforeEach
    void resetRegistry() {
        registry.requests.clear();
    }

    @ParameterizedTest(name = "{0} participants")
    @MethodSource("registrySizes")
    void enumeratesOverHttp(int participants, List<Map<String, Integer>> expectedRequests) {
        registry.dids = dids(participants);

        assertEquals(Set.copyOf(registry.dids), reader.enumerate());
        assertEquals(expectedRequests, registry.requests);
    }

    @ParameterizedTest(name = "{0} participants at {2}")
    @MethodSource("publisherRegistries")
    void enumeratesAPublisherRegistryByUrl(int participants, List<Map<String, Integer>> expectedRequests, String path) {
        registry.dids = dids(participants);

        assertEquals(Set.copyOf(registry.dids), reader.enumerate("http://localhost:" + port + path));
        assertEquals(expectedRequests, registry.requests);
    }

    static Stream<Arguments> publisherRegistries() {
        return registrySizes().flatMap(arguments -> Stream.of("", "/")
                .map(path -> Arguments.of(arguments.get()[0], arguments.get()[1], path)));
    }

    static Stream<Arguments> registrySizes() {
        return Stream.of(
                Arguments.of(0, List.of(request(PAGE_SIZE, UNSET))),
                Arguments.of(1, List.of(request(PAGE_SIZE, UNSET))),
                Arguments.of(4, List.of(request(PAGE_SIZE, UNSET), request(PAGE_SIZE, 1))),
                Arguments.of(5, List.of(request(PAGE_SIZE, UNSET), request(PAGE_SIZE, 1), request(PAGE_SIZE, 2))));
    }

    private static Map<String, Integer> request(int size, int after) {
        return Map.of(TrustedIssuersRegistryReader.PAGE_SIZE_PARAMETER, size,
                TrustedIssuersRegistryReader.PAGE_AFTER_PARAMETER, after);
    }

    private static List<String> dids(int count) {
        return IntStream.range(0, count).mapToObj(i -> "did:web:participant-" + i + ".org").toList();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Pages like FIWARE TIL: 0-based page[after], links.next on an unresolvable host only while there is a next page,
     * and a past-the-end or empty page answered with total 0 and no links.
     */
    @Requires(property = "spec.name", value = SPEC_NAME)
    @Controller("/v4/issuers")
    static class FakeRegistry {

        private static final String UNREACHABLE_HOST = "http://tir.unreachable.invalid/v4/issuers";

        private final List<Map<String, Integer>> requests = new CopyOnWriteArrayList<>();
        private volatile List<String> dids = List.of();

        @Get
        Map<String, Object> issuers(@Nullable @QueryValue("page[size]") Integer size,
                @Nullable @QueryValue("page[after]") Integer after) {
            requests.add(request(size == null ? UNSET : size, after == null ? UNSET : after));
            int pageSize = size == null ? PAGE_SIZE : size;
            int pageNumber = after == null ? 0 : after;
            int from = pageNumber * pageSize;
            if (from >= dids.size()) {
                return Map.of("items", List.of(), "total", 0, "pageSize", 0);
            }
            List<Map<String, String>> items = new ArrayList<>();
            dids.subList(from, Math.min(from + pageSize, dids.size()))
                    .forEach(did -> items.add(Map.of("did", did, "href", UNREACHABLE_HOST + "/" + did)));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("self", UNREACHABLE_HOST + "?page[size]=" + pageSize + "&page[after]=" + pageNumber);
            page.put("items", items);
            page.put("total", dids.size());
            page.put("pageSize", items.size());
            Map<String, Object> links = new LinkedHashMap<>();
            if (from + pageSize < dids.size()) {
                links.put("next", UNREACHABLE_HOST + "?page[size]=" + pageSize + "&page[after]=" + (pageNumber + 1));
            }
            page.put("links", links);
            return page;
        }
    }
}
