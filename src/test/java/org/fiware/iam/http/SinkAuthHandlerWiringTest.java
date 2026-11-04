package org.fiware.iam.http;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.fiware.iam.dataspace.EventDelivery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that the OID4VP beans for participant-chosen sinks are wired, so deliveries authenticate through a client that
 * never follows a redirect, while the rest of the application keeps the general handler.
 */
class SinkAuthHandlerWiringTest {

    private static final String CURVE_P256 = "secp256r1";
    private static final String KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String KEY_FOOTER = "-----END PRIVATE KEY-----";
    private static final String KEY_FILE = "holder-key.pem";
    private static final String CREDENTIALS_FOLDER = "credentials";
    private static final String HOLDER_ID = "did:key:zDnaehXH4gDLjLeWcACPyQX9TnvsKiQNt6KT7fdsfyW6fhEYA";
    private static final Map<String, Object> BASE_CONFIG = Map.of(
            "notification.enabled", false,
            "general.enable-rainbow", false,
            "dataspace.events.listener.verifier.audience", "contract-management",
            "dataspace.events.listener.verifier.jwks-url", "http://verifier/.well-known/jwks",
            "dataspace.events.publisher.enabled", true,
            "dataspace.events.publisher.origin", "https://contract-management.org",
            "oid4vp.enabled", true,
            "oid4vp.proxyConfig.use-proxy", false,
            "oid4vp.holder.holderId", HOLDER_ID);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void addProvider() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void usesTheNonRedirectingClientForSinksAndTheGeneralOneForTheRest() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(configuration())) {
            assertEquals(HttpClient.Redirect.NEVER,
                    context.getBean(HttpClient.class, Qualifiers.byName(SinkAuthHandler.SINK_QUALIFIER)).followRedirects());
            assertEquals(HttpClient.Redirect.NORMAL, context.getBean(HttpClient.class).followRedirects());
            assertInstanceOf(Oid4VpSinkAuthHandler.class, context.getBean(SinkAuthHandler.class));
            assertInstanceOf(Oid4VpAuthHandler.class, context.getBean(AuthHandler.class));
            // resolves the whole delivery graph, including its Optional<SinkAuthHandler>
            assertNotNull(context.getBean(EventDelivery.class));
        }
    }

    private Map<String, Object> configuration() throws GeneralSecurityException, IOException {
        Map<String, Object> configuration = new HashMap<>(BASE_CONFIG);
        configuration.put("oid4vp.holder.keyPath", writeKey().toString());
        configuration.put("oid4vp.holder.keyType", "EC");
        configuration.put("oid4vp.holder.signatureAlgorithm", "ECDH-ES");
        // no additional trust anchors: the test config points at a certificate that is not part of the repository
        configuration.put("oid4vp.trustAnchors", List.of());
        configuration.put("oid4vp.credentialsFolder", Files.createDirectories(tempDir.resolve(CREDENTIALS_FOLDER)).toString());
        return configuration;
    }

    private Path writeKey() throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE_P256));
        KeyPair keyPair = generator.generateKeyPair();
        String pem = KEY_HEADER + System.lineSeparator()
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + System.lineSeparator()
                + KEY_FOOTER + System.lineSeparator();
        Path keyFile = tempDir.resolve(KEY_FILE);
        Files.writeString(keyFile, pem);
        return keyFile;
    }
}
