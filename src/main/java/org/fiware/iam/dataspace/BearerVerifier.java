package org.fiware.iam.dataspace;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.VerifierUnavailableException;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verifies the verifier-issued bearer token of an incoming event and extracts the caller's participant id.
 * Reads the refreshable verifier configuration on every call; a refresh that makes it unusable fails closed.
 */
@Slf4j
@Context
@Requires(condition = DataSpaceEventsProperties.ListenerCondition.class)
public class BearerVerifier {

    private static final String BEARER_SCHEME = "Bearer ";
    private static final String CONFIG_PREFIX = "dataspace.events.listener.verifier.";
    private static final Set<String> REQUIRED_CLAIMS = Collections.singleton(JWTClaimNames.EXPIRATION_TIME);
    // DefaultJWSVerifierFactory only covers RSA, EC and HMAC keys; JDK 21's SunEC dropped secp256k1 (ES256K)
    private static final Set<JWSAlgorithm> VERIFIABLE_ALGORITHMS = Stream.concat(
                    JWSAlgorithm.Family.RSA.stream(), JWSAlgorithm.Family.EC.stream())
            .filter(algorithm -> !JWSAlgorithm.ES256K.equals(algorithm))
            .collect(Collectors.toUnmodifiableSet());

    private final DataSpaceEventsProperties properties;
    private final Function<URL, JWKSource<SecurityContext>> jwkSourceFactory;
    private final AtomicReference<TrackedJwkSource> jwkSource = new AtomicReference<>();
    private final AtomicReference<String> reportedMisconfiguration = new AtomicReference<>();

    @Inject
    public BearerVerifier(DataSpaceEventsProperties properties) {
        this(properties, url -> JWKSourceBuilder.create(url).build());
    }

    BearerVerifier(DataSpaceEventsProperties properties, Function<URL, JWKSource<SecurityContext>> jwkSourceFactory) {
        this.properties = properties;
        this.jwkSourceFactory = jwkSourceFactory;
    }

    @PostConstruct
    void validateConfiguration() {
        misconfiguration(verifier()).ifPresent(problem -> {
            throw new ConfigurationException(problem);
        });
    }

    /**
     * The participant id claim of a valid bearer token, empty if the header is missing or the token is not acceptable.
     * Blocks on a JWKS fetch (cache miss or unknown kid), so it must not run on the event loop.
     *
     * @throws VerifierUnavailableException if the JWKS cannot be fetched, so the token can be neither accepted nor rejected
     */
    public Optional<String> participantIdOf(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            log.debug("No bearer token in the Authorization header.");
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_SCHEME.length()).strip();
        DataSpaceEventsProperties.Listener.Verifier verifier = verifier();
        Optional<String> misconfiguration = misconfiguration(verifier);
        reportMisconfiguration(misconfiguration);
        if (misconfiguration.isPresent()) {
            return Optional.empty();
        }
        TrackedJwkSource keys;
        try {
            keys = jwkSource(verifier.getJwksUrl());
        } catch (MalformedURLException | IllegalArgumentException e) {
            // only reachable if a refresh broke the URL after the configuration check above
            log.debug("Rejecting event bearer token: {}jwks-url {} is not a valid URL.", CONFIG_PREFIX, verifier.getJwksUrl());
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = processor(verifier, keys).process(token, null);
        } catch (RateLimitReachedException e) {
            // any caller can provoke this with an unknown kid; it only means "unavailable" while the last fetch failed
            if (keys.unreachable()) {
                throw unavailable(verifier, e);
            }
            log.debug("Rejecting event bearer token: no matching key, JWKS refetch is rate limited.");
            return Optional.empty();
        } catch (KeySourceException e) {
            throw unavailable(verifier, e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.debug("Rejecting event bearer token: {}", e.getMessage());
            return Optional.empty();
        }
        return participantClaim(claims, verifier.getParticipantClaim());
    }

    @PreDestroy
    void close() {
        closeQuietly(jwkSource.getAndSet(null));
    }

    private static VerifierUnavailableException unavailable(DataSpaceEventsProperties.Listener.Verifier verifier,
                                                            KeySourceException cause) {
        return new VerifierUnavailableException(
                "Cannot verify event bearer token, JWKS " + verifier.getJwksUrl() + " is unavailable.", cause);
    }

