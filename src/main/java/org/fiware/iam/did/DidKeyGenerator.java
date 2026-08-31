package org.fiware.iam.did;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.net.URI;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;

/**
 * Generates {@code did:key} DIDs from Java {@link PrivateKey} objects following the
 * <a href="https://w3c-ccg.github.io/did-key-spec/">W3C did:key specification</a>.
 *
 * <p>The generation process:</p>
 * <ol>
 *   <li>Derive the public key from the private key scalar and curve generator point.</li>
 *   <li>Compress the public key to its canonical compressed EC point encoding.</li>
 *   <li>Prepend the appropriate multicodec unsigned varint prefix.</li>
 *   <li>Base58-BTC encode the prefixed bytes.</li>
 *   <li>Prepend the multibase {@code z} prefix and the {@code did:key:} scheme.</li>
 * </ol>
 *
 * <p>Supported key types:</p>
 * <ul>
 *   <li>EC P-256 (secp256r1)</li>
 *   <li>EC P-384 (secp384r1)</li>
 * </ul>
 */
public final class DidKeyGenerator {

    /** The {@code did:key} URI scheme prefix. */
    private static final String DID_KEY_PREFIX = "did:key:";

    /** Multibase prefix character for Base58-BTC encoding. */
    private static final char MULTIBASE_BASE58BTC_PREFIX = 'z';

    /** Curve name constant for P-256 (secp256r1). */
    private static final String CURVE_P256 = "secp256r1";

    /** Curve name constant for P-384 (secp384r1). */
    private static final String CURVE_P384 = "secp384r1";

    /** Multicodec unsigned varint prefix for P-256 (secp256r1) public keys (code 0x1200). */
    static final byte[] MULTICODEC_P256_PREFIX = {(byte) 0x80, (byte) 0x24};

    /** Multicodec unsigned varint prefix for P-384 (secp384r1) public keys (code 0x1201). */
    static final byte[] MULTICODEC_P384_PREFIX = {(byte) 0x81, (byte) 0x24};

    /** Multicodec unsigned varint prefix for Ed25519 public keys (code 0xED). */
    static final byte[] MULTICODEC_ED25519_PREFIX = {(byte) 0xED, (byte) 0x01};

    /** The order of the P-256 curve, used for curve identification. */
    private static final BigInteger P256_ORDER =
            ECNamedCurveTable.getParameterSpec(CURVE_P256).getN();

    /** The order of the P-384 curve, used for curve identification. */
    private static final BigInteger P384_ORDER =
            ECNamedCurveTable.getParameterSpec(CURVE_P384).getN();

    /** Error message template for unsupported key types. */
    private static final String UNSUPPORTED_KEY_TYPE_MESSAGE =
            "Unsupported key type: %s. Supported types: EC (P-256, P-384).";

    /** Error message for unsupported EC curves. */
    private static final String UNSUPPORTED_CURVE_MESSAGE =
            "Unsupported EC curve. Supported curves: secp256r1 (P-256), secp384r1 (P-384).";

    private DidKeyGenerator() {
        // Utility class - not instantiable
    }

    /**
     * Generates a {@code did:key} URI from the given private key.
     *
     * <p>The method derives the public key from the private key, applies the
     * appropriate multicodec prefix, Base58-BTC encodes the result with the
     * multibase {@code z} prefix, and returns the full {@code did:key} URI.</p>
     *
     * @param privateKey the private key (EC P-256 or P-384)
     * @return the generated {@code did:key} URI
     * @throws IllegalArgumentException if the key type or curve is not supported
     */
    public static URI generateDidKey(PrivateKey privateKey) {
        if (privateKey instanceof ECPrivateKey ecPrivateKey) {
            return generateEcDidKey(ecPrivateKey);
        }
        throw new IllegalArgumentException(
                String.format(UNSUPPORTED_KEY_TYPE_MESSAGE, privateKey.getAlgorithm()));
    }

