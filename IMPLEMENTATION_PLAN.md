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

### Step 2: Run the k3s integration tests against TIL 0.10.3 and drive adoption and removal end to end
Why the current k3s setup cannot catch the bug: `contract-management-provider.yaml` has no publisher whose `types`
include `org.fiware.dataspace.participant.removed`. `DataSpaceEventsProperties.participantRemovedEnabled()` is therefore
false, and none of the `ParticipantRemovedCondition` beans exist: `TilCapabilities` probes,
`ParticipantRemovedHealthIndicator`, `ParticipantRemovedStartupGuard`, `ParticipantAdoption`, `ParticipantRemoval`,
`ParticipantReconciler` and `PublisherRegistryLookup`. Health is `UP` today with the bug in place. Note that
`general.enableTrustedIssuersList` only gates `TilProductOrderHandler`, not these beans, so it can stay `false`.
`micronaut.http.services.trusted-issuers-list.url` already points at `trusted-issuers-list-provider:7085`.

Code paths that write and delete `dataspace-membership` entries (the only ones):
- Write: `ParticipantReconciler` (sweep) -> `ParticipantAdoption.adopt` -> `TrustedIssuersListAdapter.grantMembership`.
  The sweep runs only when adoption is allowed (`DataSpaceEventsProperties.adoption()`): exactly one removal publisher,
  with `on-participant-removed.adopt-unattributed: true` and a `registry-url`, and `dataspace.events.dataspace-id` set.
  It adopts a DID that the publisher's registry lists (`TrustedIssuersRegistryReader.enumerate(registryUrl)`, TIR API
  `GET /v4/issuers`) and whose provider-TIL entry holds nothing but unattributed credentials (no `scope`).
- Delete: a `participant.removed` event from that publisher -> `ParticipantRemovedHandler` -> `ParticipantRemoval`.
  In a writing mode this first asks `PublisherRegistryLookup.isListed` (`GET /v4/issuers/{did}` on the `registry-url`).
  A DID that is still listed is answered `STILL_LISTED` and left alone. Otherwise `RemovalPlan.RevokeGrant` ->
  `adapter.revokeGrant(did, <publisher id>, dataspace-membership)`. The sweep also revokes grants of DIDs the registry
  no longer lists (`issuersGrantedBy`), so the test must accept either path having done the delete.
- A writing mode without `registry-url` is clamped to REPORT, and adoption is refused with NO_REGISTRY_URL. A
  `registry-url` is mandatory for this scenario.

Registry decision: use the consumer-side TIL that is already deployed as the publisher's registry. The FIWARE
trusted-issuers-list serves the TIR API (`/v4/issuers`, `/v4/issuers/{did}`) next to its TIL API (`/issuer`) on the
same port. Set `registry-url: http://trusted-issuers-list:8085` (service from `til.yaml`; the host reaches it on
`localhost:8085`). "Listed" then means "an issuer at the consumer TIL", which the IT controls with `POST /issuer` and
`DELETE /issuer/{did}` on `localhost:8085`. First verify against 0.10.3 that `GET http://localhost:8085/v4/issuers` and
`/v4/issuers/{did}` answer as the TIR API (`TrustedIssuersRegistryReaderHttpTest` and `PublisherRegistryLookupTest`
show the shapes CM expects). Only if 0.10.3 does not serve them may you add a minimal fake registry: one Deployment +
Service in `src/test/resources/k3s/` that serves those two routes from a list the IT can change, with a port binding in
`pom.xml`. Say in the completion summary which option was used and why.

Changes:
- Bump `image: quay.io/fiware/trusted-issuers-list:0.9.1` to `0.10.3` in `src/test/resources/k3s/til.yaml` and
  `til-provider.yaml`. Check whether 0.10.3 needs new env or config, such as a DB migration flag, and adjust the
  manifests if it does. Check that 0.10.3 serves `GET /issuer/{did}/scopes` (curl it for an existing issuer on
  `localhost:7085`). `TilCapabilities.required()` needs SCOPES_ENDPOINT as well as TYPED_SCOPES, and with adoption
  allowed also UNSCOPED. If 0.10.3 lacks one of them, health stays DEGRADED (or startup fails) for a reason unrelated
  to this bug. In that case stop and report `blocked` with the missing capability rather than weakening the assertions.
- In the `contract-management-provider` ConfigMap (static config, so the startup guard runs against it):
  - `endpoints.health.details-visible: ANONYMOUS`. Without it the anonymous IT cannot read health details.
  - `dataspace.events.dataspace-id: <some id>`. Required for adoption. `EventAuthorizer` only rejects events that
    carry a *different* `dataspaceid`, so the existing ITs, which send none, are unaffected.
  - `dataspace.events.participant-removed.reconcile.initial-delay` and `.interval` of a few seconds (e.g. `5s`), so
    the sweep runs within the IT's await window instead of after 1 min / 15 min.
  - A second publisher, e.g. `did:web:anchor.io` with its own `origin`, `types: [org.fiware.dataspace.participant.removed]`
    and `on-participant-removed: {mode: dataspace, adopt-unattributed: true, registry-url: http://trusted-issuers-list:8085}`.
    It must be the *only* publisher listing `participant.removed`, otherwise adoption is refused with MULTIPLE_PUBLISHERS.
    Keep `did:web:publisher-a.io` unchanged. `withPublishers(...)` appends to the list, so the existing tests and
    `restore()` keep working.
  - With the bug, CM *may* fail to start: `ParticipantRemovedStartupGuard` throws only if the provider TIL already
    answers when the `@Context` guard runs, so that typed-scopes reads TOO_OLD while `removalWritesConfigured()`. If the
    TIL is not reachable yet (k3s pod ordering), the probe gives UNVERIFIED, which per the guard's Javadoc only
    degrades; CM starts and typed-scopes turns `too_old` on a later re-probe, so health goes DEGRADED. The guaranteed
    regression signal is therefore the health assertion `typed-scopes: supported` below, not startup. Do not write a
    test that expects a startup failure.
