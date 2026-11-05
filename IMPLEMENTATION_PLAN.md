# Implementation Plan: [CM] Trust anchor watcher: deriving participant.removed from the TIR

## Overview
Add a scheduled `TirWatcher` to contract-management. It runs in the publisher-mode instance in the trust anchor's
namespace. Each cycle it fully enumerates the Trusted Issuers Registry (TIR), diffs the result against the previous
snapshot, and publishes a `participant.removed` event through the CM-2 publisher (`EventDelivery`). The diff is guarded
by safeguards, all configurable and backed by named constants: a snapshot is taken only on a complete enumeration,
`confirmations` consecutive cycles must pass before a removal is announced, a `max-removals-per-cycle` circuit breaker
trips on mass removals, a registry that turns empty is alerted on, and the first snapshot is silent. Detection is by polling (architecture D-3), never by a TIL hook.

### Design decisions (apply to all steps)
- **Registry API.** Use the EBSI-style TIR read API `GET /v4/issuers?page[size]=&page[after]=`, specified in
  `https://raw.githubusercontent.com/FIWARE/trusted-issuers-list/main/api/trusted-issuers-registry.yaml`.
  It returns `{self, items:[{did, href}], total, pageSize, links}`. The TIR is pluggable (that is why D-3 polls), so
  the reader must not depend on one implementation's cursor semantics: FIWARE trusted-issuers-list uses a 0-based page
  number, EBSI-style registries may start at 1, and a registry may cap `page[size]` below what was requested.
  Therefore `page[after]` is treated as an **opaque cursor taken from `links.next`**. The first request sends no
  `page[after]` (the registry's own default first page). Each further request sends the `page[after]` and `page[size]`
  values parsed from the **query** of the previous page's `links.next`, through the generated client against the
  configured `trusted-issuers-registry` service. The host and path of `links.next` are ignored: they are built from the
  registry's own view of its host (`getHrefUri` in FIWARE TIL) and may not be reachable from CM. FIWARE TIL emits
  `links.next` only while `page.hasNext()`. An empty page (an empty registry, or a page past the end) answers
  `{items:[], total:0, pageSize:0}` **without `links`**, so the `total` of an empty page means nothing.
- **Complete enumeration.** The `total` of the first page is authoritative. Paging stops as soon as the distinct DIDs
  collected reach that `total` (so no trailing request is made), or when a page has no `links.next` or is empty
  (end-of-list; the `total` of an empty page is ignored). A short page is **not** an end-of-list signal, because the
  registry may cap the page size. The enumeration is complete only if every page answered 2xx, every **non-empty**
  page reports the first page's `total`, no DID is blank, every `links.next` carries a parseable `page[after]` that has
  not been requested before in this enumeration, the number of distinct DIDs collected equals the first page's
  `total`, and the page count stayed below `max-pages`. Anything else is incomplete and throws. The count check also
  catches entries skipped by offset pagination, a cursor that repeats a page (duplicate DIDs leave the count below
  `total`), and a registry that shrank mid-enumeration (an early end leaves the count below `total`). A registry that
  gives no `links.next` before `total` is reached therefore never yields a complete enumeration; this fails safe
  (outcome `incomplete`, WARN every cycle).
- **State (in memory only).** `snapshot` is a `Set<String>`, or null before the first complete cycle. `pending` is a
  `Map<String,Integer>` from a missing DID to the number of consecutive complete cycles it has been missing.
  `undelivered` is a `Set<String>` of confirmed removals that have not yet been queued for any subscription (see
  step 5 of the cycle). A restart loses all three, so the first cycle after a restart is silent. Removals that happen while CM is
  down are not announced; document this as accepted.
- **Cycle algorithm** (C = DIDs of a complete enumeration):
  1. Incomplete enumeration: change no state, emit nothing, log WARN once with `DownstreamError.reason(e)`, outcome
     `incomplete`. Error cycles neither confirm nor refute a pending removal.
  2. `C` is empty **and `snapshot` is non-empty** (the registry went from participants to none): change no state,
     emit nothing, ALERT (`tir_empty`), outcome `empty`. An empty registry is far likelier a wiped or misconfigured
     TIR than the legitimate removal of every participant, so this is never acted on. Consequence (accepted,
     documented): the removal of the **last** participant is never announced; the alert stays active until a
     participant is added again. That announcement would have no recipient anyway, since every remaining subscriber
     is a removed participant. A fresh trust anchor whose TIR is empty from the start is not an alert: with
     `snapshot == null` step 3 takes `C = {}` as the initial snapshot, and with an empty `snapshot` step 5 runs
     normally (nothing is missing).
  3. `snapshot == null`: set `snapshot = C` (possibly empty), emit nothing, log INFO with the size, outcome `initial`.
  4. `missing = snapshot \ C`. If `missing.size() > maxRemovalsPerCycle`: change no state (snapshot, pending and
     undelivered stay as they are), emit nothing, ALERT (`tir_mass_removal`), outcome `breaker`. `missing` includes
     DIDs that are still awaiting confirmation from earlier cycles, so the cap bounds the removals **outstanding at
     once** (newly missing plus pending), not only those that disappeared in this cycle. This is deliberate: a broken
     registry that loses entries a few at a time is still caught. It also bounds the removals confirmed in one cycle,
     which is what the ticket's `maxRemovalsPerCycle` name refers to. The name stays (it is the ticket's term), and
     the config Javadoc and the README spell out these semantics. Example (cap 5, confirmations 2): 3 removed in one
     cycle and 3 more in the next → 6 missing → breaker. The breaker re-trips
     every cycle until the operator raises the cap (refreshable) or the registry is restored. Because a tripped cycle
     does not advance `pending`, a raised cap must stay in place for at least `confirmations` cycles
     (≈ `confirmations × interval`) before the removals are announced; lowering it earlier re-trips the breaker and
     freezes `pending`.
  5. Otherwise, drop from `pending` and from `undelivered` every DID that is in C again (reappeared), and increment
     `pending` for every DID in `missing`. Each DID with `pending >= confirmations` is removed from `snapshot` and
     `pending` and added to `undelivered`. Then publish every DID in `undelivered`; a DID leaves `undelivered` only
     when `EventDelivery.publish` queued it for at least one subscription. Queued is not delivered: delivery is
     asynchronous, and if it exhausts its retries afterwards the removal is lost for that subscriber. The watcher does
     not retry that; it is the delivery's own retry policy. A count of 0 (no subscription, e.g. after a publisher
     restart or in a subscriber's lease gap) or an empty `Optional` (no source) keeps it there for the next complete
     cycle, so a removal is not lost just because nobody was listening at that moment. `undelivered` DIDs are no
     longer in `snapshot`, so they never count towards the breaker. Finally add `C \ snapshot` (additions, never
     announced). Outcome `complete`.
