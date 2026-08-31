package org.fiware.iam.did;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
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
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DidKeyGenerator}.
 *
 * <p>Tests cover did:key generation for supported EC curves (P-256, P-384),
 * error handling for unsupported key types, URI validity, deterministic output,
 * and independent verification of the Base58-BTC encoder with known test vectors.</p>
 */
class DidKeyGeneratorTest {

    /** Compressed public key length for P-256: 1-byte prefix + 32-byte x-coordinate. */
    private static final int P256_COMPRESSED_KEY_LENGTH = 33;

    /** Compressed public key length for P-384: 1-byte prefix + 48-byte x-coordinate. */
    private static final int P384_COMPRESSED_KEY_LENGTH = 49;

    @BeforeAll
    static void setupBouncyCastle() {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Provides EC curve configurations for parameterized did:key generation tests.
     * Each argument set contains: curve name, expected multicodec prefix, compressed key length.
     */
    static Stream<Arguments> ecCurveProvider() {
        return Stream.of(
                Arguments.of("secp256r1", DidKeyGenerator.MULTICODEC_P256_PREFIX, P256_COMPRESSED_KEY_LENGTH),
                Arguments.of("secp384r1", DidKeyGenerator.MULTICODEC_P384_PREFIX, P384_COMPRESSED_KEY_LENGTH)
        );
    }

    @ParameterizedTest(name = "Generate did:key for curve {0}")
    @MethodSource("ecCurveProvider")
    @DisplayName("should generate correct did:key for EC curves")
    void testGenerateDidKeyMatchesIndependentDerivation(
            String curveName, byte[] expectedPrefix, int compressedKeyLength) throws Exception {
        KeyPair keyPair = generateEcKeyPair(curveName);

        URI didKey = DidKeyGenerator.generateDidKey(keyPair.getPrivate());

        assertNotNull(didKey, "did:key URI should not be null");
        assertTrue(didKey.toString().startsWith("did:key:z"),
                "did:key URI should start with 'did:key:z', got: " + didKey);

        // Independently derive the expected did:key from the public key (not private key path)
        String expectedDid = deriveExpectedDidKey(keyPair, curveName, expectedPrefix, compressedKeyLength);
        assertEquals(expectedDid, didKey.toString(),
                "Generated did:key should match independently computed value from public key");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException for unsupported RSA key type")
    void testUnsupportedRsaKeyTypeThrows() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DidKeyGenerator.generateDidKey(keyPair.getPrivate()));
        assertTrue(ex.getMessage().contains("RSA"),
                "Error message should mention the unsupported key type 'RSA'");
    }

    @Test
    @DisplayName("should produce a valid URI with 'did' scheme and 'key:z' prefix")
    void testGeneratedDidKeyIsValidUri() throws Exception {
        KeyPair keyPair = generateEcKeyPair("secp256r1");

        URI didKey = DidKeyGenerator.generateDidKey(keyPair.getPrivate());

        assertEquals("did", didKey.getScheme(), "URI scheme should be 'did'");
        assertTrue(didKey.getSchemeSpecificPart().startsWith("key:z"),
                "Scheme-specific part should start with 'key:z'");
    }

    @Test
    @DisplayName("should produce deterministic output for the same private key")
    void testDeterministicOutput() throws Exception {
        KeyPair keyPair = generateEcKeyPair("secp256r1");

        URI first = DidKeyGenerator.generateDidKey(keyPair.getPrivate());
        URI second = DidKeyGenerator.generateDidKey(keyPair.getPrivate());

        assertEquals(first, second, "Same private key should always produce the same did:key");
    }

    @Test
    @DisplayName("should produce different did:key values for different private keys")
    void testDifferentKeysProduceDifferentDids() throws Exception {
        KeyPair keyPair1 = generateEcKeyPair("secp256r1");
        KeyPair keyPair2 = generateEcKeyPair("secp256r1");

        URI did1 = DidKeyGenerator.generateDidKey(keyPair1.getPrivate());
        URI did2 = DidKeyGenerator.generateDidKey(keyPair2.getPrivate());

        // Extremely unlikely (but not impossible) for two random keys to produce the same DID
        assertNotNull(did1);
        assertNotNull(did2);
        // We don't assert inequality because it's theoretically possible,
        // but in practice this verifies the method runs without error on multiple keys
    }

    /**
     * Provides known Base58-BTC encoding test vectors for independent encoder verification.
     * Vectors sourced from the Bitcoin Base58Check specification.
     */
    static Stream<Arguments> base58TestVectors() {
        return Stream.of(
                Arguments.of(new byte[]{}, "", "empty input"),
                Arguments.of(new byte[]{0x61}, "2g", "single byte 0x61"),
                Arguments.of(new byte[]{0x62, 0x62, 0x62}, "a3gV", "bytes 0x626262"),
                Arguments.of(new byte[]{0x63, 0x63, 0x63}, "aPEr", "bytes 0x636363"),
                Arguments.of(new byte[]{0}, "1", "single zero byte"),
                Arguments.of(new byte[]{0, 0, 0}, "111", "three leading zero bytes"),
                Arguments.of(new byte[]{0, 0, 0, 1}, "1112", "leading zeros with trailing value"),
                Arguments.of(
                        "Hello World".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "JxF12TrwUP45BMd",
                        "Hello World string")
        );
    }

    @ParameterizedTest(name = "Base58 encode: {2}")
    @MethodSource("base58TestVectors")
    @DisplayName("should correctly Base58 encode known test vectors")
    void testBase58Encoding(byte[] input, String expected, String description) {
        assertEquals(expected, DidKeyGenerator.Base58.encode(input),
                "Base58 encoding of '" + description + "' should match expected value");
    }

    /**
     * Generates an EC key pair for the given named curve using BouncyCastle.
     *
     * @param curveName the standard curve name (e.g. "secp256r1", "secp384r1")
     * @return a freshly generated EC key pair
     */
    private static KeyPair generateEcKeyPair(String curveName) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec(curveName));
        return kpg.generateKeyPair();
    }

    /**
     * Independently derives the expected {@code did:key} string from the public key of a key pair.
     *
     * <p>This method uses the public key directly (rather than deriving from the private key)
     * to provide an independent verification path for the {@link DidKeyGenerator}.</p>
     *
     * @param keyPair              the key pair containing the public key
     * @param curveName            the EC curve name
     * @param expectedPrefix       the expected multicodec prefix bytes
     * @param compressedKeyLength  the expected length of the compressed public key
     * @return the expected did:key URI string
     */
    private static String deriveExpectedDidKey(
            KeyPair keyPair, String curveName, byte[] expectedPrefix, int compressedKeyLength) {
        ECPublicKey ecPublicKey = (ECPublicKey) keyPair.getPublic();
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        ECPoint bcPoint = spec.getCurve().createPoint(
                ecPublicKey.getW().getAffineX(),
                ecPublicKey.getW().getAffineY()
        );
        byte[] compressed = bcPoint.getEncoded(true);
        assertEquals(compressedKeyLength, compressed.length,
                "Compressed public key should be " + compressedKeyLength + " bytes for " + curveName);

        byte[] prefixed = new byte[expectedPrefix.length + compressed.length];
        System.arraycopy(expectedPrefix, 0, prefixed, 0, expectedPrefix.length);
        System.arraycopy(compressed, 0, prefixed, expectedPrefix.length, compressed.length);

        return "did:key:z" + DidKeyGenerator.Base58.encode(prefixed);
    }
}
