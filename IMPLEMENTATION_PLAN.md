# Implementation Plan: [CM] Trust anchor watcher: deriving participant.removed from the TIR

## Overview
Add a scheduled `TirWatcher` to contract-management. It runs in the publisher-mode instance in the trust anchor's
namespace. Each cycle it fully enumerates the Trusted Issuers Registry (TIR), diffs the result against the previous
snapshot, and publishes a `participant.removed` event through the CM-2 publisher (`EventDelivery`). The diff is guarded
by safeguards, all configurable and backed by named constants: a snapshot is taken only on a complete enumeration,
`confirmations` consecutive cycles must pass before a removal is announced, a `max-removals-per-cycle` circuit breaker
trips on mass removals, an empty registry is alerted on, and the first snapshot is silent. Detection is by polling (architecture D-3), never by a TIL hook.

### Design decisions (apply to all steps)
- **Registry API.** Use the EBSI-style TIR read API `GET /v4/issuers?page[size]=&page[after]=`, specified in
  `https://raw.githubusercontent.com/FIWARE/trusted-issuers-list/main/api/trusted-issuers-registry.yaml`.
  It returns `{self, items:[{did, href}], total, pageSize, links}`. In FIWARE trusted-issuers-list,
  `page[after]` is a **0-based page number**, results are sorted by DID, `page[size]` is 1..100, and an empty registry
  answers `{items:[], total:0, pageSize:0}` **without `links`**. Do not follow `links.next`: it is an absolute URL
  built from the registry's own view of its host. Increment `page[after]` instead.
- **Complete enumeration.** An enumeration is complete only if every page answered 2xx, every page reports the same
  `total`, no DID is blank, the number of distinct DIDs collected equals `total`, and the page count stayed below
  `max-pages`. Anything else is incomplete and throws. This also catches entries skipped by offset pagination
  when the registry changes during the enumeration.
- **State (in memory only).** `snapshot` is a `Set<String>`, or null before the first complete cycle. `pending` is a
  `Map<String,Integer>` from a missing DID to the number of consecutive complete cycles it has been missing.
  A restart loses both, so the first cycle after a restart is silent. Removals that happen while CM is down are not
  announced; document this as accepted.
- **Cycle algorithm** (C = DIDs of a complete enumeration):
  1. Incomplete enumeration: change no state, emit nothing, log WARN once with `DownstreamError.reason(e)`, outcome
     `incomplete`. Error cycles neither confirm nor refute a pending removal.
  2. `C` is empty: change no state, emit nothing, ALERT (`tir_empty`), outcome `empty`. This applies to the first cycle too.
  3. `snapshot == null`: set `snapshot = C`, emit nothing, log INFO with the size, outcome `initial`.
  4. `missing = snapshot \ C`. If `missing.size() > maxRemovalsPerCycle`: change no state (snapshot and pending stay
     as they are), emit nothing, ALERT (`tir_mass_removal`), outcome `breaker`. The breaker re-trips every cycle until
     the operator raises the cap (refreshable) or the registry is restored.
  5. Otherwise, drop from `pending` every DID that is in C again (reappeared), and increment `pending` for every DID in
     `missing`. Each DID with `pending >= confirmations` is emitted and removed from both `snapshot` and `pending`.
     Then add `C \ snapshot` (additions, never announced). Outcome `complete`.