- **Event.** `type` is the constant `TirWatcher.PARTICIPANT_REMOVED_TYPE = "org.fiware.dataspace.participant.removed"`,
  which follows the repo's `org.fiware.dataspace.*` naming. `subject` is the removed DID, and `data` is
  `{"participantId": "<did>"}`. The source, id, time and dataspaceid are built the same way as in `PublishEndpoint`.
  **Before implementing step 4**, check the type string and payload against architecture §8
  (`doc/eventing/architecture.md` in data-space-connector) and against any `participant.removed` handler that CM-3
  (#103) has merged (`grep -rn "participant" src/main/java/org/fiware/iam/dataspace`). Align to them if they differ.
- **Alert** means three things: an ERROR log line with the `FailureReason` code (`FailureReason.X.format(...)`) and
  the counts involved, logged when the alert is **raised** (or its kind changes), with DEBUG on every further cycle
  while it persists; the metric outcome, every cycle; and a DEGRADED health detail (step 5). A raised alert is cleared
  (INFO) by the next cycle that ends with `complete` or `initial`.
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
- `configuration/DataSpaceEventsProperties.java`: add the field `tirWatcher = new TirWatcherSettings()` and a nested
  `@Data @ConfigurationProperties("tir-watcher") public static class TirWatcherSettings` (suffix like
  `PublisherSettings`/`SubscriberSettings`, so it does not clash with `dataspace.TirWatcher`) with Javadoc'd fields and
  `DEFAULT_*` constants:
  - `enabled` (default false; evaluated at startup only)
  - `initialDelay` (`PT30S`)
  - `interval` (`PT1M`)
  - `pageSize` (`100`, the spec maximum)
  - `maxPages` (`1000`)
  - `confirmations` (`2`; with the 1 min interval this gives the accepted ~2 min latency)
  - `maxRemovalsPerCycle` (`5`; Javadoc states it counts all DIDs missing from the snapshot at once, including those
    still awaiting confirmation)

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
  every call. It requests the first page without `page[after]`, then follows the cursor from `links.next` (query
  only, see *Registry API*), and stops as soon as the distinct DIDs reach the first page's `total`, or at a page
  without `links.next` or with no items (its `total:0` is ignored). It enforces every rule under *Complete
  enumeration*. It throws `TirEnumerationException` on an `HttpClientResponseException` (keep the status), a transport
  error, a missing/null body or items, a non-empty page whose `total` differs from the first page, a blank DID, a
  `links.next` without a parseable `page[after]` or with one already requested, a distinct count ≠ first-page `total`,
  or `maxPages` being reached. An empty registry is **not** an error: it returns an empty set, and the watcher decides
  what that means.
- Unit test `dataspace/TrustedIssuersRegistryReaderTest`, with the client mocked and parameterized via `@MethodSource`:
  - single page, multi-page, empty registry (no `links`)
  - exact multiple of the page size (e.g. 2 pages of `page-size`): complete after exactly 2 requests, and the mock
    answers any further page the way the real server does (`{items:[], total:0, pageSize:0}`), so a trailing request
    or a total check on it would fail the test
  - 1-based cursor: the first page's `links.next` carries `page[after]=2`; the reader requests exactly that value
  - registry caps the page size: `page[size]=100` requested, every page returns 50 items with a `links.next` →
    complete, the short pages are not treated as end-of-list
  - registry shrank mid-enumeration: the second page answers `{items:[], total:0}` before the first-page `total` is
    reached → incomplete
  - `links.next` missing before `total` is reached, `links.next` without `page[after]`, and a `links.next` that repeats
    an already requested cursor → incomplete
  - 5xx on page 2, connection error, `total` changing between non-empty pages, count ≠ total, duplicate DID across
    pages, blank DID, max-pages exceeded
- HTTP test `dataspace/TrustedIssuersRegistryReaderHttpTest` (`@MicronautTest`, a fake TIR `@Controller` guarded by
  `@Requires(property = "spec.name", …)`, in the style of `SubscriptionControllerHttpTest`). It checks that the generated
  client sends `page[size]`/`page[after]` correctly and deserializes a real multi-page response and the
  empty-registry response. The fake builds `links.next` like upstream (absolute URL with a host that does not resolve,
  to prove only the query is used) and answers a page past the end with `{items:[], total:0, pageSize:0}`.
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
  complete cycle `Instant`, snapshot size, pending size, undelivered size, alert active) for the health indicator.
  - Logging: INFO for each announced removal (`Participant <did> removed from the trust anchor registry, queued for N subscriptions.`).
  - Logging: WARN once when a confirmed removal reached 0 subscriptions and is kept for retry; DEBUG on later retries,
    so a publisher without subscriptions does not log every interval.
  - Logging: one WARN per incomplete cycle (`DownstreamError.reason(e)`, exception attached).
  - Logging: one ERROR when an alert is raised (`FailureReason.TIR_EMPTY/TIR_MASS_REMOVAL.format(...)` with the
    snapshot size and the missing count), DEBUG while it persists, INFO when it clears. A trust anchor stuck in an
    alert therefore does not log an ERROR every interval.
  - Logging: log-and-rethrow is forbidden.
  - If `OwnEventPublisher` returns empty (no source), log ERROR and keep the DID in `undelivered`. Neither that nor a
    count of 0 counts as announced in `dataspace.tir.removals`.