    /**
     * Why the verifier configuration cannot verify any token, empty if it is usable.
     */
    private static Optional<String> misconfiguration(DataSpaceEventsProperties.Listener.Verifier verifier) {
        if (isBlank(verifier.getAudience())) {
            return Optional.of(CONFIG_PREFIX + "audience is required when the event listener is enabled.");
        }
        if (isBlank(verifier.getJwksUrl())) {
            return Optional.of(CONFIG_PREFIX + "jwks-url is required when the event listener is enabled.");
        }
        if (isEmpty(verifier.getAlgorithms())) {
            return Optional.of(CONFIG_PREFIX + "algorithms must not be empty.");
        }
        // JWSAlgorithm.parse accepts anything, so a typo would silently match no token
        Set<String> unsupported = verifier.getAlgorithms().stream()
                .filter(algorithm -> !VERIFIABLE_ALGORITHMS.contains(JWSAlgorithm.parse(algorithm)))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!unsupported.isEmpty()) {
            return Optional.of(CONFIG_PREFIX + "algorithms contains unsupported signature algorithms " + unsupported
                    + ", allowed are " + VERIFIABLE_ALGORITHMS.stream().map(JWSAlgorithm::getName)
                    .collect(Collectors.toCollection(TreeSet::new)) + ".");
        }
        try {
            toUrl(verifier.getJwksUrl());
        } catch (MalformedURLException | IllegalArgumentException e) {
            return Optional.of(CONFIG_PREFIX + "jwks-url is not a valid URL: " + verifier.getJwksUrl());
        }
        return Optional.empty();
    }

    // any caller can trigger verification, so a broken refresh is reported once per state instead of once per request
    private void reportMisconfiguration(Optional<String> misconfiguration) {
        String current = misconfiguration.orElse(null);
        String previous = reportedMisconfiguration.getAndSet(current);
        if (current == null) {
            if (previous != null) {
                log.info("Event bearer verification is configured again, accepting tokens.");
            }
            return;
        }
        if (current.equals(previous)) {
            log.debug("Rejecting event bearer token: {}", current);
        } else {
            log.error("Rejecting all event bearer tokens until the configuration is fixed: {}", current);
        }
    }

    private static DefaultJWTProcessor<SecurityContext> processor(DataSpaceEventsProperties.Listener.Verifier verifier,
                                                                  JWKSource<SecurityContext> keys) {
        Set<JWSAlgorithm> algorithms = verifier.getAlgorithms().stream()
                .map(JWSAlgorithm::parse)
                .collect(Collectors.toSet());
        JWTClaimsSet exactMatchClaims = isBlank(verifier.getIssuer())
                ? new JWTClaimsSet.Builder().build()
                : new JWTClaimsSet.Builder().issuer(verifier.getIssuer()).build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));
        processor.setJWTClaimsSetVerifier(
                new DefaultJWTClaimsVerifier<>(Collections.singleton(verifier.getAudience()), exactMatchClaims, REQUIRED_CLAIMS, null));
        return processor;
    }

    private TrackedJwkSource jwkSource(String jwksUrl) throws MalformedURLException {
        while (true) {
            TrackedJwkSource current = jwkSource.get();
            if (current != null && current.url().equals(jwksUrl)) {
                return current;
            }
            // replaced, not mutated: a refresh pointing to another verifier must not keep the old keys
            TrackedJwkSource created = new TrackedJwkSource(jwksUrl, jwkSourceFactory.apply(toUrl(jwksUrl)));
            if (jwkSource.compareAndSet(current, created)) {
                closeQuietly(current);
                return created;
            }
            // a concurrent request swapped first; drop ours so its refresh thread does not leak
            closeQuietly(created);
        }
    }

    private static void closeQuietly(@Nullable TrackedJwkSource source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (IOException | RuntimeException e) {
            log.debug("Could not close the JWK source for {}.", source.url(), e);
        }
    }

    private static Optional<String> participantClaim(JWTClaimsSet claims, String claimName) {
        try {
            Optional<String> participantId = Optional.ofNullable(claims.getStringClaim(claimName)).filter(id -> !id.isBlank());
            if (participantId.isEmpty()) {
                log.debug("Rejecting event bearer token: no participant claim {}.", claimName);
            }
            return participantId;
        } catch (ParseException e) {
            log.debug("Rejecting event bearer token: participant claim {} is not a string.", claimName);
            return Optional.empty();
        }
    }

    private DataSpaceEventsProperties.Listener.Verifier verifier() {
        return properties.getListener().getVerifier();
    }

    private static URL toUrl(String url) throws MalformedURLException {
        return URI.create(url).toURL();
    }

    private static boolean isEmpty(@Nullable Set<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * JWK source for one JWKS URL that remembers whether its last fetch failed.
     * Tells a rate-limited refetch during an outage apart from one provoked by an unknown kid.
     */
    private static final class TrackedJwkSource implements JWKSource<SecurityContext>, Closeable {

        private final String url;
        private final JWKSource<SecurityContext> delegate;
        private final AtomicBoolean unreachable = new AtomicBoolean();

        private TrackedJwkSource(String url, JWKSource<SecurityContext> delegate) {
            this.url = url;
            this.delegate = delegate;
        }

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
            try {
                List<JWK> keys = delegate.get(selector, context);
                unreachable.set(false);
                return keys;
            } catch (RateLimitReachedException e) {
                // no fetch happened, so it says nothing about reachability
                throw e;
            } catch (KeySourceException e) {
                unreachable.set(true);
                throw e;
            }
        }

        String url() {
            return url;
        }

        boolean unreachable() {
            return unreachable.get();
        }

        @Override
        public void close() throws IOException {
            if (delegate instanceof Closeable closeable) {
                closeable.close();
            }
        }
    }
}
