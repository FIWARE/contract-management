# Implementation Plan: Generate holderId from public key

## Overview
Make the `holderId` field optional in the `Holder` record within `Oid4VpConfiguration`. When `holderId` is not provided, automatically generate a `did:key` DID (per the [W3C did:key specification](https://w3c-ccg.github.io/did-key-spec/)) from the public key derived from the holder's private key. This simplifies configuration by removing the need to manually compute and specify the `did:key` identifier.

## Steps

### Step 1: Create DidKeyGenerator utility class with unit tests

Create a new utility class that generates a `did:key` DID from a Java `PrivateKey`. This step is self-contained and introduces no changes to existing code.

**New file:** `src/main/java/org/fiware/iam/did/DidKeyGenerator.java`

The class must:
- Accept a `java.security.PrivateKey` and derive the corresponding public key using BouncyCastle.
  - For EC keys (`ECPrivateKey`): use the curve's generator point and the private scalar (`d`) to compute the public point, then produce the compressed EC point encoding (33 bytes for P-256, 49 bytes for P-384).
  - For Ed25519 keys: extract the raw 32-byte public key from the private key info.
- Prepend the correct [multicodec](https://github.com/multiformats/multicodec) varint prefix to the compressed public key bytes:
  - P-256 (`secp256r1`): `0x80, 0x24` (multicodec `0x1200`)
  - P-384 (`secp384r1`): `0x81, 0x24` (multicodec `0x1201`)
  - Ed25519: `0xed, 0x01` (multicodec `0xed`)
- Encode the prefixed bytes using Base58-BTC encoding (implement a small static `Base58` encoder — approximately 25 lines — since no Base58 library is currently in the dependency tree; avoid adding a new dependency for this).
- Prepend the multibase prefix `z` (indicating base58btc) and the `did:key:` scheme to form the final DID string.
- Return the result as a `java.net.URI`.
- Throw `IllegalArgumentException` with a clear message for unsupported key types or curves.
- Document all public methods with Javadoc.
- Define named constants for multicodec prefixes (e.g., `MULTICODEC_P256_PREFIX`, `MULTICODEC_P384_PREFIX`, `MULTICODEC_ED25519_PREFIX`).

**New file:** `src/test/java/org/fiware/iam/did/DidKeyGeneratorTest.java`

Unit tests must:
- Use JUnit 5 parameterized tests (`@ParameterizedTest` with `@MethodSource` or `@CsvSource`) to verify did:key generation for multiple key types.
- Test with an EC P-256 private key and verify the generated `did:key` matches the expected value (use the test config's existing DID `did:key:zDnaehXH4gDLjLeWcACPyQX9TnvsKiQNt6KT7fdsfyW6fhEYA` as a reference if the corresponding key is available, or generate a known test key pair).
- Test with an EC P-384 private key.
- Test that unsupported key types throw `IllegalArgumentException`.
- Test that the output is a valid URI with the `did:key:z` prefix.
- Test the Base58 encoder independently with known input/output vectors.

**Acceptance criteria:**
- `DidKeyGenerator.generateDidKey(PrivateKey)` returns a correct `did:key` URI for EC P-256 and P-384 keys.
- All unit tests pass via `mvn test -Dtest=DidKeyGeneratorTest`.
- No changes to existing source files in this step.

---

### Step 2: Make holderId optional in Holder record and integrate DidKeyGenerator

Modify the configuration and bean factory to support optional `holderId` with automatic `did:key` generation.

**File:** `src/main/java/org/fiware/iam/configuration/Oid4VpConfiguration.java`

- Change the `Holder` record's `holderId` parameter from `URI holderId` to `@Nullable URI holderId`.
- The `@Nullable` annotation is already imported in this file (`io.micronaut.core.annotation.Nullable`).
- No validation logic is needed in the record — the fallback is handled in the bean factory.

**File:** `src/main/java/org/fiware/iam/bean/Oid4VpBeanFactory.java`

In the `oid4VPClient()` method, after loading the private key (line 82), add logic to resolve the holder ID:
- If `oid4VpConfiguration.getHolder().holderId()` is not null, use it as-is (existing behavior).
- If it is null, call `DidKeyGenerator.generateDidKey(privateKey)` to generate the `did:key` URI.
- Use the resolved URI for both the `holderId` (URI) and `kid` (String) parameters of `HolderConfiguration`.
- Add an `import` for `DidKeyGenerator` and `java.net.URI`.
- Add a log message (at INFO level) when a `did:key` is auto-generated, e.g.: `"No holderId configured, generated did:key from holder public key: {}"`.

**File:** `src/main/resources/application.yaml`

- Update the commented-out OID4VP configuration example to show that `holderId` is optional:
  ```yaml
  #  holder:
  #    # holderId is optional. If omitted, a did:key is generated from the public key.
  #    # holderId: "did:key:zDnaehXH4gDLjLeWcACPyQX9TnvsKiQNt6KT7fdsfyW6fhEYA"
  #    keyType: "EC"
  #    keyPath: "oid4vp/private-key.pem"
  #    signatureAlgorithm: "ECDH-ES"
  ```

**Acceptance criteria:**
- When `holderId` is provided in config, behavior is identical to before (no regression).
- When `holderId` is omitted from config, a `did:key` is generated from the private key and used.
- The application compiles and existing tests still pass: `mvn test`.

---

### Step 3: Add tests for the integrated holderId resolution

Add focused tests that verify the full integration of optional `holderId` with `did:key` generation in the bean factory.

**New file:** `src/test/java/org/fiware/iam/bean/Oid4VpBeanFactoryTest.java`

This unit test class must:
- Test that when a `Holder` is configured with an explicit `holderId`, the `HolderConfiguration` receives that exact URI and string.
- Test that when a `Holder` is configured with a null `holderId`, the bean factory generates a `did:key` URI from the private key and passes it to `HolderConfiguration`.
- Use Mockito to mock `CertReader` (return a known test private key) and `Oid4VpConfiguration` (return `Holder` records with and without `holderId`).
- Verify the generated `did:key` matches the expected value for the test key.

**File:** `src/test/resources/application.yaml`

- Optionally remove the `holderId` line from the test OID4VP configuration to exercise the auto-generation path in integration tests, OR add a second test profile (e.g., `application-no-holder-id.yaml`) that omits `holderId`. The choice depends on whether removing it would break existing integration tests — if it does, use a separate profile.

**Acceptance criteria:**
- `Oid4VpBeanFactoryTest` passes with both explicit and auto-generated `holderId` scenarios.
- Existing integration tests (`mvn verify`) continue to pass.
- Both configuration paths (with and without `holderId`) are covered by tests.