- **Acceptance test** `dataspace/TirWatcherTest`: a single `@ParameterizedTest @MethodSource` over scenarios. Each
  scenario consists of the config (confirmations, cap), a sequence of cycles, each with the registry result (a
  `Set<String>` or a thrown `TirEnumerationException`), an optional cap override (applied to the properties bean
  before that cycle, as a refresh would) and the publisher's delivery count, plus the expected emitted DIDs, the
  expected outcomes per cycle, and the expected alert state. Mock the reader and publisher, use `SimpleMeterRegistry`, and drive time with `MutableClock`. Scenarios:
  - clean removal: `{A,B,C}`, `{A,B}`, `{A,B}` → C emitted once on cycle 3 (confirmations 2); the confirmations=1
    variant emits on cycle 2
  - registry error mid-enumeration: `{A,B,C}`, error, error → nothing emitted, snapshot is still `{A,B,C}`. Variant
    `{A,B,C}`, `{A,B}`, error, `{A,B}` → C emitted on cycle 4
  - empty registry: `{A,B}`, `{}`, `{}` → nothing emitted, alert raised (one ERROR, not two), snapshot unchanged; a
    later `{A,B}` clears the alert
  - empty from the start: `{}`, `{}`, `{A}` → outcomes `initial`, `complete`, `complete`; no alert, nothing emitted
  - last participant removed: `{A}`, `{}`, `{}` → outcome `empty` twice, nothing emitted, alert active
  - reappearance: `{A,B,C}`, `{A,B}`, `{A,B,C}`, `{A,B}` (confirmations 2) → nothing emitted
  - restart: a fresh watcher whose first cycle is `{A}` → nothing emitted, outcome `initial`
  - over the cap: cap 2, `{A,B,C,D}`, `{A}` → nothing emitted, alert, outcome `breaker`, snapshot unchanged; at exactly
    the cap the removals proceed
  - staggered removals (confirmations 2, cap 5): `{A..G}`, `{A,B,C,D}` (3 missing), `{A}` (6 missing) → breaker on
    cycle 3, although no single cycle removed more than 5
  - breaker remedy (confirmations 2, cap 2): `{A,B,C,D}`, `{A}` (breaker), `{A}` with cap 5, `{A}` with cap back to 2
    → breaker again, nothing emitted. Variant keeping cap 5 for two cycles → B, C, D emitted on the second raised cycle
  - no subscriptions: confirmations 1, `{A,B}`, `{A}` with delivery count 0, `{A}` with count 1 → B published on cycles 2
    and 3, announced (metric) once, `undelivered` empty afterwards. Variant: `{A,B}`, `{A}` (count 0), `{A,B}` → B
    dropped from `undelivered`, not published again
  - additions are never announced: `{A}`, `{A,B}`
