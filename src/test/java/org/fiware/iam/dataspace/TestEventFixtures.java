package org.fiware.iam.dataspace;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import jakarta.inject.Singleton;
import org.fiware.iam.events.model.DataSpaceEventVO;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only beans for the event listener tests: a JWKS endpoint for the verifier key and a controllable handler.
 */
final class TestEventFixtures {

    static final String ENABLED_PROPERTY = "test.dataspace-events";
    static final String JWKS_PATH = "/test/jwks";
    static final String KNOWN_TYPE = "org.fiware.dataspace.test";
    static final String KID = "test-verifier-key";
    static final ECKey SIGNING_KEY = generateKey();

    private TestEventFixtures() {
    }

    private static ECKey generateKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(KID).algorithm(JWSAlgorithm.ES256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Serves the public part of {@link #SIGNING_KEY}, standing in for the verifier's JWKS.
     */
    @Requires(env = "test")
    @Requires(property = ENABLED_PROPERTY, value = "true")
    @Controller
    static class JwksController {

        @Get(JWKS_PATH)
        @Produces(MediaType.APPLICATION_JSON)
        Map<String, Object> jwks() {
            return new JWKSet(SIGNING_KEY.toPublicJWK()).toJSONObject();
        }
    }

    /**
     * Handles {@link #KNOWN_TYPE}; counts calls and can be made to fail or to wait on a gate.
     */
    @Requires(env = "test")
    @Requires(property = ENABLED_PROPERTY, value = "true")
    @Singleton
    static class CountingHandler implements DataSpaceEventHandler {

        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger handled = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();
        private volatile CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);

        @Override
        public boolean isEventTypeSupported(String type) {
            return KNOWN_TYPE.equals(type);
        }

        @Override
        public Mono<Void> handleEvent(DataSpaceEventVO event) {
            return Mono.defer(() -> {
                started.incrementAndGet();
                if (failNext.getAndSet(false)) {
                    return Mono.error(new IllegalStateException("Test handler failure."));
                }
                return Mono.fromFuture(gate, true).doOnSuccess(ignored -> handled.incrementAndGet());
            });
        }

        void reset() {
            started.set(0);
            handled.set(0);
            failNext.set(false);
            gate = CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> hold() {
            gate = new CompletableFuture<>();
            return gate;
        }

        void failNext() {
            failNext.set(true);
        }

        int started() {
            return started.get();
        }

        int handled() {
            return handled.get();
        }
    }
}