    /**
     * Generates a {@code did:key} URI from an EC private key by deriving the
     * compressed public key point, prepending the multicodec prefix, and encoding.
     */
    private static URI generateEcDidKey(ECPrivateKey ecPrivateKey) {
        String curveName = identifyCurve(ecPrivateKey);
        byte[] multicodecPrefix = getMulticodecPrefix(curveName);
        byte[] compressedPublicKey = deriveCompressedPublicKey(ecPrivateKey, curveName);

        byte[] prefixedKey = new byte[multicodecPrefix.length + compressedPublicKey.length];
        System.arraycopy(multicodecPrefix, 0, prefixedKey, 0, multicodecPrefix.length);
        System.arraycopy(compressedPublicKey, 0, prefixedKey, multicodecPrefix.length, compressedPublicKey.length);

        String encoded = Base58.encode(prefixedKey);
        return URI.create(DID_KEY_PREFIX + MULTIBASE_BASE58BTC_PREFIX + encoded);
    }

    /**
     * Identifies the EC curve by comparing the key's curve order against known NIST curves.
     *
     * @param ecPrivateKey the EC private key to identify
     * @return the standard curve name (e.g. "secp256r1")
     * @throws IllegalArgumentException if the curve is not supported
     */
    private static String identifyCurve(ECPrivateKey ecPrivateKey) {
        BigInteger order = ecPrivateKey.getParams().getOrder();
        if (P256_ORDER.equals(order)) {
            return CURVE_P256;
        } else if (P384_ORDER.equals(order)) {
            return CURVE_P384;
        }
        throw new IllegalArgumentException(UNSUPPORTED_CURVE_MESSAGE);
    }

    /**
     * Returns the multicodec unsigned varint prefix bytes for the given curve name.
     */
    private static byte[] getMulticodecPrefix(String curveName) {
        return switch (curveName) {
            case CURVE_P256 -> MULTICODEC_P256_PREFIX;
            case CURVE_P384 -> MULTICODEC_P384_PREFIX;
            default -> throw new IllegalArgumentException(UNSUPPORTED_CURVE_MESSAGE);
        };
    }

    /**
     * Derives the compressed public key bytes from an EC private key.
     *
     * <p>Uses the curve's generator point {@code G} and the private scalar {@code d}
     * to compute the public point {@code Q = d * G}, then returns its compressed encoding.</p>
     */
    private static byte[] deriveCompressedPublicKey(ECPrivateKey ecPrivateKey, String curveName) {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        BigInteger d = ecPrivateKey.getS();
        ECPoint publicPoint = spec.getG().multiply(d).normalize();
        return publicPoint.getEncoded(true);
    }

    /**
     * Base58-BTC encoder using the Bitcoin alphabet.
     *
     * <p>Implements the standard Base58 encoding as used by Bitcoin and the
     * <a href="https://github.com/multiformats/multibase">multibase</a> specification
     * for the {@code z} (base58btc) prefix. The alphabet excludes characters
     * {@code 0}, {@code O}, {@code I}, and {@code l} to avoid visual ambiguity.</p>
     */
    static final class Base58 {

        /** The Base58 Bitcoin alphabet. */
        private static final String ALPHABET =
                "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

        /** Base value for the encoding. */
        private static final BigInteger BASE = BigInteger.valueOf(58);

        private Base58() {
            // Utility class - not instantiable
        }

        /**
         * Encodes the given byte array using Base58-BTC encoding.
         *
         * <p>Leading zero bytes in the input are preserved as leading {@code '1'}
         * characters in the output, matching the Bitcoin Base58Check convention.</p>
         *
         * @param input the bytes to encode (must not be null)
         * @return the Base58-BTC encoded string, or an empty string for empty input
         */
        static String encode(byte[] input) {
            if (input.length == 0) {
                return "";
            }

            // Count leading zero bytes - each maps to a '1' in the output
            int leadingZeros = 0;
            while (leadingZeros < input.length && input[leadingZeros] == 0) {
                leadingZeros++;
            }

            // Convert the byte array to a BigInteger and repeatedly divide by 58
            BigInteger value = new BigInteger(1, input);
            StringBuilder sb = new StringBuilder();
            while (value.compareTo(BigInteger.ZERO) > 0) {
                BigInteger[] divmod = value.divideAndRemainder(BASE);
                value = divmod[0];
                sb.append(ALPHABET.charAt(divmod[1].intValue()));
            }

            // Prepend '1' characters for each leading zero byte
            for (int i = 0; i < leadingZeros; i++) {
                sb.append('1');
            }

            return sb.reverse().toString();
        }
    }
}
