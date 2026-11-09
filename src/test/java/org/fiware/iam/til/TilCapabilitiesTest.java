package org.fiware.iam.til;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import org.fiware.iam.configuration.AdoptionPolicy;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.dataspace.MutableClock;
import org.fiware.iam.exception.FailureReason;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ProblemDetailsVO;
import org.fiware.iam.til.model.ScopeTypeVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.fiware.iam.til.model.TrustedIssuersListResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests which capabilities are required, when they are re-probed, and how their states decide {@code requireSupported}.
 */
class TilCapabilitiesTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration JUST_BEFORE_RETRY = TilCapabilities.PROBE_RETRY_INTERVAL.minusSeconds(1);
    private static final String ISSUER_DID = "did:web:issuer.org";

    private final TrustedIssuersListAdapter adapter = mock(TrustedIssuersListAdapter.class);
    private final DataSpaceEventsProperties properties = mock(DataSpaceEventsProperties.class);
    private final MutableClock clock = new MutableClock(START);

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TilCapabilities.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(properties.adoption()).thenReturn(AdoptionPolicy.OFF);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adoptions")
    void required_followsTheAdoptionPolicy(AdoptionPolicy adoption, Set<TilCapability> expected) {
        when(properties.adoption()).thenReturn(adoption);

        Assertions.assertEquals(expected, capabilities().required());
    }

    static Stream<Arguments> adoptions() {
        Set<TilCapability> always = Set.of(TilCapability.TYPED_SCOPES, TilCapability.SCOPES_ENDPOINT);
        return Stream.of(
                Arguments.of(AdoptionPolicy.OFF, always),
                Arguments.of(AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.MULTIPLE_PUBLISHERS), always),
                Arguments.of(AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.NO_REGISTRY_URL), always),
                Arguments.of(AdoptionPolicy.ALLOWED, Set.of(TilCapability.values())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("states")
    void requireSupported_decidesByTheRequiredStates(String description, AdoptionPolicy adoption,
            TilCapability.State typedScopes, TilCapability.State scopesEndpoint, TilCapability.State unscoped,
            @Nullable FailureReason expected) {
        when(properties.adoption()).thenReturn(adoption);
        stubProbes(typedScopes, scopesEndpoint, unscoped);

        Mono<Void> required = capabilities().requireSupported();

        if (expected == null) {
            Assertions.assertDoesNotThrow(() -> required.block());
        } else {
            TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class, required::block);
            Assertions.assertEquals(expected, e.getReason());
        }
    }

    static Stream<Arguments> states() {
        TilCapability.State supported = TilCapability.State.SUPPORTED;
        TilCapability.State tooOld = TilCapability.State.TOO_OLD;
        TilCapability.State unverified = TilCapability.State.UNVERIFIED;
        TilCapability.State pending = TilCapability.State.PENDING;
        AdoptionPolicy off = AdoptionPolicy.OFF;
        AdoptionPolicy allowed = AdoptionPolicy.ALLOWED;
        AdoptionPolicy refused = AdoptionPolicy.refused(AdoptionPolicy.RefusalReason.NO_DATASPACE_ID);
        return Stream.of(
                Arguments.of("all supported", allowed, supported, supported, supported, null),
                Arguments.of("empty list", off, supported, pending, supported, null),
                Arguments.of("typed scopes too old", off, tooOld, supported, supported, FailureReason.TIL_TOO_OLD),
                Arguments.of("scopes endpoint too old", off, supported, tooOld, supported, FailureReason.TIL_TOO_OLD),
                Arguments.of("typed scopes unverified", off, unverified, supported, supported,
                        FailureReason.TIL_CAPABILITY_UNVERIFIED),
                Arguments.of("too old wins over unverified", off, unverified, tooOld, supported, FailureReason.TIL_TOO_OLD),
                Arguments.of("unscoped not required without adoption", off, supported, supported, tooOld, null),
                Arguments.of("unscoped not required with refused adoption", refused, supported, supported, tooOld, null),
                Arguments.of("unscoped too old with allowed adoption", allowed, supported, supported, tooOld,
                        FailureReason.TIL_TOO_OLD),
                Arguments.of("unscoped unverified with allowed adoption", allowed, supported, supported, unverified,
                        FailureReason.TIL_CAPABILITY_UNVERIFIED));
    }

    @Test
    void requireSupported_namesTheMissingCapability() {
        stubProbes(TilCapability.State.SUPPORTED, TilCapability.State.TOO_OLD, TilCapability.State.SUPPORTED);

        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> capabilities().requireSupported().block());
        Assertions.assertTrue(e.getMessage().contains(TilCapability.SCOPES_ENDPOINT.getId()), e.getMessage());
    }

    @Test
    void requireSupported_doesNotProbeAnUnrequiredCapability() {
        stubProbes(TilCapability.State.SUPPORTED, TilCapability.State.SUPPORTED, TilCapability.State.SUPPORTED);

        capabilities().requireSupported().block();

        verify(adapter, never()).probeUnscoped();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undecided")
    void requireSupported_reprobesAnUndecidedCapabilityAfterTheRetryInterval(TilCapability.State undecided) {
        when(adapter.probeTypedScopes()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(undecided), Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = capabilities();

        capabilities.requireSupported().onErrorComplete().block();
        clock.advance(JUST_BEFORE_RETRY);
        capabilities.requireSupported().onErrorComplete().block();
        verify(adapter, times(1)).probeScopesEndpoint();

        clock.advance(Duration.ofSeconds(1));
        capabilities.requireSupported().block();
        verify(adapter, times(2)).probeScopesEndpoint();
        Assertions.assertEquals(TilCapability.State.SUPPORTED, capabilities.state().get(TilCapability.SCOPES_ENDPOINT));

        clock.advance(TilCapabilities.PROBE_RETRY_INTERVAL);
        capabilities.requireSupported().block();
        verify(adapter, times(1)).probeTypedScopes();
        verify(adapter, times(2)).probeScopesEndpoint();
    }

    static Stream<TilCapability.State> undecided() {
        return Stream.of(TilCapability.State.UNVERIFIED, TilCapability.State.PENDING);
    }

    @Test
    void tooOld_isLoggedOnceAndResetByARefresh() {
        when(adapter.probeTypedScopes()).thenReturn(Mono.just(TilCapability.State.TOO_OLD), Mono.just(TilCapability.State.SUPPORTED));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = capabilities();

        for (int attempt = 0; attempt < 3; attempt++) {
            Assertions.assertThrows(TrustedIssuersException.class, () -> capabilities.requireSupported().block());
            clock.advance(TilCapabilities.PROBE_RETRY_INTERVAL);
        }
        verify(adapter, times(1)).probeTypedScopes();
        Assertions.assertEquals(1, errorsLogged(), "TOO_OLD is final and reported once.");

        capabilities.onApplicationEvent(new RefreshEvent());
        Assertions.assertEquals(TilCapability.State.UNVERIFIED, capabilities.state().get(TilCapability.TYPED_SCOPES));

        Assertions.assertDoesNotThrow(() -> capabilities.requireSupported().block(), "An upgraded list is picked up.");
        verify(adapter, times(2)).probeTypedScopes();
        Assertions.assertEquals(1, errorsLogged());
    }

    @Test
    void refresh_dropsAProbeAnsweredForThePreviousList() {
        Sinks.One<TilCapability.State> previousList = Sinks.one();
        when(adapter.probeTypedScopes()).thenReturn(previousList.asMono(), Mono.just(TilCapability.State.TOO_OLD));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = capabilities();
        capabilities.probeRequired().subscribe();

        capabilities.onApplicationEvent(new RefreshEvent());
        previousList.tryEmitValue(TilCapability.State.SUPPORTED);

        Assertions.assertEquals(TilCapability.State.UNVERIFIED, capabilities.state().get(TilCapability.TYPED_SCOPES));
        TrustedIssuersException e = Assertions.assertThrows(TrustedIssuersException.class,
                () -> capabilities.requireSupported().block(), "The refreshed list is probed anew, not joined to the old probe.");
        Assertions.assertEquals(FailureReason.TIL_TOO_OLD, e.getReason());
    }

    @Test
    void requireSupported_claimsNoProbeUntilSubscribed() {
        stubProbes(TilCapability.State.SUPPORTED, TilCapability.State.SUPPORTED, TilCapability.State.SUPPORTED);
        TilCapabilities capabilities = capabilities();

        capabilities.requireSupported();
        verify(adapter, never()).probeTypedScopes();

        Assertions.assertDoesNotThrow(() -> capabilities.requireSupported().block());
        verify(adapter, times(1)).probeTypedScopes();
    }

    @Test
    void requireSupported_recordsAProbeWhoseCallerCancelled() {
        Sinks.One<TilCapability.State> answer = Sinks.one();
        when(adapter.probeTypedScopes()).thenReturn(answer.asMono());
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = capabilities();

        capabilities.requireSupported().subscribe().dispose();
        answer.tryEmitValue(TilCapability.State.SUPPORTED);

        Assertions.assertEquals(TilCapability.State.SUPPORTED, capabilities.state().get(TilCapability.TYPED_SCOPES));
        Assertions.assertDoesNotThrow(() -> capabilities.requireSupported().block());
        verify(adapter, times(1)).probeTypedScopes();
    }

    @Test
    void requireSupported_sharesARunningProbe() {
        Sinks.One<TilCapability.State> answer = Sinks.one();
        when(adapter.probeTypedScopes()).thenReturn(answer.asMono());
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = capabilities();
        CompletableFuture<Void> first = capabilities.requireSupported().toFuture();
        CompletableFuture<Void> second = capabilities.requireSupported().toFuture();
        Assertions.assertFalse(second.isDone(), "A caller arriving during the probe waits for it instead of failing.");

        answer.tryEmitValue(TilCapability.State.SUPPORTED);

        Assertions.assertDoesNotThrow(() -> first.join());
        Assertions.assertDoesNotThrow(() -> second.join());
        verify(adapter, times(1)).probeTypedScopes();
    }

    @Test
    void probeRequired_givesUnverifiedForAHangingProbe() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        when(adapter.probeTypedScopes()).thenReturn(Mono.never());
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(TilCapability.State.SUPPORTED));
        TilCapabilities capabilities = new TilCapabilities(adapter, properties, clock, scheduler);

        StepVerifier.withVirtualTime(capabilities::probeRequired, () -> scheduler, Long.MAX_VALUE)
                .expectSubscription()
                .expectNoEvent(TilCapabilities.PROBE_TIMEOUT.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(Map.of(TilCapability.TYPED_SCOPES, TilCapability.State.UNVERIFIED,
                        TilCapability.SCOPES_ENDPOINT, TilCapability.State.SUPPORTED))
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hangingReads")
    void probeRequired_boundsEveryReadOfTheScopesEndpointProbe(String description, Mono<?> list, Mono<?> scopes,
            Mono<?> issuer) {
        IssuerApiClient apiClient = mock(IssuerApiClient.class);
        doReturn(Mono.error(new HttpClientResponseException("bad", HttpResponse.status(HttpStatus.BAD_REQUEST)
                .body(new ProblemDetailsVO().detail(TrustedIssuersListAdapter.SCOPE_TYPE_WITHOUT_SCOPE)))))
                .when(apiClient).getIssuers(null, null, null, ScopeTypeVO.DATASPACE_MEMBERSHIP);
        doReturn(list).when(apiClient).getIssuers(1, 0, null, null);
        doReturn(scopes).when(apiClient).getIssuerScopes(ISSUER_DID);
        doReturn(issuer).when(apiClient).getIssuer(ISSUER_DID);
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        TilCapabilities capabilities = new TilCapabilities(new TrustedIssuersListAdapter(apiClient, properties), properties,
                clock, scheduler);

        StepVerifier.withVirtualTime(capabilities::probeRequired, () -> scheduler, Long.MAX_VALUE)
                .expectSubscription()
                .expectNoEvent(TilCapabilities.PROBE_TIMEOUT.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(Map.of(TilCapability.TYPED_SCOPES, TilCapability.State.SUPPORTED,
                        TilCapability.SCOPES_ENDPOINT, TilCapability.State.UNVERIFIED))
                .verifyComplete();
    }

    static Stream<Arguments> hangingReads() {
        Mono<?> oneIssuer = Mono.just(HttpResponse.ok(new TrustedIssuersListResponseVO().total(1).items(List.of(ISSUER_DID))));
        Mono<?> notFound = Mono.error(new HttpClientResponseException("not found", HttpResponse.notFound()));
        Mono<?> found = Mono.just(HttpResponse.ok(new TrustedIssuerVO().did(ISSUER_DID)));
        return Stream.of(
                Arguments.of("list hangs", Mono.never(), notFound, found),
                Arguments.of("scopes hang", oneIssuer, Mono.never(), found),
                Arguments.of("issuer read hangs", oneIssuer, notFound, Mono.never()));
    }

    private TilCapabilities capabilities() {
        return new TilCapabilities(adapter, properties, clock);
    }

    private void stubProbes(TilCapability.State typedScopes, TilCapability.State scopesEndpoint, TilCapability.State unscoped) {
        when(adapter.probeTypedScopes()).thenReturn(Mono.just(typedScopes));
        when(adapter.probeScopesEndpoint()).thenReturn(Mono.just(scopesEndpoint));
        when(adapter.probeUnscoped()).thenReturn(Mono.just(unscoped));
    }

    private long errorsLogged() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
    }
}
