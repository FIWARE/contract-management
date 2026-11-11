package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only helpers for the event listener tests: the identity header the gateway would set and a controllable handler.
 */
final class TestEventFixtures {

    static final String ENABLED_PROPERTY = "test.dataspace-events";
    static final String KNOWN_TYPE = "org.fiware.dataspace.test";

    private TestEventFixtures() {
    }

    /**
     * The identity header an API gateway sets for the participant: base64 of the verified token's claims.
     */
    static String userinfo(String participant) {
        String claims = "{\"" + DataSpaceEventsProperties.DEFAULT_PARTICIPANT_CLAIM + "\":\"" + participant + "\"}";
        return Base64.getEncoder().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
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
        private final AtomicReference<Throwable> failNext = new AtomicReference<>();
        private volatile CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);

        @Override
        public boolean isEventTypeSupported(String type) {
            return KNOWN_TYPE.equals(type);
        }

        @Override
        public Mono<Void> handleEvent(DataSpaceEventVO event) {
            return Mono.defer(() -> {
                started.incrementAndGet();
                Throwable failure = failNext.getAndSet(null);
                if (failure != null) {
                    return Mono.error(failure);
                }
                return Mono.fromFuture(gate, true).doOnSuccess(ignored -> handled.incrementAndGet());
            });
        }

        void reset() {
            started.set(0);
            handled.set(0);
            failNext.set(null);
            gate = CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> hold() {
            gate = new CompletableFuture<>();
            return gate;
        }

        void failNext() {
            failNext(new IllegalStateException("Test handler failure."));
        }

        void failNext(Throwable failure) {
            failNext.set(failure);
        }

        int started() {
            return started.get();
        }

        int handled() {
            return handled.get();
        }
    }
}
