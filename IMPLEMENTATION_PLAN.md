# Implementation Plan: [CM] Data space event envelope, receiving endpoint, authorization and refreshable publishers

## Overview
Add the receiving half of the data space event channel to contract-management. This means an OpenAPI-first CloudEvents endpoint
(`POST`/`OPTIONS /events`), the authorization that makes it safe, a dispatch registry `DataSpaceEventHandler`, deduplication, and a
`@Refreshable` `dataspace.events.*` configuration that is reloaded through `/refresh` and a file watch, without a restart. No concrete
event handler is part of this ticket; CM-2 (#100) adds the first one.

### Design decisions and assumptions (the design docs in data-space-connector were not reachable while planning)
- **New code** lives in package `org.fiware.iam.dataspace` (config in `org.fiware.iam.configuration`). The generated API goes to
  `org.fiware.iam.events.api` / `org.fiware.iam.events.model` (suffix `VO`).
- **Holder identifier**: the claim of the verified token that carries the participant identifier is configurable as a dot path
  (`dataspace.events.listener.verifier.holder-claim`, default `sub`). If architecture §6 names a different claim, change only the default.
- **Own identifier** (`subject` self-check): `dataspace.events.participant-id`, falling back to `general.did`.
- **Check order** (architecture §6 plus the acceptance criteria):
  1. Verify the bearer (`401` if missing or invalid).
  2. Read the holder identifier (`401` if absent).
  3. Require `source == holder` (`403`).
  4. If no `DataSpaceEventHandler` supports the type: `202` and count it as `unknown_type`.
  5. Require the `(publisher id, type)` pair to be configured (`403`).
  6. Deduplicate on `(source, id)`; a replay gets `202` and is not dispatched.
  7. If `subject == own id`: `202`, log at INFO, count it as `self`, and do not dispatch.
  8. Dispatch the event and answer `202`.

  The acceptance criterion says "a *known* type from an unconfigured publisher gets 403", so the unknown-type branch must sit before
  the pair check. An unknown type does nothing, so placing it there opens no bypass.
- **Dedup cache**: an entry is written only *after* authorization, so an unauthenticated caller cannot burn a legitimate event id. If
  the handler fails, the entry is removed again so the publisher's retry is processed.
- **`dataspaceid`**: optional in the schema. If both the event and `dataspace.events.dataspace-id` carry one and they differ → `403`.
  Otherwise no check.
- **Rejections** are authorization decisions, not downstream failures. They are returned as a decision enum and logged once at WARN
  in the controller (one line with source, id, type and reason); they are not thrown as `ContractManagementException`.
- **Metrics**: a Micrometer counter `dataspace.events.received`, tagged with `type` and `outcome`. The outcomes are `accepted`,
  `duplicate`, `unknown_type`, `self`, `unauthenticated`, `source_mismatch`, `publisher_not_authorized`, `dataspace_mismatch` and
  `handler_failed`. The README already advertises `micronaut.metrics.enabled`, but micrometer is not a dependency yet.

## Steps

### Step 1: OpenAPI spec, code generation and refreshable `dataspace.events.*` configuration
- Add `api/dataspace-events.yaml` (OpenAPI 3.0.3, same style as `api/contract-management.yaml`):
  - `POST /events`, request body `application/cloudevents+json` → schema `DataSpaceEvent`. Required fields: `specversion` (enum
    `1.0`), `id`, `source`, `type`. Optional fields: `subject`, `time` (date-time), `datacontenttype`, `dataschema`, `dataspaceid`,
    `data` (free-form object). Responses: `202`, `400`, `401`, `403`, `415`. Bearer `securitySchemes`.
  - `OPTIONS /events`, header parameter `WebHook-Request-Origin` (optional). Response `200` with headers `Allow`,
    `WebHook-Allowed-Origin`.
- `pom.xml`: add property `<events.api.url>${project.basedir}/api/dataspace-events.yaml</events.api.url>` and an
  `openapi-generator-maven-plugin` execution `events`. Copy the `cm` execution, with `apiPackage` `org.fiware.iam.events.api` and
  `modelPackage` `org.fiware.iam.events.model`.
- Jackson only decodes `application/json` by default. Register the CloudEvents media type in `src/main/resources/application.yaml`
  (`micronaut.codec.json.additional-types: [application/cloudevents+json]`).
- New `org.fiware.iam.configuration.DataSpaceEventsProperties`: a `@Refreshable @ConfigurationProperties("dataspace.events")` Lombok
  `@Data` class (mutable, because refresh rebinds it). Fields:
  - `dataspaceId` (`@Nullable`)
  - `participantId` (`@Nullable`)
  - `listener` with:
    - `enabled` (default false)
    - `verifier` with `jwksUrl`, `@Nullable issuer`, `@Nullable audience`, `holderClaim = "sub"`
    - `dedup` with `maxEntries` and a `ttl` `Duration`, both named constants as defaults (e.g. `10_000`, `PT1H`)
    - `watch` with `enabled` and `path`
  - `publishers`: a list of `Publisher` with `id`, `origin` and `types` (`List<String>`). `id` and `origin` are independent.

  Helper methods: `isAuthorized(publisherId, type)`, `isAllowedOrigin(origin)` and `ownId(GeneralProperties)`. Every reader must
  go through the injected proxy on each request and never copy the publisher list into a singleton field, or refresh has no effect.
- Conditions in the style of `GeneralProperties.CentralMarketplaceCondition`: `DataSpaceEventsProperties.ListenerCondition`
  (`listener.enabled`) and `WatchCondition` (`listener.watch.enabled`).
- Add a commented example `dataspace:` block to `src/main/resources/application.yaml`, and add rows to the README configuration table.
- Tests: `DataSpaceEventsPropertiesTest`, a parameterized test of binding and the helpers (`isAuthorized`/`isAllowedOrigin` over
  configured, unconfigured and type-not-listed cases; `ownId` fallback to `general.did`). `mvn clean package -DskipTests` must
  generate `EventsApi` and `DataSpaceEventVO`.
- **Acceptance:** the build generates the new API, the config binds, and the tests pass. No controller exists yet.

### Step 2: Bearer verification against the verifier JWKS
- New `org.fiware.iam.dataspace.BearerVerifier` (`@Singleton`, `@Requires(condition = ListenerCondition.class)`).
  `Optional<String> holderOf(String authorizationHeader)` strips `Bearer `, then verifies the token with Nimbus
  `DefaultJWTProcessor`:
  - the signature against a `JWKSource` built with `JWKSourceBuilder` (cached, refreshed on an unknown `kid`) from the current
    `verifier.jwksUrl`, re-created when the URL changes after a refresh;
  - `exp`/`nbf`;
  - `iss` and `aud` when configured.

  It returns the value at `holderClaim`. A dot path walks nested map claims, e.g. `verifiableCredential.credentialSubject.id`.
  The result is empty for a missing header, an invalid token or a missing claim, and the reason is logged at DEBUG (the
  controller logs the WARN once).
- Isolate JWK source creation behind a small package-private factory, so that tests can inject an `ImmutableJWKSet`.
- Tests: `BearerVerifierTest`, a `@ParameterizedTest` that signs tokens with a generated EC key. Cases: valid; expired; wrong key;
  unknown `kid`; `alg: none`; wrong `iss`; wrong `aud`; missing holder claim; nested holder claim; missing or malformed header.
- **Acceptance:** only a token signed by a key from the configured JWKS, with valid time claims and a holder claim, produces a
  holder id.

### Step 3: Authorization chain with the parameterized authorization matrix
- New `org.fiware.iam.dataspace.EventDecision` enum (`ACCEPT`, `UNKNOWN_TYPE`, `SELF`, `UNAUTHENTICATED`, `SOURCE_MISMATCH`,
  `PUBLISHER_NOT_AUTHORIZED`, `DATASPACE_MISMATCH`). Each value carries its `HttpStatus` and its metric `outcome` tag, so there are
  no magic strings or numbers in the controller.
- New `org.fiware.iam.dataspace.EventAuthorizer` (`@Singleton`). `EventDecision authorize(Optional<String> holder,
  DataSpaceEventVO event, Predicate<String> typeKnown)` implements checks 1–5 and 7 of the order in the Overview. It is a pure
  function over the refreshable properties, so it can be tested without HTTP. Dedup is not part of it: dedup is state and is added in
  Step 4.
- Tests: `EventAuthorizerTest`, a `@ParameterizedTest` + `@MethodSource` matrix over holder present/absent, source equal/different,
  type known/unknown, publisher configured/unconfigured/configured-for-other-type, subject self/other/absent, and dataspaceid
  equal/different/absent on either side. Expected decisions:
  - configured publisher, known type → `ACCEPT`
  - unknown type → `UNKNOWN_TYPE`
  - known type, unconfigured publisher → `PUBLISHER_NOT_AUTHORIZED`
  - mismatched source → `SOURCE_MISMATCH`, even if the type is unknown
  - self subject → `SELF`
- **Acceptance:** the matrix covers every row of the ticket's acceptance list that does not need HTTP or state.

### Step 4: `EventListener` controller, handler registry, dedup cache and metric
- `pom.xml`: add `io.micronaut.micrometer:micronaut-micrometer-core` and `micronaut-micrometer-registry-prometheus` (versions come
  from the Micronaut platform BOM). Add `com.github.ben-manes.caffeine:caffeine` for the cache (BOM-managed; if it is not, add a
  version property).
- New interface `org.fiware.iam.dataspace.DataSpaceEventHandler`, mirroring `TMForumEventHandler`:
  `boolean isEventTypeSupported(String type)` and `Mono<Void> handleEvent(DataSpaceEventVO event)`.
- New `org.fiware.iam.dataspace.EventDeduplicator` (`@Singleton`). A Caffeine cache bounded by `dedup.maxEntries` with
  `expireAfterWrite(dedup.ttl)`, keyed by the record `(source, id)`. `boolean firstSeen(key)` uses an atomic `putIfAbsent`, and
  `forget(key)` removes the entry after a handler failure.
- New `org.fiware.iam.dataspace.EventListener`: `@Controller("${general.basepath:/}")`, `@Requires(condition = ListenerCondition.class)`,
  implements the generated `EventsApi` POST. It injects `List<DataSpaceEventHandler>`, `BearerVerifier`, `EventAuthorizer`,
  `EventDeduplicator` and `MeterRegistry`. Flow: authorize → on `ACCEPT`/`SELF` dedup (`duplicate` → `202`) → `SELF` logs at INFO
  (`Data space event <id> from <source> is about this participant, not acted on.`) → dispatch → `202`. On handler error: `forget`,
  log the WARN once with `DownstreamError.reason(e)` and the exception attached, and return `500`. Every path increments
  `dataspace.events.received{type,outcome}`. Rejections log one WARN line.
- Tests: `EventListenerTest` as a `@MicronautTest` with `dataspace.events.listener.enabled=true`. A test-only
  `@Requires(env = "test")` controller serves a JWKS, and a test `DataSpaceEventHandler` counts its invocations. Parameterized over
  the acceptance list:
  - configured publisher → `202`, handled once
  - unknown type → `202`, counter +1, not handled
  - known type from an unconfigured publisher → `403`
  - mismatched source → `403`
  - no bearer → `401`
  - replay of the same `(source, id)` → `202`, handled once
  - self subject → `202`, `self` counter +1, not handled
  - handler failure followed by a retry → handled twice
  - `application/json` instead of `application/cloudevents+json` → `415`

  Also check that existing tests stay green while the listener is disabled (the default).
- Update `AGENTS.md`: add `dataspace/` to the structure, and document the micrometer and caffeine dependencies.
- **Acceptance:** all `POST /events` rows of the ticket acceptance pass over HTTP. `mvn test` is green.

### Step 5: `OPTIONS /events` CloudEvents webhook validation
- Implement the `OPTIONS` operation of `EventsApi` in `EventListener`. If the generator does not emit `@Options`, add a hand-written
  `@Options("/events")` method. The response always carries `Allow: POST`. When `WebHook-Request-Origin` matches the `origin` of a
  configured publisher (`isAllowedOrigin`, exact match, read through the refreshable proxy), it also carries
  `WebHook-Allowed-Origin: <that origin>`. Otherwise that header is absent. No bearer is required, because the handshake is
  unauthenticated by spec. Header names are constants.
- Tests: in `EventListenerTest` (or `EventListenerOptionsTest`), a `@ParameterizedTest` over a configured origin, an unconfigured
  origin, a missing header, the origin of a publisher whose `id` differs from that origin, and a case-variant origin.
- **Acceptance:** `OPTIONS /events` grants only configured origins and always returns `Allow: POST`.

### Step 6: Refresh without restart: `/refresh`, ConfigMap file watch and an end-to-end refresh test
- `src/main/resources/application.yaml`: `endpoints.refresh.enabled: true` and `endpoints.refresh.sensitive: false`. Management runs
  on port 9090 and is cluster-internal; without micronaut-security a sensitive endpoint is refused. When the watch is enabled, set
  `micronaut.io.watch.enabled` and `micronaut.io.watch.paths` from `dataspace.events.listener.watch.path`.
- New `org.fiware.iam.dataspace.ConfigFileRefresher`, an `ApplicationEventListener<FileChangedEvent>` with
  `@Requires(condition = WatchCondition.class)`. It publishes a `RefreshEvent` through `ApplicationEventPublisher`. Debounce it with
  a named constant window, because a Kubernetes ConfigMap update is an atomic `..data` symlink swap that emits several events.
- Tests: `DataSpaceEventsRefreshTest`, a surefire test that starts a real `ApplicationContext` + `EmbeddedServer`
  (`micronaut.config.files` points at a temp YAML file; the test JWKS controller from Step 4). Steps:
  1. Publisher B → `403`.
  2. Add B to the file, then `POST http://localhost:<management-port>/refresh`.
  3. B → `202`.
  4. Add publisher C with the watch enabled, without calling `/refresh`.
  5. Awaitility until C → `202`.
  6. Add an origin and check that `OPTIONS` now grants it.

  This test is the acceptance evidence that a publisher added to the config is accepted without a restart. Verify first that
  `RefreshEvent` re-reads `micronaut.config.files`. If it does not, add a `PropertySourceLoader` for the watched file and document why.
- README: add a "Data space events" section with the config keys, the endpoint, the authorization order, the metric, the refresh
  options, and the warning that the ConfigMap must be mounted as a directory, **not** with `subPath`, because kubelet never updates
  `subPath` mounts. The Helm side is DSC-1 (#102).
- **Acceptance:** the refresh test passes for both paths. `mvn test` and `mvn clean package` are green. README and `AGENTS.md`
  describe the feature.
