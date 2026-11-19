package org.fiware.iam.til;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.CredentialsVOTestExample;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.fiware.iam.til.TrustedIssuersListAdapter.DATASPACE_MEMBERSHIP_SCOPE_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the scope type the generated client puts on the wire, against a fake list that validates it like TIL 0.10.3.
 */
@MicronautTest(packages = {"org.fiware.iam"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustedIssuersListAdapterHttpTest implements TestPropertyProvider {

    private static final String SPEC_NAME = "TrustedIssuersListAdapterHttpTest";
    private static final String ISSUER_DID = "did:web:participant.org";
    private static final String PUBLISHER_ID = "did:web:publisher.org";
    private static final Set<String> GRANTED_ISSUERS = Set.of("did:web:first.org", "did:web:second.org");
    private static final String SCOPE_TYPE_PARAMETER = "scopeType";
    private static final String UNKNOWN_SCOPE_TYPE = "Unknown scope type";
    private static final List<String> TYPED = List.of(ScopeTypeVO.DATASPACE_MEMBERSHIP_VALUE);
    private static final List<String> UNTYPED = List.of();

    private final int port = freePort();

    @Inject
    private TrustedIssuersListAdapter adapter;

    @Inject
    private FakeTrustedIssuersList til;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spec.name", SPEC_NAME);
        properties.put("micronaut.server.port", String.valueOf(port));
        properties.put("notification.enabled", "false");
        properties.put("general.enable-rainbow", "false");
        properties.put("micronaut.http.services.trusted-issuers-list.url", "http://localhost:" + port);
        properties.put("micronaut.http.services.trusted-issuers-list.path", "/");
        return properties;
    }

    @BeforeEach
    void resetList() {
        til.received.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("calls")
    void sendsTheScopeTypeByItsApiValue(Call call) {
        assertEquals(call.expected(), call.call().apply(adapter).block());
        assertEquals(List.of(call.received()), til.received);
    }

    static Stream<Call> calls() {
        return Stream.of(
                new Call("probe typed scopes", TrustedIssuersListAdapter::probeTypedScopes,
                        TilCapability.State.SUPPORTED, new Received(FakeTrustedIssuersList.LIST, TYPED)),
                new Call("list the issuers granted a membership",
                        a -> a.issuersGrantedBy(PUBLISHER_ID, DATASPACE_MEMBERSHIP_SCOPE_TYPE),
                        GRANTED_ISSUERS, new Received(FakeTrustedIssuersList.LIST, TYPED)),
                new Call("grant a membership",
                        a -> a.grantMembership(ISSUER_DID, PUBLISHER_ID, List.of(CredentialsVOTestExample.build())),
                        null, new Received(FakeTrustedIssuersList.REPLACE, TYPED)),
                new Call("revoke a membership",
                        a -> a.revokeGrant(ISSUER_DID, PUBLISHER_ID, DATASPACE_MEMBERSHIP_SCOPE_TYPE),
                        null, new Received(FakeTrustedIssuersList.REVOKE, TYPED)),
                new Call("revoke an untyped grant", a -> a.revokeGrant(ISSUER_DID, PUBLISHER_ID, null),
                        null, new Received(FakeTrustedIssuersList.REVOKE, UNTYPED)),
                new Call("list all issuers", TrustedIssuersListAdapter::allIssuers,
                        GRANTED_ISSUERS, new Received(FakeTrustedIssuersList.LIST, UNTYPED)));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An adapter call, the value it completes with ({@code null} for none) and what the fake list receives.
     */
    record Call(String name, Function<TrustedIssuersListAdapter, Mono<?>> call, @Nullable Object expected,
            Received received) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * An operation of the fake list and the raw scopeType values it was called with.
     */
    record Received(String operation, List<String> scopeTypes) {
    }

    /**
     * Validates the scope type like TIL 0.10.3: anything but the API value is an unknown scope type, and a type without
     * a scope is rejected with the message the typed-scopes probe expects.
     */
    @Requires(property = "spec.name", value = SPEC_NAME)
    @Controller
    static class FakeTrustedIssuersList {

        static final String LIST = "list";
        static final String REPLACE = "replace";
        static final String REVOKE = "revoke";

        private final List<Received> received = new CopyOnWriteArrayList<>();

        @Get(IssuerApiClient.PATH_GET_ISSUERS)
        HttpResponse<?> issuers(HttpRequest<?> request, @Nullable @QueryValue String scope) {
            List<String> scopeTypes = record(LIST, request);
            return unknownScopeType(scopeTypes).orElseGet(() -> !scopeTypes.isEmpty() && scope == null
                    ? badRequest(TrustedIssuersListAdapter.SCOPE_TYPE_WITHOUT_SCOPE)
                    : HttpResponse.ok(Map.of(
                            "total", GRANTED_ISSUERS.size(),
                            "pageSize", GRANTED_ISSUERS.size(),
                            "page", 0,
                            "items", List.copyOf(GRANTED_ISSUERS))));
        }

        @Put(IssuerApiClient.PATH_REPLACE_CREDENTIALS_BY_SCOPE)
        HttpResponse<?> replace(HttpRequest<?> request, @PathVariable String did) {
            return unknownScopeType(record(REPLACE, request)).orElseGet(() -> HttpResponse.ok(Map.of("did", did)));
        }

        @Delete(IssuerApiClient.PATH_DELETE_CREDENTIALS_BY_SCOPE)
        HttpResponse<?> revoke(HttpRequest<?> request, @PathVariable String did) {
            return unknownScopeType(record(REVOKE, request)).orElseGet(HttpResponse::noContent);
        }

        private List<String> record(String operation, HttpRequest<?> request) {
            List<String> scopeTypes = request.getParameters().getAll(SCOPE_TYPE_PARAMETER);
            received.add(new Received(operation, scopeTypes));
            return scopeTypes;
        }

        // checked before anything else is validated
        private static Optional<HttpResponse<?>> unknownScopeType(List<String> scopeTypes) {
            return scopeTypes.stream().allMatch(ScopeTypeVO.DATASPACE_MEMBERSHIP_VALUE::equals)
                    ? Optional.empty()
                    : Optional.of(badRequest(UNKNOWN_SCOPE_TYPE));
        }

        private static HttpResponse<?> badRequest(String detail) {
            return HttpResponse.badRequest(Map.of(
                    "title", HttpStatus.BAD_REQUEST.getReason(),
                    "status", HttpStatus.BAD_REQUEST.getCode(),
                    "detail", detail));
        }
    }
}