- **Event.** `type` is the constant `TirWatcher.PARTICIPANT_REMOVED_TYPE = "org.fiware.dataspace.participant.removed"`,
  which follows the repo's `org.fiware.dataspace.*` naming. `subject` is the removed DID, and `data` is
  `{"participantId": "<did>"}`. The source, id, time and dataspaceid are built the same way as in `PublishEndpoint`.
  **Before implementing step 4**, check the type string and payload against architecture §8
  (`doc/eventing/architecture.md` in data-space-connector) and against any `participant.removed` handler that CM-3
  (#103) has merged (`grep -rn "participant" src/main/java/org/fiware/iam/dataspace`). Align to them if they differ.
- **Alert** means three things: an ERROR log line with the `FailureReason` code (`FailureReason.X.format(...)`) and
  the counts involved; the metric outcome; and a DEGRADED health detail (step 5). A raised alert is cleared by the next
  cycle that ends with `complete` or `initial`.
- **Metric.** Counter `dataspace.tir.cycles`, tag `outcome` ∈ {initial, complete, incomplete, empty, breaker}, plus
  counter `dataspace.tir.removals` for the announced removals. Use the `Optional<MeterRegistry>` →
  `CompositeMeterRegistry` fallback, as `EventListener` does. Never use DIDs as tag values.
- Use the doc density of the existing `dataspace/` classes: one-line class Javadoc, Javadoc on configuration
  fields, no `@param`/`@return`.

## Steps

### Step 1: TIR read client and watcher configuration
Lay the foundation: no behaviour yet.
- `pom.xml`: add the property `tir.api.url` → the `trusted-issuers-registry.yaml` URL above, and a new
  `openapi-generator-maven-plugin` execution `tir`. Copy the `til` execution: `apiPackage` `org.fiware.iam.tir.api`,
  `modelPackage` `org.fiware.iam.tir.model`, `clientId` `trusted-issuers-registry`, `generatorName` micronaut,
  `modelNameSuffix` VO, `supportAsync` true, `useGenericResponse` true, `openApiNullable` false. Make sure the generated
  `TirApiClient` (or whatever name the tag yields) compiles and does not clash with `org.fiware.iam.til.*`.
- `src/main/resources/application.yaml`:
  - Add `micronaut.http.services.trusted-issuers-registry` (`path: "/"`, `url: http://localhost:8085`, `read-timeout: 30`).
  - Add `micronaut.executors.tir-watcher` (`type: scheduled`, `core-pool-size: 1`), like `notification-subscriber`.
- `configuration/DataSpaceEventsProperties.java`: add the field `tirWatcher = new TirWatcher()` and a nested
  `@Data @ConfigurationProperties("tir-watcher") public static class TirWatcher` with Javadoc'd fields and
  `DEFAULT_*` constants:
  - `enabled` (default false; evaluated at startup only)
  - `initialDelay` (`PT30S`)
  - `interval` (`PT1M`)
  - `pageSize` (`100`, the spec maximum)
  - `maxPages` (`1000`)
  - `confirmations` (`2`; with the 1 min interval this gives the accepted ~2 min latency)
  - `maxRemovalsPerCycle` (`5`)

  Add `public static class TirWatcherCondition implements Condition`, true iff `publisher.enabled && tirWatcher.enabled`.
- `bean/TirWatcherBeanFactory.java` (`@Factory`): a `@Named("tir-watcher") TaskScheduler` built from
  `@Named("tir-watcher") ScheduledExecutorService`, mirroring `NotificationBeanFactory`. Annotate the bean with
  `@Requires(condition = DataSpaceEventsProperties.TirWatcherCondition.class)`.
- `exception/FailureReason.java`: add `TIR_ENUMERATION_FAILED("tir_enumeration_failed")`, `TIR_EMPTY("tir_empty")` and
  `TIR_MASS_REMOVAL("tir_mass_removal")`. Add `exception/TirEnumerationException extends ContractManagementException`
  (reason `TIR_ENUMERATION_FAILED`; fields `page`, nullable `status`; keeps the cause).
- Tests: extend `configuration/DataSpaceEventsPropertiesTest` with a parameterized test of the `tir-watcher.*` defaults
  and binding, and a test of `TirWatcherCondition`, which needs both flags.
- Acceptance: `mvn clean package -DskipTests` generates the `org.fiware.iam.tir.*` sources, and `mvn test` is green.

### Step 2: Complete-enumeration reader for the TIR
- New `dataspace/TrustedIssuersRegistryReader.java` (`@Singleton`, `@Requires(condition = TirWatcherCondition.class)`,
  injects the generated TIR client and `DataSpaceEventsProperties`). It exposes one method, `Set<String> enumerate()`,
  which blocks and is called from the watcher thread. It reads the page size and max-pages from the properties on
  every call. It requests pages `page[after] = 0, 1, 2, …` until a page returns fewer items than requested, or none,
  and enforces every rule under *Complete enumeration*. It throws `TirEnumerationException` on an
  `HttpClientResponseException` (keep the status), a transport error, a missing/null body or items, a `total` that
  changes between pages, a blank DID, a distinct count ≠ `total`, or `maxPages` being reached. An empty registry is
  **not** an error: it returns an empty set, and the watcher decides what that means.
- Unit test `dataspace/TrustedIssuersRegistryReaderTest`, with the client mocked and parameterized via `@MethodSource`:
  - single page, multi-page, exact-multiple-of-page-size boundary, empty registry (no `links`)
  - 5xx on page 2, connection error, `total` changing between pages, count ≠ total, duplicate DID across pages,
    blank DID, max-pages exceeded
- HTTP test `dataspace/TrustedIssuersRegistryReaderHttpTest` (`@MicronautTest`, a fake TIR `@Controller` guarded by
  `@Requires(property = "spec.name", …)`, in the style of `SubscriptionControllerHttpTest`). It checks that the generated
  client sends `page[size]`/`page[after]` correctly and deserializes a real multi-page response and the
  empty-registry response.
- Acceptance: all reader tests pass, and no state is kept in the reader.

### Step 3: Reusable publishing of own events
`PublishEndpoint` currently builds the `DataSpaceEventVO` inline. Extract that so that the watcher emits exactly the same envelope.
- New `dataspace/OwnEventPublisher.java` (`@Singleton`, `@Requires(condition = PublisherCondition.class)`; injects
  `EventDelivery`, `DataSpaceEventsProperties`, `GeneralProperties`, `Clock`). Method:
  `Optional<PublishedEvent> publish(String type, @Nullable String subject, @Nullable Object data, @Nullable String id)`.
  It returns empty when no source is configured (neither `own-participant-id` nor `general.did`), and otherwise builds
  the event exactly as today (specversion, generated id, source, time, dataspaceid, `application/json`) and calls
  `EventDelivery.publish`. Move the `PublishedEvent` record there, or keep it in `PublishEndpoint` and reference it.
- `PublishEndpoint` delegates to it. It keeps its 400 on a blank type, the 503 plus ERROR log on a missing source,
  the INFO log and `plain(data)`.
- Tests: the existing `PublishEndpointTest` stays green unchanged, except for construction. Add
  `OwnEventPublisherTest` (parameterized over the source fallback and id generation).

### Step 4: TirWatcher diff, safeguards and acceptance tests
- New `dataspace/TirWatcher.java` (`@Slf4j @Singleton`, `@Requires(condition = TirWatcherCondition.class)`; injects
  `TrustedIssuersRegistryReader`, `OwnEventPublisher`, `DataSpaceEventsProperties`, `Clock`, `Optional<MeterRegistry>`).
  It defines `PARTICIPANT_REMOVED_TYPE`, the metric/tag name constants and an `enum Outcome` (lower-case name = tag
  value). `Outcome cycle()` implements the *Cycle algorithm* above. It reads `confirmations` (values < 1 are treated as
  1) and `maxRemovalsPerCycle` from the bean at the start of each cycle. It is `synchronized`, or guarded by a single
  thread, so that a cycle never overlaps another. It also exposes a read-only `Status` record (last outcome, last
  complete cycle `Instant`, snapshot size, pending size, alert active) for the health indicator.
  - Logging: INFO for each announced removal (`Participant <did> removed from the trust anchor registry, announced to N subscriptions.`).
  - Logging: one WARN per incomplete cycle (`DownstreamError.reason(e)`, exception attached).
  - Logging: one ERROR per alert (`FailureReason.TIR_EMPTY/TIR_MASS_REMOVAL.format(...)` with the snapshot size and the missing count).
  - Logging: log-and-rethrow is forbidden.
  - If `OwnEventPublisher` returns empty (no source), log ERROR, keep the DID pending so that it is retried next cycle,
    and do not count it as announced.
- **Acceptance test** `dataspace/TirWatcherTest`: a single `@ParameterizedTest @MethodSource` over scenarios. Each
  scenario consists of the config (confirmations, cap), a sequence of registry results per cycle (a `Set<String>` or
  a thrown `TirEnumerationException`), the expected emitted DIDs, the expected outcomes per cycle, and the expected
  alert state. Mock the reader and publisher, use `SimpleMeterRegistry`, and drive time with `MutableClock`. Scenarios:
  - clean removal: `{A,B,C}`, `{A,B}`, `{A,B}` → C emitted once on cycle 3 (confirmations 2); the confirmations=1
    variant emits on cycle 2
  - registry error mid-enumeration: `{A,B,C}`, error, error → nothing emitted, snapshot is still `{A,B,C}`. Variant
    `{A,B,C}`, `{A,B}`, error, `{A,B}` → C emitted on cycle 4
  - empty registry: `{A,B}`, `{}`, `{}` → nothing emitted, alert raised, snapshot unchanged; a later `{A,B}` clears the alert
  - reappearance: `{A,B,C}`, `{A,B}`, `{A,B,C}`, `{A,B}` (confirmations 2) → nothing emitted
  - restart: a fresh watcher whose first cycle is `{A}` → nothing emitted, outcome `initial`
  - over the cap: cap 2, `{A,B,C,D}`, `{A}` → nothing emitted, alert, outcome `breaker`, snapshot unchanged; at exactly
    the cap the removals proceed
  - additions are never announced: `{A}`, `{A,B}`
- Also assert the event arguments (type, subject = DID, data `participantId`) and the metric counts per outcome.

### Step 5: Scheduling, startup guard, health and documentation
- Scheduling: `TirWatcher` (or a small `TirWatcherScheduler` in `dataspace/`) implements
  `ApplicationEventListener<ServerStartupEvent>`. Do not use Micronaut's `@EventListener`: it clashes with
  `dataspace.EventListener`. It schedules the first cycle on the `@Named("tir-watcher") TaskScheduler` after
  `initialDelay`, then self-reschedules each next cycle after the **current** `interval` (one-shot chain, so a refreshed
  interval applies; cap the delay like `DataSpaceEventSubscriber.capped`). Any `Throwable` from a cycle is logged and
  the chain continues. Stop on `@PreDestroy`.
- Startup guard: when `tir-watcher.enabled` is true but `publisher.enabled` is false, or no own participant id /
  `general.did` is set, fail startup with a clear message. Follow the pattern of `SubscriberStartupGuard`, so that a
  misconfiguration does not silently do nothing.
- Health: `dataspace/TirWatcherHealthIndicator` (`@Requires(condition = TirWatcherCondition.class)`), in the style of
  `DataSpaceSubscriptionHealthIndicator`. It reports DEGRADED while an alert is active, or when there has been no
  complete cycle for `3 × interval` (named constant). It is UP otherwise, and the details come from `TirWatcher.Status`.
  Make sure it does not fail liveness or readiness probes, the same way the subscription indicator avoids that (see
  `DataSpaceSubscriptionHealthProbeTest`).
- Tests:
  - scheduler tests with `RecordingTaskScheduler`: first cycle after the initial delay; rescheduling after success
    and after an exception; a changed interval is applied
  - parameterized startup-guard test
  - health-indicator test over UP/DEGRADED
- Docs:
  - `README.md`: a new `#### Watching the trust anchor registry` section under `### Data space events`. It covers
    what is detected, latency ≈ `confirmations × interval`, every safeguard and its config key, the alerts (log
    code, metric, health), the operator remedy for a tripped breaker (raise `max-removals-per-cycle` via
    `POST :9090/refresh` for one cycle), and the restart/first-cycle behaviour.
  - Commented `tir-watcher:` block in the `dataspace.events` example of `src/main/resources/application.yaml`.
  - `AGENTS.md`: list `TirWatcher`/`TrustedIssuersRegistryReader` and `api` → `org.fiware.iam.tir.*`.
- Acceptance: `mvn test` is green. A k3s IT is out of scope: the k3s CM deployment is
  listener-only, and covering this would need a second publisher-mode CM plus a sink. The HTTP test of step 2 covers
  the registry contract.