- Also assert the event arguments (type, subject = DID, data `participantId`) and the metric counts per outcome.

### Step 5: Scheduling, startup guard, health and documentation
- Scheduling: `TirWatcher` (or a small `TirWatcherScheduler` in `dataspace/`) implements
  `ApplicationEventListener<ServerStartupEvent>`. Do not use Micronaut's `@EventListener`: it clashes with
  `dataspace.EventListener`. It schedules the first cycle on the `@Named("tir-watcher") TaskScheduler` after
  `initialDelay`, then self-reschedules each next cycle after the **current** `interval` (one-shot chain, so a refreshed
  interval applies; cap the delay like `DataSpaceEventSubscriber.capped`). Any `Throwable` from a cycle is logged and
  the chain continues. Stop on `@PreDestroy`.
- Startup guard: `dataspace/TirWatcherStartupGuard` (`@Context`). When `tir-watcher.enabled` is true but
  `publisher.enabled` is false, or no own participant id / `general.did` is set, fail startup with a clear
  `ConfigurationException`. It follows `SubscriberStartupGuard`, **but** is conditioned on `tir-watcher.enabled`
  alone (`@Requires(property = "dataspace.events.tir-watcher.enabled", value = "true")`), not on
  `TirWatcherCondition`: that condition needs `publisher.enabled`, so the guard would not exist in exactly the case it
  must reject.
- Health: `dataspace/TirWatcherHealthIndicator` (`@Requires(condition = TirWatcherCondition.class)`), in the style of
  `DataSpaceSubscriptionHealthIndicator`. It reports DEGRADED while an alert is active, or when there has been no
  successful cycle (outcome `complete` or `initial`) for `3 × interval` (named constant). Before the first successful
  cycle the baseline is the instant the watcher was started (taken from the `Clock` when the first cycle is scheduled),
  so the initial delay does not report DEGRADED. `TirWatcher.Status` carries that start instant. It is UP otherwise,
  and the details come from `TirWatcher.Status`. Reuse the existing `DataSpaceSubscriptionHealthIndicator.DEGRADED`
  status, or move it to a shared constant both indicators use, so that the aggregate never sees two different
  DEGRADED values.
  Make sure it does not fail liveness or readiness probes, the same way the subscription indicator avoids that (see
  `DataSpaceSubscriptionHealthProbeTest`).
- Tests:
  - scheduler tests with `RecordingTaskScheduler`: first cycle after the initial delay; rescheduling after success
    and after an exception; a changed interval is applied
  - parameterized startup-guard test over (tir-watcher enabled, publisher enabled, own id set): fails for
    watcher on + publisher off, and for watcher on + no own id; starts otherwise (including watcher off + publisher off)
  - health-indicator test over UP/DEGRADED, including UP during the initial delay and DEGRADED after `3 × interval`
    without a successful cycle
- Docs:
  - `README.md`: a new `#### Watching the trust anchor registry` section under `### Data space events`. It covers
    what is detected, latency ≈ `confirmations × interval`, every safeguard and its config key, the alerts (log
    code, metric, health), what `max-removals-per-cycle` counts (all DIDs missing at once, including those awaiting
    confirmation), the operator remedy for a tripped breaker (raise `max-removals-per-cycle` via
    `POST :9090/refresh` and keep it raised for at least `confirmations` cycles, ≈ `confirmations × interval`, before
    lowering it again), the empty-registry behaviour (an empty TIR from the start is fine; going to empty is alerted
    and never acted on, so removing the last participant is not announced), the restart/first-cycle behaviour, and
    the delivery semantics (a confirmed removal is retried every cycle until it was queued for at least one
    subscription; a delivery that fails after queueing is not retried by the watcher; subscribers that register later
    do not get it).
  - Commented `tir-watcher:` block in the `dataspace.events` example of `src/main/resources/application.yaml`.
  - `AGENTS.md`: list `TirWatcher`/`TrustedIssuersRegistryReader` and `api` → `org.fiware.iam.tir.*`.
- Acceptance: `mvn test` is green. A k3s IT is out of scope: the k3s CM deployment is
  listener-only, and covering this would need a second publisher-mode CM plus a sink. The HTTP test of step 2 covers
  the registry contract.