- Extend `src/test/java/org/fiware/iam/InContainerDataSpaceEventsIT.java` (plain `java.net.http` like the existing
  helpers; add small helpers for the TIL calls and the health read; no new framework):
  - Health: `GET http://localhost:9090/health` reports status `UP`. Its `dataspace-participant-removed` component
    detail `til` (see `ParticipantRemovedHealthIndicator.TIL_DETAIL` and `TilCapability` ids) must contain
    `typed-scopes: supported` and `unscoped: supported`. It must also contain `scopes-endpoint`, which may read
    `pending` while the provider TIL is empty and must read `supported` once the test below has seeded an issuer. Await
    these values; the probes run asynchronously after startup. Await `typed-scopes` and `unscoped` = `supported`
    *before* seeding anything (start of the adoption test), so the sweep is known to run when the TIL is seeded.
  - Bound every await that depends on the sweep or a removal by one shared timeout derived from
    `TilCapabilities.PROBE_RETRY_INTERVAL` + the reconcile interval + margin (about 60s with a 5s interval). Reason:
    `ParticipantReconciler.run` and `ParticipantRemoval` call `requireSupported()`, which fails with
    `TIL_CAPABILITY_UNVERIFIED` while any required capability is UNVERIFIED. That happens when the provider TIL was not
    reachable at startup, or after the existing ITs' `restore()` sends a RefreshEvent. An UNVERIFIED capability is
    re-probed at most once per `PROBE_RETRY_INTERVAL` (30s), so a sweep-interval-only await can time out with the fix in
    place. This applies to the `typed-scopes`/`unscoped` health await, the adoption await and the removal await below.
  - Bound the `scopes-endpoint` await explicitly. `ParticipantRemovedHealthIndicator` only reports
    `TilCapabilities.state()` and never probes. A PENDING capability is re-probed only when `requireSupported()` /
    `probeRequired()` runs (the sweep, or a removal), and then at most once per `TilCapabilities.PROBE_RETRY_INTERVAL`
    (30s). After the issuer is seeded, await at least `PROBE_RETRY_INTERVAL` + the reconcile interval + margin (with a
    5s interval, e.g. 60s; the shared timeout above), rather than hard-coding a shorter one; a 10-20s await would be
    flaky. Do this assertion after the adoption step below has seeded the issuer.
  - Adoption writes the grant: pick a unique DID per run (e.g. `did:web:member-<uuid>.io`).
    1. Seed it at the provider TIL (`POST localhost:7085/issuer`) with one credential and no `scope` (unattributed).
    2. List it at the registry (`POST localhost:8085/issuer`).
    3. Await (Awaitility, the shared timeout above) that `GET localhost:7085/issuer?scope=did:web:anchor.io&scopeType=dataspace-membership`
       lists the DID, and that `GET localhost:7085/issuer/{did}` holds the credential with `scope` = the anchor id
       and `scopeType` = `dataspace-membership`, and no unattributed copy.
  - Removal deletes the grant:
    1. Delete the DID from the registry (`DELETE localhost:8085/issuer/{did}`), so `isListed` is false.
    2. POST a `participant.removed` CloudEvent from the anchor to `EVENTS_URL` (identity header =
       `userinfo("did:web:anchor.io")`, `source` = anchor id, `subject` = the DID, `type` =
       `DataSpaceEventsProperties.PARTICIPANT_REMOVED_TYPE`, `dataspaceid` = the configured id). Assert `202`.
    3. Await (the shared timeout above) that the typed list above no longer contains the DID and the entry holds no
       `dataspace-membership` credential. Either the event or the sweep may have done it; the assertion only checks the outcome.
  - Clean up the DID at both TILs in `@AfterEach`, so reruns and the other ITs sharing the provider TIL are not
    affected. Do not reuse DIDs that other ITs write to `localhost:8085`, because the sweep adopts every listed DID
    whose provider entry is unattributed only.
- Run `mvn verify` (k3s ITs). If the container environment cannot run k3s, run at least `mvn test` and the
  manifest-independent parts, and say so explicitly in the completion summary.

Acceptance: against TIL 0.10.3, CM starts with the writing participant-removed configuration. Health is `UP` with
`typed-scopes` and `unscoped` = `supported`, and `scopes-endpoint` = `supported` once an issuer exists. Adoption
writes a `scope=did:web:anchor.io&scopeType=dataspace-membership` grant, and the `participant.removed` path deletes it.
All existing ITs pass. Without Step 1's converter, the health assertion fails (typed-scopes `too_old`, health
DEGRADED); CM additionally fails to start if the provider TIL already answers when the startup guard runs.
