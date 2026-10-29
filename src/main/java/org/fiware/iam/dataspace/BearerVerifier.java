package org.fiware.iam.dataspace;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
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
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verifies the verifier-issued bearer token of an incoming event and extracts the caller's participant id.
 * Reads the refreshable verifier configuration on every call; a refresh that blanks jwks-url or audience fails closed.
 */
@Slf4j
@Context
@Requires(condition = DataSpaceEventsProperties.ListenerCondition.class)
public class BearerVerifier {

    private static final String BEARER_SCHEME = "Bearer ";
    private static final String CONFIG_PREFIX = "dataspace.events.listener.verifier.";
    private static final Set<String> REQUIRED_CLAIMS = Collections.singleton(JWTClaimNames.EXPIRATION_TIME);

    private final DataSpaceEventsProperties properties;
    private final Function<URL, JWKSource<SecurityContext>> jwkSourceFactory;
    private final AtomicReference<CachedJwkSource> jwkSource = new AtomicReference<>();

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
        DataSpaceEventsProperties.Listener.Verifier verifier = verifier();
        if (isBlank(verifier.getAudience())) {
            throw new ConfigurationException(CONFIG_PREFIX + "audience is required when the event listener is enabled.");
        }
        if (isBlank(verifier.getJwksUrl())) {
            throw new ConfigurationException(CONFIG_PREFIX + "jwks-url is required when the event listener is enabled.");
        }
        if (isEmpty(verifier.getAlgorithms())) {
            throw new ConfigurationException(CONFIG_PREFIX + "algorithms must not be empty.");
        }
        try {
            toUrl(verifier.getJwksUrl());
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new ConfigurationException(CONFIG_PREFIX + "jwks-url is not a valid URL: " + verifier.getJwksUrl(), e);
        }
    }

    /**
     * The participant id claim of a valid bearer token, empty if the header is missing or the token is not acceptable.
     * Blocks on a JWKS fetch (cache miss or unknown kid), so it must not run on the event loop.
     */
    public Optional<String> participantIdOf(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            log.debug("No bearer token in the Authorization header.");
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_SCHEME.length()).strip();
        DataSpaceEventsProperties.Listener.Verifier verifier = verifier();
        if (isBlank(verifier.getAudience()) || isBlank(verifier.getJwksUrl()) || isEmpty(verifier.getAlgorithms())) {
            log.error("Rejecting event bearer token: {}audience, jwks-url and algorithms must be configured.", CONFIG_PREFIX);
            return Optional.empty();
        }
        JWKSource<SecurityContext> keys;
        try {
            keys = jwkSource(verifier.getJwksUrl());
        } catch (MalformedURLException | IllegalArgumentException e) {
            log.error("Rejecting event bearer token: {}jwks-url {} is not a valid URL.", CONFIG_PREFIX, verifier.getJwksUrl(), e);
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = processor(verifier, keys).process(token, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.debug("Rejecting event bearer token: {}", e.getMessage());
            return Optional.empty();
        }
        return participantClaim(claims, verifier.getParticipantClaim());
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

    private JWKSource<SecurityContext> jwkSource(String jwksUrl) throws MalformedURLException {
        CachedJwkSource cached = jwkSource.get();
        if (cached != null && cached.url().equals(jwksUrl)) {
            return cached.source();
        }
        // replaced, not mutated: a refresh pointing to another verifier must not keep the old keys
        CachedJwkSource created = new CachedJwkSource(jwksUrl, jwkSourceFactory.apply(toUrl(jwksUrl)));
        jwkSource.set(created);
        return created.source();
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

    private record CachedJwkSource(String url, JWKSource<SecurityContext> source) {
    }
}
