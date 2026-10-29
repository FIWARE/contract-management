# Implementation Plan: [CM] Data space event envelope, receiving endpoint, authorization and refreshable publishers

## Overview
Add the receiving half of the data space event channel to contract-management. This means an OpenAPI-first CloudEvents endpoint
(`POST`/`OPTIONS /events`), the authorization that makes it safe, a dispatch registry `DataSpaceEventHandler`, deduplication, and a
refreshable `dataspace.events.*` configuration that is reloaded through `/refresh` and a file watch, without a restart. No concrete
event handler is part of this ticket; CM-2 (#100) adds the first one.

### Design decisions and assumptions (the design docs in data-space-connector were not reachable while planning)
- **New code** lives in package `org.fiware.iam.dataspace` (config in `org.fiware.iam.configuration`). The generated API goes to
  `org.fiware.iam.events.api` / `org.fiware.iam.events.model` (suffix `VO`).
- **Participant id** (the acting organization, not the credential holder): extracting it from the presented credentials is the
  verifier's job; the verifier writes it into its JWT under a claim of our choosing. CM reads that one top-level claim, configurable
  as `dataspace.events.listener.verifier.participant-claim` (default `participantId`; the verifier must be configured to emit it,
  DSC-1 #102). There is no dot-path walking into credential structures. Code and docs say "participant id", never "holder".
- **Own identifier** (`subject` self-check): `dataspace.events.own-participant-id`, falling back to `general.did`.
- **Check order** (architecture §6 plus the acceptance criteria):
  1. Verify the bearer (`401` if missing or invalid).
  2. Read the participant id claim (`401` if absent).
  3. Require `source == participant id` (`403`).
  3a. If both the event and the configuration name a data space and they differ: `403` (`DATASPACE_MISMATCH`). Like the source
      check it does not depend on the type, so it also applies to unknown types.
  4. If no `DataSpaceEventHandler` supports the type: `202` and count it as `unknown_type`.
  5. Require the `(publisher id, type)` pair to be configured (`403`).
  6. Deduplicate on `(source, id)`; a replay gets `202` and is not dispatched.
  7. If `subject == own id`: `202`, log at INFO, count it as `self`, and do not dispatch.
  8. Dispatch the event and answer `202`.

  The acceptance criterion says "a *known* type from an unconfigured publisher gets 403", so the unknown-type branch must sit before
  the pair check. An unknown type does nothing, so placing it there opens no bypass.
- **Dedup cache**: an entry is written only *after* authorization, so an unauthenticated caller cannot burn a legitimate event id.
  An entry is `IN_FLIGHT` while the handler runs and `DONE` afterwards. A retry of a `DONE` event gets `202` (`duplicate`); a retry
  that arrives while the first attempt is still `IN_FLIGHT` gets `503` with `Retry-After` (retryable per the CloudEvents webhook
  spec), so it is not lost if the first attempt then fails. If the handler fails, or the request is cancelled (publisher disconnect
  or timeout), the entry is removed so the next retry is processed; `complete` only turns an existing `IN_FLIGHT` into `DONE`
  (`asMap().replace(key, IN_FLIGHT, DONE)`), so it cannot resurrect an entry that eviction already dropped.
  `dedup.max-entries` and `dedup.ttl` are refreshable without depending on `RefreshEvent` listener order: on each `begin()` the
  deduplicator compares the current values (read through the injected properties bean) with the last values it applied, and on a change
  applies them to the live cache through Caffeine's `policy()` (`eviction().setMaximum`, `expireAfterWrite().setExpiresAfter`),
  keeping the existing entries.
- **`dataspaceid`**: optional in the schema. If both the event and `dataspace.events.dataspace-id` carry one and they differ → `403`.
  Otherwise no check.
- **Rejections** are authorization decisions, not downstream failures. They are returned as a decision enum and logged once at WARN
  in the controller (one line with source, id, type and reason); they are not thrown as `ContractManagementException`.
- **Metrics**: a Micrometer counter `dataspace.events.received`, tagged with `type` and `outcome`. The outcomes are `accepted`,
  `duplicate`, `in_flight`, `unknown_type`, `self`, `unauthenticated`, `source_mismatch`, `publisher_not_authorized`,
  `dataspace_mismatch` and `handler_failed`. The `type` tag carries the event type only if a registered `DataSpaceEventHandler`
  supports it, and the constant `other` otherwise, so callers cannot create unbounded meters/series. The README already advertises
  `micronaut.metrics.enabled`, but micrometer is not a dependency yet.
- **`/refresh` exposure**: `endpoints.refresh.sensitive: false` leaves `POST :9090/refresh` open to anything that reaches the
  management port. The reviewer accepted this; the README states the exposure and recommends keeping 9090 cluster-internal.
- **Startup-only switches**: `listener.enabled` and `listener.watch.enabled` drive startup-time `Condition`s, so they are *not*
  refreshable; turning the listener or the watch on or off needs a restart. Everything else under `dataspace.events.*` is refreshable.
  The README says so.
- **No blocking on the event loop**: Nimbus fetches the JWKS with blocking I/O (on a cache miss and on an unknown `kid`), so bearer
  verification runs on `Schedulers.boundedElastic()` (`Mono.fromCallable(..).subscribeOn(..)`), never on a Netty event-loop thread.

## Steps

### Step 1: OpenAPI spec, code generation and refreshable `dataspace.events.*` configuration
- Add `api/dataspace-events.yaml` (OpenAPI 3.0.3, same style as `api/contract-management.yaml`):
  - `POST /events`, request body `application/cloudevents+json` → schema `DataSpaceEvent`. Required fields: `specversion` (enum
    `1.0`), `id`, `source`, `type`. Optional fields: `subject`, `time` (date-time), `datacontenttype`, `dataschema`, `dataspaceid`,
    `data` (free-form object). Responses: `202`, `400`, `401`, `403`, `415`, `500`, `503` (with `Retry-After`). Bearer
    `securitySchemes`.
  - `OPTIONS /events`, header parameter `WebHook-Request-Origin` (optional). Response `200` with headers `Allow`,
    `WebHook-Allowed-Origin`.
- `pom.xml`: add property `<events.api.url>${project.basedir}/api/dataspace-events.yaml</events.api.url>` and an
  `openapi-generator-maven-plugin` execution `events`. Copy the `cm` execution, with `apiPackage` `org.fiware.iam.events.api` and
  `modelPackage` `org.fiware.iam.events.model`.
- Jackson only decodes `application/json` by default. Register the CloudEvents media type in `src/main/resources/application.yaml`
  (`micronaut.codec.json.additional-types: [application/cloudevents+json]`).
- New `org.fiware.iam.configuration.DataSpaceEventsProperties`: a `@ConfigurationProperties("dataspace.events")` Lombok
  `@Data` class (mutable, because refresh rebinds it). *Plan update (step 1):* no `@Refreshable` — Micronaut generates no scoped
  proxy for a `@ConfigurationProperties` class, but its refresh scope rebinds `@ConfigurationProperties` singletons in place on every
  `RefreshEvent`, which gives the same effect. A key *removed* from the config keeps its previous value (set it empty instead);
  readers treat blank values as unset. Fields:
  - `dataspaceId` (`@Nullable`)
  - `ownParticipantId` (`@Nullable`)
  - `listener` with:
    - `enabled` (default false)
    - `verifier` with `jwksUrl`, `@Nullable issuer`, `audience` (required when the listener is enabled), `participantClaim =
      "participantId"`, and `algorithms` (allowed JWS algorithms, default the named constant set `ES256`, `RS256`)
    - `dedup` with `maxEntries` and a `ttl` `Duration`, both named constants as defaults (e.g. `10_000`, `PT1H`)
    - `watch` with `enabled` and `path`
  - `publishers`: a list of `Publisher` with `id`, `origin` and `types` (`List<String>`). `id` and `origin` are independent.

  Helper methods: `isAuthorized(publisherId, type)`, `allowedOrigin(requestOrigin)` (returns the configured origin, matched
  case-insensitively because it is a DNS name) and `ownId(GeneralProperties)`. Every reader must
  go through the injected bean on each request and never copy the publisher list into a singleton field, or refresh has no effect.
- Conditions in the style of `GeneralProperties.CentralMarketplaceCondition`: `DataSpaceEventsProperties.ListenerCondition`
  (`listener.enabled`) and `WatchCondition` (`listener.watch.enabled`). With the listener enabled, a missing `verifier.audience` or
  `verifier.jwks-url` fails startup rather than silently disabling the listener: `BearerVerifier` is `@Context` (eager, like
  `NotificationSubscriber`; Micronaut singletons are otherwise created on first use), and its `@PostConstruct` throws a
  `ConfigurationException`. A refresh that later blanks either value makes verification fail closed (every request `401`, logged at
  ERROR), since a refresh cannot abort a running service.
- Add a commented example `dataspace:` block to `src/main/resources/application.yaml`, and add rows to the README configuration table.
- Tests: `DataSpaceEventsPropertiesTest`, a parameterized test of binding and the helpers (`isAuthorized`/`allowedOrigin` over
  configured, unconfigured, type-not-listed and case-variant cases; `ownId` fallback to `general.did`). `mvn clean package -DskipTests` must
  generate `EventsApi` and `DataSpaceEventVO`.
- **Acceptance:** the build generates the new API, the config binds, and the tests pass. No controller exists yet.

### Step 2: Bearer verification against the verifier JWKS
- New `org.fiware.iam.dataspace.BearerVerifier` (`@Context`, `@Requires(condition = ListenerCondition.class)`).
  `Optional<String> participantIdOf(String authorizationHeader)` strips `Bearer `, then verifies the token with Nimbus
  `DefaultJWTProcessor`. The method may block (JWKS fetch), which its Javadoc states; callers run it off the event loop (Step 4).
  It checks:
  - the signature against a `JWKSource` built with `JWKSourceBuilder` (cached, refreshed on an unknown `kid`) from the current
    `verifier.jwksUrl`, re-created when the URL changes after a refresh;
  - a `JWSVerificationKeySelector` over the explicit `verifier.algorithms` set, never the algorithms the JWKS advertises;
  - `exp`/`nbf`;
  - `aud` must contain `verifier.audience` (always enforced, so a token the verifier issued for another service is refused);
    `iss` when configured.

  It returns the top-level string claim `participantClaim`. The result is empty for a missing header, an invalid token or a
  missing or non-string claim, and the reason is logged at DEBUG (the controller logs the WARN once).
- Isolate JWK source creation behind a small package-private factory, so that tests can inject an `ImmutableJWKSet`.
- Tests: `BearerVerifierTest`, a `@ParameterizedTest` that signs tokens with a generated EC key. Cases: valid; expired; wrong key;
  unknown `kid`; `alg: none`; an algorithm outside `verifier.algorithms`; wrong `iss`; no `aud`; other `aud`; missing participant
  claim; non-string participant claim; missing or malformed header. Plus a startup test: `ApplicationContext.run(...)` with the
  listener enabled and no `audience` (and, separately, no `jwks-url`) throws, without any `getBean` call.
- **Acceptance:** only a token signed by a key from the configured JWKS with an allowed algorithm, valid time claims, our audience
  and a participant claim produces a participant id.

### Step 3: Authorization chain with the parameterized authorization matrix
- New `org.fiware.iam.dataspace.EventDecision` enum (`ACCEPT`, `UNKNOWN_TYPE`, `SELF`, `UNAUTHENTICATED`, `SOURCE_MISMATCH`,
  `PUBLISHER_NOT_AUTHORIZED`, `DATASPACE_MISMATCH`). Each value carries its `HttpStatus` and its metric `outcome` tag, so there are
  no magic strings or numbers in the controller.
- New `org.fiware.iam.dataspace.EventAuthorizer` (`@Singleton`). `EventDecision authorize(Optional<String> participantId,
  DataSpaceEventVO event, Predicate<String> typeKnown)` implements checks 1–5 and 7 of the order in the Overview. It is a pure
  function over the refreshable properties, so it can be tested without HTTP. Dedup is not part of it: dedup is state and is added in
  Step 4.
- Tests: `EventAuthorizerTest`, a `@ParameterizedTest` + `@MethodSource` matrix over participant id present/absent, source equal/different,
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
  `expireAfterWrite(dedup.ttl)`, keyed by the record `(source, id)`, valued `IN_FLIGHT`/`DONE`. `Seen begin(key)` first applies
  changed `maxEntries`/`ttl` (see Overview), then uses an atomic `putIfAbsent` and returns `NEW`, `IN_FLIGHT` or `DONE`;
  `complete(key)` does `asMap().replace(key, IN_FLIGHT, DONE)`; `forget(key)` removes it. No `RefreshEvent` listener, so there is no
  dependency on the order in which `RefreshScope` and other listeners run.
- New `org.fiware.iam.dataspace.EventListener`: `@Controller("${general.basepath:/}")`, `@Requires(condition = ListenerCondition.class)`,
  implements the generated `EventsApi` POST. It injects `List<DataSpaceEventHandler>`, `BearerVerifier`, `EventAuthorizer`,
  `EventDeduplicator` and `MeterRegistry`. Flow: verify the bearer via
  `Mono.fromCallable(() -> bearerVerifier.participantIdOf(header)).subscribeOn(Schedulers.boundedElastic())` (never on the event
  loop) → authorize → on `ACCEPT`/`SELF` dedup (`DONE` → `202 duplicate`, `IN_FLIGHT` →
  `503` + `Retry-After` named constant, `in_flight`) → `SELF` logs at INFO
  (`Data space event <id> from <source> is about this participant, not acted on.`) → dispatch → `202`. After `begin` returned
  `NEW`, a `doFinally` on the signal type settles the entry: `ON_COMPLETE` → `complete`, `ON_ERROR`/`CANCEL` → `forget`. On handler
  error it also logs the WARN once with `DownstreamError.reason(e)` and the exception attached, and returns `500`. Every path increments
  `dataspace.events.received{type,outcome}`, with `type` = the event type if a handler supports it, else `other`. Rejections log
  one WARN line.
- Tests: `EventListenerTest` as a `@MicronautTest` with `dataspace.events.listener.enabled=true`. A test-only
  `@Requires(env = "test")` controller serves a JWKS, and a test `DataSpaceEventHandler` counts its invocations. Parameterized over
  the acceptance list:
  - configured publisher → `202`, handled once
  - unknown type → `202`, counter +1, not handled
  - known type from an unconfigured publisher → `403`
  - mismatched source → `403`
  - no bearer → `401`
  - replay of the same `(source, id)` → `202`, handled once
  - retry while the first attempt is still running (test handler blocks on a latch) → `503` with `Retry-After`
  - unknown and rejected events with random type names → counted under `type=other`, no new meter per name
  - self subject → `202`, `self` counter +1, not handled
  - handler failure followed by a retry → handled twice
  - cancellation while the handler runs (the controller `Mono` is subscribed directly and disposed while the test handler blocks)
    → the dedup entry is gone and the next retry is handled, not answered with `503`
  - `application/json` instead of `application/cloudevents+json` → `415`

  Also check that existing tests stay green while the listener is disabled (the default).
- `EventDeduplicatorTest`: begin/complete/forget transitions; `complete` after eviction does not re-add the entry; and, through a
  real `ApplicationContext` (`Environment#refreshAndDiff()` + `RefreshEvent`, as `/refresh` does), a refresh that lowers
  `max-entries`/`ttl` makes the live cache use the new values on the next `begin`.
- Update `AGENTS.md`: add `dataspace/` to the structure, and document the micrometer and caffeine dependencies.
- **Acceptance:** all `POST /events` rows of the ticket acceptance pass over HTTP. `mvn test` is green.

### Step 5: `OPTIONS /events` CloudEvents webhook validation
- Implement the `OPTIONS` operation of `EventsApi` in `EventListener`. If the generator does not emit `@Options`, add a hand-written
  `@Options("/events")` method. First verify with a minimal test that the route is actually reached in Micronaut 4.7 and not
  answered by the server's built-in OPTIONS handling (`micronaut.server.dispatch-options-requests` / CORS); set what is needed so it
  is. The response always carries `Allow: POST`. When `WebHook-Request-Origin` matches the `origin` of a configured publisher
  (`allowedOrigin`, case-insensitive because it is a DNS name, read through the injected properties bean), it also carries
  `WebHook-Allowed-Origin: <the configured origin>`. Otherwise that header is absent. No bearer is required, because the handshake is
  unauthenticated by spec. Header names are constants.
- Tests: in `EventListenerTest` (or `EventListenerOptionsTest`), a `@ParameterizedTest` over a configured origin, an unconfigured
  origin, a missing header, the origin of a publisher whose `id` differs from that origin, and a case-variant origin (granted,
  echoing the configured spelling).
- **Acceptance:** `OPTIONS /events` grants only configured origins and always returns `Allow: POST`.

### Step 6: Refresh without restart: `/refresh`, ConfigMap file watch and an end-to-end refresh test
- `src/main/resources/application.yaml`: `endpoints.refresh.enabled: true` and `endpoints.refresh.sensitive: false`. Management runs
  on port 9090; without micronaut-security a sensitive endpoint is refused. The open endpoint was accepted in review; the README
  states the exposure. When the watch is enabled, set
  `micronaut.io.watch.enabled` and `micronaut.io.watch.paths` from `dataspace.events.listener.watch.path`.
- New `org.fiware.iam.dataspace.ConfigFileRefresher`, an `ApplicationEventListener<FileChangedEvent>` with
  `@Requires(condition = WatchCondition.class)`. It does what Micronaut's `RefreshEndpoint` does: call
  `Environment#refreshAndDiff()` (a bare `RefreshEvent` would only rebind beans from the stale Environment), and if the diff is
  non-empty publish `new RefreshEvent(diff)` through `ApplicationEventPublisher`. Debounce it with a named constant window, because a
  Kubernetes ConfigMap update is an atomic `..data` symlink swap that emits several events. It must **not** filter on the config
  file name: in a directory-mounted ConfigMap the events concern `..data` and `..<timestamp>` entries, while `application.yaml` is a
  symlink that never changes itself. Any event under the watched path triggers the (debounced) `refreshAndDiff()`; an empty diff
  makes that a no-op.
- Tests: `DataSpaceEventsRefreshTest`, a surefire test that starts a real `ApplicationContext` + `EmbeddedServer`
  (`micronaut.config.files` points at a temp YAML file; the test JWKS controller from Step 4). Steps:
  1. Publisher B → `403`.
  2. Add B to the file, then `POST http://localhost:<management-port>/refresh`.
  3. B → `202`.
  4. Add publisher C with the watch enabled, without calling `/refresh`. The test lays the directory out like kubelet does
     (`..<timestamp>/application.yaml`, a `..data` symlink to it, and `application.yaml` → `..data/application.yaml`) and applies the
     update as a new `..<timestamp>` directory plus an atomic rename of the `..data` symlink, so a file-name filter would fail it.
  5. Awaitility until C → `202`.
  6. Add an origin and check that `OPTIONS` now grants it.

  It is the fast in-JVM check. It also asserts that an edit which changes nothing yields no `RefreshEvent`.
- k3s: `src/test/resources/k3s/contract-management-provider.yaml` mounts the ConfigMap as a directory (`mountPath: /config`, no
  `subPath`) with `MICRONAUT_CONFIG_FILES=/config/application.yaml`, and enables the listener, `/refresh` and the watch on `/config`.
  New `k3s/events-jwks.yaml`: a static JWKS served by nginx from a ConfigMap; the matching private key lives in
  `src/test/resources/dataspace/` so the IT can sign participant tokens.
- `pom.xml` k3s plugin: add `<portBinding>9090:9090</portBinding>` (CM management; the CM `LoadBalancer` service already exposes
  9090, and no other service binds it on the host). fabric8 reads the kubeconfig that the k3s plugin writes (its `kubeconfig`
  parameter, default `${project.build.directory}/k3s.yaml`; confirm for plugin 2.0.1). The in-container profile passes that path to
  failsafe as the system property `k3s.kubeconfig` in its `systemPropertyVariables`, so the IT does not hard-code a path.
- New `InContainerDataSpaceEventsIT` (profile in-container). It patches the `contract-management-provider` ConfigMap through the
  kubeconfig from `k3s.kubeconfig` (fabric8 `kubernetes-client`, test scope):
  1. Publisher B → `403`.
  2. Add B to the ConfigMap, then `POST /refresh` on 9090; Awaitility until B → `202` (kubelet propagates the change with a delay).
  3. Add publisher C to the ConfigMap without calling `/refresh`; Awaitility (timeout a named constant above the kubelet sync
     period) until C → `202`, exercising the real `..data` symlink swap.

  This IT is the acceptance evidence that a publisher added to the ConfigMap is accepted without a restart.
  _Plan change (implementation):_ the deployed image has no `DataSpaceEventHandler`, so every authenticated event with a matching
  source ends in `202 unknown_type` and "B → 403" cannot be observed in the container. The IT therefore observes the publisher list
  through the `OPTIONS /events` origin grant (B's/C's origin not granted → granted), checks the bearer path separately
  (no token → `401`, token from the configured publisher A → `202`), and restores the ConfigMap afterwards.
  _Plan change (review):_ the watch is enabled in the container, so the IT cannot tell a `/refresh` from a watch refresh. It
  only checks that `/refresh` answers `200`, and adds B and C through the watch alone; `DataSpaceEventsRefreshTest` is the
  evidence for `/refresh`. `/refresh` and the watch refresh are serialized by `ConfigurationRefreshLock`
  (`SerializedRefreshEndpoint` replaces Micronaut's `RefreshEndpoint`).
  _Plan change:_ the consumer `contract-management` LoadBalancer service also exposed 9090, so klipper could schedule only one of
  the two CM service load balancers; its 9090 port (unused from the host) is removed from `src/test/resources/contract-management.yaml`.
- README: add a "Data space events" section with the config keys, the endpoint, the authorization order, the metric, the refresh
  options, that `/refresh` on 9090 is unauthenticated, that `listener.enabled`/`listener.watch.enabled` need a restart (startup-time
  conditions) while the rest is refreshable, the verifier settings it needs (`participantId` claim, CM audience), and the
  warning that the ConfigMap must be mounted as a directory, **not** with `subPath`, because kubelet never updates
  `subPath` mounts. The Helm side is DSC-1 (#102).
- _Plan change:_ agents may not edit `AGENTS.md`; the following edit is left to a plan-mode agent. `AGENTS.md`: change the `k3s/*.yaml` line from "CM config is a ConfigMap mounted via subPath" to "mounted as a directory at
  `/config` (no subPath, so refresh/watch see updates)", and list `InContainerDataSpaceEventsIT`, the `k3s.kubeconfig` property and
  the 9090 port binding.
- **Acceptance:** the embedded test and the in-container IT pass for both paths. `mvn test` and `mvn clean package` are green. README and `AGENTS.md`
  describe the feature.
