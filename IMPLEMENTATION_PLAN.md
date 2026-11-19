# Implementation Plan: [CM] Typed-scope requests send scopeType=DATASPACE_MEMBERSHIP, which the TIL rejects, so CM treats the TIL as too old

## Overview
The generated TIL client (`org.fiware.iam.til.api.IssuerApiClient`, kokuwa `micronaut-openapi-codegen`) binds the
`@QueryValue("scopeType") ScopeTypeVO` parameter through Micronaut's conversion service. The generated `ScopeTypeVO`
enum does not override `toString()`, so the client sends `name()` (`DATASPACE_MEMBERSHIP`) instead of the API value
(`dataspace-membership`). TIL 0.10.3 answers `400 Unknown scope type`. As a result `probeTypedScopes` returns TOO_OLD,
and every typed read and write fails. The fix registers a `TypeConverter<ScopeTypeVO, String>` that returns `getValue()`.
A wire-level test against a fake TIL checks the fix, and the k3s ITs move to TIL 0.10.3 to check the acceptance criteria.

Facts checked while planning:
- `ScopeTypeVO` is the only enum used as a query or path parameter in any generated client. All four uses are in
  `IssuerApiClient`: `createTrustedIssuer`, `deleteCredentialsByScope`, `getIssuers` and `replaceCredentialsByScope`.
  No path parameter uses it. JSON bodies already serialise correctly through `@JsonValue getValue()`.
- Generated sources are written to `target/generated-sources/openapi` by `mvn generate-sources`. Do not edit them.
- The k3s manifests `src/test/resources/k3s/til.yaml` and `til-provider.yaml` still use `quay.io/fiware/trusted-issuers-list:0.9.1`.

## Steps

### Step 1: Serialise ScopeTypeVO by its API value, with converter and wire-level tests
Changes:
- Add `src/main/java/org/fiware/iam/til/ScopeTypeConverter.java`: a `@Singleton` class implementing
  `io.micronaut.core.convert.TypeConverter<ScopeTypeVO, String>` whose `convert` returns `Optional.of(value.getValue())`.
  Give it a one-line class Javadoc saying why it exists (the generated enum's `toString()` is the Java name, but the TIL
  only accepts the API value). Micronaut registers `TypeConverter` beans in the context's conversion service
  automatically. The more specific source type wins over the generic `Object -> String` and `Enum` converters.
- If the `String` target is not picked up for `@QueryValue` binding (the client may ask for `CharSequence`), also
  register `TypeConverter<ScopeTypeVO, CharSequence>`, or use `TypeConverterRegistrar`. The wire test below decides
  this, so do not guess.
- Leave `TrustedIssuersListAdapter` unchanged unless the wire test shows a different problem.

Tests:
- `src/test/java/org/fiware/iam/til/ScopeTypeConverterTest.java`: a parameterized unit test (`@MethodSource` over
  `ScopeTypeVO.values()`) asserting that the converter returns `getValue()`. Add one `@MicronautTest` case asserting that
  the application `ConversionService.convert(ScopeTypeVO.DATASPACE_MEMBERSHIP, String.class)` yields `dataspace-membership`.
- `src/test/java/org/fiware/iam/til/TrustedIssuersListAdapterHttpTest.java`: follow the pattern of
  `dataspace/TrustedIssuersRegistryReaderHttpTest`, which uses `TestPropertyProvider`, a free port, `spec.name`, and a
  fake `@Controller` guarded by `@Requires(property = "spec.name", value = SPEC_NAME)`. Point
  `micronaut.http.services.trusted-issuers-list.url` at the embedded server. The fake TIL must behave like 0.10.3:
  - Record the raw `scopeType` query value it receives (bind it as a `String`).
  - Answer any `scopeType` other than `dataspace-membership` with `400` problem details `Unknown scope type`.
  - `GET /issuer` with `scopeType` but no `scope`: answer `400` with a detail containing
    `TrustedIssuersListAdapter.SCOPE_TYPE_WITHOUT_SCOPE`, which is the message `classify` expects.
  - `GET /issuer?scope=..&scopeType=dataspace-membership`: return a one-page `TrustedIssuersListResponseVO`-shaped body.
  - `PUT /issuer/{did}/credential?scope=..&scopeType=..` and `DELETE /issuer/{did}/credential?scope=..&scopeType=..`:
    return 2xx. These paths come from the `PATH_*` constants in the generated `IssuerApiClient`.
  - Run the cases as a parameterized test where practical, and assert both the outcome and the recorded wire value
    `dataspace-membership`:
    - `probeTypedScopes()` returns `TilCapability.State.SUPPORTED`.
    - `issuersGrantedBy(scope, DATASPACE_MEMBERSHIP_SCOPE_TYPE)` returns the fake's issuers.
    - `grantMembership(did, publisherId, credentials)` succeeds.
    - `revokeGrant(did, scope, DATASPACE_MEMBERSHIP_SCOPE_TYPE)` succeeds.
  - Add a negative control: with an untyped call (`type == null`) no `scopeType` query parameter is sent.
- Run `mvn test` and make sure the whole unit test suite passes, including the existing mock-based
  `TrustedIssuersListAdapterTest`.

Docs: add a short bullet to the `## Key Conventions` section of `AGENTS.md`. It says that generated client enums
in query or path parameters need a `TypeConverter` to their API value, with `til/ScopeTypeConverter` as the example.

Acceptance: the new tests fail without the converter (wire value `DATASPACE_MEMBERSHIP`, so the probe returns TOO_OLD)
and pass with it. `mvn test` is green.

### Step 2: Run the k3s integration tests against TIL 0.10.3 and assert typed-scope support end to end
Changes:
- Bump `image: quay.io/fiware/trusted-issuers-list:0.9.1` to `0.10.3` in `src/test/resources/k3s/til.yaml` and
  `src/test/resources/k3s/til-provider.yaml`. Check whether 0.10.3 needs new env or config, such as a DB migration flag,
  and adjust the manifests if it does.
- Extend `src/test/java/org/fiware/iam/InContainerDataSpaceEventsIT.java`, or the most fitting `InContainer*IT`, so that:
  - The CM health endpoint on the management port reports `UP`, and the typed-scopes capability reads supported, not
    `too_old`.
  - With the data-space mode that writes membership (`dataspace`, `granted` or `full`; see `DataSpaceEventsProperties`
    and `ParticipantRemovedStartupGuard`), CM starts. A participant added through the existing ConfigMap and watch flow
    shows up at the TIL as a credential entry with `scopeType=dataspace-membership`. Removing that participant deletes
    the entry. Reuse the existing helpers such as `grantedOrigin(...)` and the Awaitility patterns. Do not add new
    infrastructure.
- If the CM ConfigMap in `src/test/resources/k3s/contract-management-provider.yaml` needs a mode switch for this, make
  it, and keep the existing ITs passing.
- Run `mvn verify` (k3s ITs). If the container environment cannot run k3s, run at least `mvn test` and the
  manifest-independent parts, and say so explicitly in the completion summary.

Acceptance: against TIL 0.10.3 the typed-scopes probe reads supported, and health is UP. Data-space mode starts and
writes and deletes `dataspace-membership` entries. All existing ITs pass.
