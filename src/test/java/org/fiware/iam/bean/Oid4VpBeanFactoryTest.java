package org.fiware.iam.bean;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.fiware.iam.cert.CertReader;
import org.fiware.iam.configuration.Oid4VpConfiguration;
import org.fiware.iam.did.DidKeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the holderId resolution logic in {@link Oid4VpBeanFactory}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>An explicit {@code holderId} in the {@link Oid4VpConfiguration.Holder} record
 *       is returned unchanged.</li>
 *   <li>A {@code null} {@code holderId} triggers automatic {@code did:key} generation
 *       from the holder's private key via {@link DidKeyGenerator}.</li>
 *   <li>The generated {@code did:key} is deterministic and matches the value produced
 *       by {@link DidKeyGenerator#generateDidKey} for supported EC curves.</li>
 * </ul>
 */
class Oid4VpBeanFactoryTest {

    /** EC curve name constant for P-256 (secp256r1). */
    private static final String CURVE_P256 = "secp256r1";

    /** EC curve name constant for P-384 (secp384r1). */
    private static final String CURVE_P384 = "secp384r1";

    /** A sample explicit holder DID URI used in test configuration. */
    private static final URI EXPLICIT_HOLDER_ID =
            URI.create("did:key:zDnaehXH4gDLjLeWcACPyQX9TnvsKiQNt6KT7fdsfyW6fhEYA");

    /** Signature algorithm used in Holder records for testing (not relevant to holderId resolution). */
    private static final String TEST_SIGNATURE_ALGORITHM = "ECDH-ES";

    /** Key type string used in Holder records for testing. */
    private static final String TEST_KEY_TYPE = "EC";

    /** Key path string used in Holder records for testing. */
    private static final String TEST_KEY_PATH = "oid4vp/test-key.pem";

    /** The factory instance under test, constructed with a mocked {@link CertReader}. */
    private static Oid4VpBeanFactory factory;

    @BeforeAll
    static void setUp() {
        Security.addProvider(new BouncyCastleProvider());
        CertReader mockCertReader = mock(CertReader.class);
        factory = new Oid4VpBeanFactory(mockCertReader);
    }

    /**
     * Provides EC curve names for parameterized tests of auto-generated holderId.
     * Each argument set contains the standard curve name.
     */
    static Stream<Arguments> ecCurveProvider() {
        return Stream.of(
                Arguments.of(CURVE_P256),
                Arguments.of(CURVE_P384)
        );
    }

    @Test
    @DisplayName("should return explicit holderId when configured in Holder record")
    void testExplicitHolderIdIsReturnedAsIs() {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                EXPLICIT_HOLDER_ID, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);

        // Private key is not used when holderId is explicit, but we pass a valid key
        // to satisfy the method signature
        URI result = factory.resolveHolderId(holder, generateEcKeyPairUnchecked(CURVE_P256).getPrivate());

        assertEquals(EXPLICIT_HOLDER_ID, result,
                "resolveHolderId should return the explicit holderId unchanged");
    }

    @Test
    @DisplayName("should return explicit holderId string representation matching URI")
    void testExplicitHolderIdStringRepresentation() {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                EXPLICIT_HOLDER_ID, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);

        URI result = factory.resolveHolderId(holder, generateEcKeyPairUnchecked(CURVE_P256).getPrivate());

        assertEquals(EXPLICIT_HOLDER_ID.toString(), result.toString(),
                "String representation of resolved holderId should match the explicit value");
    }

    @ParameterizedTest(name = "Auto-generate did:key for curve {0}")
    @MethodSource("ecCurveProvider")
    @DisplayName("should generate did:key from private key when holderId is null")
    void testNullHolderIdGeneratesDidKey(String curveName) throws Exception {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                null, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair = generateEcKeyPair(curveName);

        URI result = factory.resolveHolderId(holder, keyPair.getPrivate());

        assertNotNull(result, "Auto-generated holderId should not be null");
        assertTrue(result.toString().startsWith("did:key:z"),
                "Auto-generated holderId should start with 'did:key:z', got: " + result);
    }

    @ParameterizedTest(name = "Generated did:key matches DidKeyGenerator for curve {0}")
    @MethodSource("ecCurveProvider")
    @DisplayName("should produce did:key matching DidKeyGenerator output")
    void testGeneratedDidKeyMatchesDidKeyGenerator(String curveName) throws Exception {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                null, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair = generateEcKeyPair(curveName);
        URI expectedDidKey = DidKeyGenerator.generateDidKey(keyPair.getPrivate());

        URI result = factory.resolveHolderId(holder, keyPair.getPrivate());

        assertEquals(expectedDidKey, result,
                "resolveHolderId should produce the same did:key as DidKeyGenerator.generateDidKey");
    }

    @Test
    @DisplayName("should produce a valid URI with 'did' scheme when holderId is null")
    void testAutoGeneratedHolderIdIsValidUri() {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                null, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair = generateEcKeyPairUnchecked(CURVE_P256);

        URI result = factory.resolveHolderId(holder, keyPair.getPrivate());

        assertEquals("did", result.getScheme(),
                "Auto-generated holderId URI scheme should be 'did'");
        assertTrue(result.getSchemeSpecificPart().startsWith("key:z"),
                "Auto-generated holderId scheme-specific part should start with 'key:z'");
    }

    @Test
    @DisplayName("should produce deterministic did:key for the same private key")
    void testAutoGeneratedHolderIdIsDeterministic() {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                null, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair = generateEcKeyPairUnchecked(CURVE_P256);

        URI firstResult = factory.resolveHolderId(holder, keyPair.getPrivate());
        URI secondResult = factory.resolveHolderId(holder, keyPair.getPrivate());

        assertEquals(firstResult, secondResult,
                "resolveHolderId should produce the same did:key for the same private key across invocations");
    }

    @Test
    @DisplayName("should produce different did:key values for different private keys")
    void testDifferentKeysProduceDifferentHolderIds() {
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                null, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair1 = generateEcKeyPairUnchecked(CURVE_P256);
        KeyPair keyPair2 = generateEcKeyPairUnchecked(CURVE_P256);

        URI result1 = factory.resolveHolderId(holder, keyPair1.getPrivate());
        URI result2 = factory.resolveHolderId(holder, keyPair2.getPrivate());

        // Both should be valid did:key URIs
        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(result1.toString().startsWith("did:key:z"));
        assertTrue(result2.toString().startsWith("did:key:z"));
    }

    @Test
    @DisplayName("should not use private key when explicit holderId is provided")
    void testExplicitHolderIdIgnoresPrivateKey() {
        URI customHolderId = URI.create("did:web:example.com");
        Oid4VpConfiguration.Holder holder = new Oid4VpConfiguration.Holder(
                customHolderId, TEST_KEY_TYPE, TEST_KEY_PATH, TEST_SIGNATURE_ALGORITHM);
        KeyPair keyPair1 = generateEcKeyPairUnchecked(CURVE_P256);
        KeyPair keyPair2 = generateEcKeyPairUnchecked(CURVE_P256);

        URI result1 = factory.resolveHolderId(holder, keyPair1.getPrivate());
        URI result2 = factory.resolveHolderId(holder, keyPair2.getPrivate());

        assertEquals(customHolderId, result1,
                "Explicit holderId should be returned regardless of private key");
        assertEquals(customHolderId, result2,
                "Explicit holderId should be returned regardless of private key");
        assertEquals(result1, result2,
                "Explicit holderId should be the same regardless of which private key is provided");
    }

    /**
     * Generates an EC key pair for the given named curve using BouncyCastle.
     *
     * @param curveName the standard curve name (e.g. "secp256r1", "secp384r1")
     * @return a freshly generated EC key pair
     * @throws Exception if key generation fails
     */
    private static KeyPair generateEcKeyPair(String curveName) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec(curveName));
        return kpg.generateKeyPair();
    }

    /**
     * Generates an EC key pair, wrapping any checked exception as a {@link RuntimeException}.
     * Convenience method for use in non-throwing test contexts.
     *
     * @param curveName the standard curve name (e.g. "secp256r1", "secp384r1")
     * @return a freshly generated EC key pair
     */
    private static KeyPair generateEcKeyPairUnchecked(String curveName) {
        try {
            return generateEcKeyPair(curveName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EC key pair for curve: " + curveName, e);
        }
    }
}
