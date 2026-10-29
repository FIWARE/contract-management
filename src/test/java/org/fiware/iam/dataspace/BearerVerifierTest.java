package org.fiware.iam.dataspace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.ConfigurationException;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.exception.VerifierUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearerVerifierTest {

    private static final String PREFIX = "dataspace.events.listener.";
    private static final String JWKS_URL = "http://verifier/.well-known/jwks";
    private static final String OTHER_JWKS_URL = "http://other-verifier/.well-known/jwks";
    private static final String AUDIENCE = "contract-management";
    private static final String OTHER_AUDIENCE = "another-service";
    private static final String ISSUER = "https://verifier.org";
    private static final String OTHER_ISSUER = "https://evil.org";
    private static final String PARTICIPANT_CLAIM = "participantId";
    private static final String PARTICIPANT_ID = "did:web:provider.org";
    private static final String KID = "verifier-key";
    private static final String ES384_KID = "verifier-key-es384";
    private static final String UNKNOWN_KID = "unknown-key";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    // beyond the default clock skew tolerated by Nimbus
    private static final Duration EXPIRED = Duration.ofMinutes(10);
    private static final int NON_STRING_PARTICIPANT = 42;
    private static final String MALFORMED_URL = "not a url";
    private static final String LOWERCASE_ES256 = "es256";
    // keeps the test context away from the Rainbow and TM Forum startup calls
    private static final Map<String, Object> ISOLATED = Map.of("notification.enabled", false, "general.enable-rainbow", false);

    private static final ECKey SIGNING_KEY = generate(Curve.P_256, KID);
    private static final ECKey ES384_KEY = generate(Curve.P_384, ES384_KID);
    private static final ECKey WRONG_KEY = generate(Curve.P_256, KID);
    private static final ECKey UNKNOWN_KEY = generate(Curve.P_256, UNKNOWN_KID);
    private static final JWKSet JWKS = new JWKSet(List.of(SIGNING_KEY.toPublicJWK(), ES384_KEY.toPublicJWK()));

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BearerVerifier.class);

    private DataSpaceEventsProperties properties;
    private List<URL> requestedJwksUrls;
    private BearerVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new DataSpaceEventsProperties();
        DataSpaceEventsProperties.Listener.Verifier verifierConfig = properties.getListener().getVerifier();
        verifierConfig.setJwksUrl(JWKS_URL);
        verifierConfig.setAudience(AUDIENCE);
        verifierConfig.setIssuer(ISSUER);
        requestedJwksUrls = new ArrayList<>();
        Function<URL, JWKSource<SecurityContext>> factory = url -> {
            requestedJwksUrls.add(url);
            return new ImmutableJWKSet<>(JWKS);
        };
        verifier = new BearerVerifier(properties, factory);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizationHeaders")
    void extractsParticipantIdOnlyFromAcceptableTokens(String description, String header, Optional<String> expected) {
        assertEquals(expected, verifier.participantIdOf(header));
    }

    static Stream<Arguments> authorizationHeaders() {
        Optional<String> accepted = Optional.of(PARTICIPANT_ID);
        Optional<String> rejected = Optional.empty();
        return Stream.of(
                Arguments.of("valid", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build())), accepted),
                Arguments.of("case-insensitive scheme",
                        "bearer " + signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build()), accepted),
                Arguments.of("expired", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .expirationTime(Date.from(Instant.now().minus(EXPIRED))).build())), rejected),
                Arguments.of("not yet valid", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .notBeforeTime(Date.from(Instant.now().plus(EXPIRED))).build())), rejected),
                Arguments.of("no expiry", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .expirationTime(null).build())), rejected),
                Arguments.of("wrong key", bearer(signed(WRONG_KEY, JWSAlgorithm.ES256, validClaims().build())), rejected),
                Arguments.of("unknown kid", bearer(signed(UNKNOWN_KEY, JWSAlgorithm.ES256, validClaims().build())), rejected),
                Arguments.of("alg none", bearer(new PlainJWT(validClaims().build()).serialize()), rejected),
                Arguments.of("algorithm not allowed",
                        bearer(signed(ES384_KEY, JWSAlgorithm.ES384, validClaims().build())), rejected),
                Arguments.of("wrong issuer", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .issuer(OTHER_ISSUER).build())), rejected),
                Arguments.of("no audience", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .audience((String) null).build())), rejected),
                Arguments.of("other audience", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .audience(OTHER_AUDIENCE).build())), rejected),
                Arguments.of("our audience among others", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .audience(List.of(OTHER_AUDIENCE, AUDIENCE)).build())), accepted),
                Arguments.of("missing participant claim", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .claim(PARTICIPANT_CLAIM, null).build())), rejected),
                Arguments.of("non-string participant claim", bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims()
                        .claim(PARTICIPANT_CLAIM, NON_STRING_PARTICIPANT).build())), rejected),
                Arguments.of("missing header", null, rejected),
                Arguments.of("empty header", "", rejected),
                Arguments.of("other scheme", "Basic dXNlcjpwYXNz", rejected),
                Arguments.of("bearer without token", "Bearer ", rejected),
                Arguments.of("malformed token", "Bearer not-a-jwt", rejected));
    }

    @Test
    void acceptsTokensWithoutIssuerCheckWhenNoIssuerIsConfigured() {
        properties.getListener().getVerifier().setIssuer(null);

        String token = signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().issuer(OTHER_ISSUER).build());

        assertEquals(Optional.of(PARTICIPANT_ID), verifier.participantIdOf(bearer(token)));
    }

    @Test
    void readsTheConfiguredParticipantClaim() {
        String customClaim = "organization";
        properties.getListener().getVerifier().setParticipantClaim(customClaim);

        String token = signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().claim(customClaim, PARTICIPANT_ID).build());

        assertEquals(Optional.of(PARTICIPANT_ID), verifier.participantIdOf(bearer(token)));
    }

    @Test
    void reusesTheJwkSourceUntilTheJwksUrlChanges() throws Exception {
        String token = bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build()));

        verifier.participantIdOf(token);
        verifier.participantIdOf(token);
        properties.getListener().getVerifier().setJwksUrl(OTHER_JWKS_URL);
        verifier.participantIdOf(token);

        assertEquals(List.of(new URL(JWKS_URL), new URL(OTHER_JWKS_URL)), requestedJwksUrls);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankedSettings")
    void failsClosedWhenARefreshBlanksARequiredSetting(String setting,
                                                       Consumer<DataSpaceEventsProperties.Listener.Verifier> blank) {
        String token = bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build()));
        assertTrue(verifier.participantIdOf(token).isPresent());

        blank.accept(properties.getListener().getVerifier());

        assertEquals(Optional.empty(), verifier.participantIdOf(token));
    }

    static Stream<Arguments> blankedSettings() {
        return Stream.of(
                Arguments.of("audience", blanking(config -> config.setAudience(""))),
                Arguments.of("jwks-url", blanking(config -> config.setJwksUrl(""))),
                Arguments.of("algorithms", blanking(config -> config.setAlgorithms(Set.of()))),
                Arguments.of("unsupported algorithm", blanking(config -> config.setAlgorithms(Set.of(LOWERCASE_ES256)))),
                Arguments.of("malformed jwks-url", blanking(config -> config.setJwksUrl(MALFORMED_URL))));
    }

    @Test
    void logsAMisconfigurationOncePerState() {
        String token = bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build()));
        DataSpaceEventsProperties.Listener.Verifier config = properties.getListener().getVerifier();

        config.setAudience("");
        verifier.participantIdOf(token);
        verifier.participantIdOf(token);
        config.setAudience(AUDIENCE);
        config.setJwksUrl("");
        verifier.participantIdOf(token);
        config.setJwksUrl(JWKS_URL);
        verifier.participantIdOf(token);
        config.setAudience("");
        verifier.participantIdOf(token);

        // first breakage, a different breakage, the same breakage again after a recovery
        assertEquals(3, logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).count());
    }

    @Test
    void reportsAnUnavailableJwksInsteadOfRejectingTheToken() {
        JWKSource<SecurityContext> unavailable = (selector, context) -> {
            throw new KeySourceException("Couldn't retrieve JWK set from URL: connect timed out");
        };
        BearerVerifier withoutJwks = new BearerVerifier(properties, url -> unavailable);
        String token = bearer(signed(SIGNING_KEY, JWSAlgorithm.ES256, validClaims().build()));

        VerifierUnavailableException thrown =
                assertThrows(VerifierUnavailableException.class, () -> withoutJwks.participantIdOf(token));

        assertTrue(thrown.getCause() instanceof KeySourceException, "The JWKS failure must be kept as cause.");
    }

    private static Consumer<DataSpaceEventsProperties.Listener.Verifier> blanking(
            Consumer<DataSpaceEventsProperties.Listener.Verifier> change) {
        return change;
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("requiredSettings")
    void failsStartupWithoutARequiredSetting(String missingSetting) {
        Map<String, Object> config = new HashMap<>(completeListenerConfig());
        config.remove(PREFIX + missingSetting);

        Exception thrown = assertThrows(Exception.class, () -> ApplicationContext.run(config).close());

        assertTrue(hasCause(thrown, ConfigurationException.class), "Startup must fail with a configuration error.");
    }

    static Stream<Arguments> requiredSettings() {
        return Stream.of(Arguments.of("verifier.audience"), Arguments.of("verifier.jwks-url"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    void failsStartupWithAnInvalidSetting(String description, String setting, Object value) {
        Map<String, Object> config = new HashMap<>(completeListenerConfig());
        config.put(PREFIX + setting, value);

        Exception thrown = assertThrows(Exception.class, () -> ApplicationContext.run(config).close());

        assertTrue(hasCause(thrown, ConfigurationException.class), "Startup must fail with a configuration error.");
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of(
                Arguments.of("malformed jwks-url", "verifier.jwks-url", MALFORMED_URL),
                Arguments.of("lower-case algorithm", "verifier.algorithms", List.of(LOWERCASE_ES256)),
                Arguments.of("misspelled algorithm", "verifier.algorithms", List.of("ES-256")),
                Arguments.of("MAC algorithm", "verifier.algorithms", List.of(JWSAlgorithm.HS256.getName())),
                Arguments.of("one invalid among valid algorithms", "verifier.algorithms",
                        List.of(JWSAlgorithm.ES256.getName(), LOWERCASE_ES256)));
    }

    @Test
    void startsWithSupportedAlgorithms() {
        Map<String, Object> config = new HashMap<>(completeListenerConfig());
        config.put(PREFIX + "verifier.algorithms", List.of(JWSAlgorithm.ES384.getName(), JWSAlgorithm.PS256.getName()));

        try (ApplicationContext context = ApplicationContext.run(config)) {
            assertTrue(context.containsBean(BearerVerifier.class));
        }
    }

    @ParameterizedTest(name = "listener enabled: {0}")
    @MethodSource("listenerStates")
    void existsOnlyWithTheListenerEnabled(boolean enabled) {
        Map<String, Object> config = new HashMap<>(completeListenerConfig());
        config.put(PREFIX + "enabled", enabled);

        try (ApplicationContext context = ApplicationContext.run(config)) {
            assertEquals(enabled, context.containsBean(BearerVerifier.class));
        }
    }

    static Stream<Arguments> listenerStates() {
        return Stream.of(Arguments.of(true), Arguments.of(false));
    }

    private static Map<String, Object> completeListenerConfig() {
        Map<String, Object> config = new HashMap<>(ISOLATED);
        config.put(PREFIX + "enabled", true);
        config.put(PREFIX + "verifier.audience", AUDIENCE);
        config.put(PREFIX + "verifier.jwks-url", JWKS_URL);
        return config;
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .claim(PARTICIPANT_CLAIM, PARTICIPANT_ID);
    }

    private static String signed(ECKey key, JWSAlgorithm algorithm, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static ECKey generate(Curve curve, String kid) {
        try {
            return new ECKeyGenerator(curve).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
