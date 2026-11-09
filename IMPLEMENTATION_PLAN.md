# Implementation Plan: [CM] Reacting to a removal: attribution, cleanup, reconciliation and the report

## Overview
Make a participant act on `org.fiware.dataspace.participant.removed` (CM-5 of the data space eventing epic). A
per-publisher `on-participant-removed` policy (`report` by default, or `dataspace` / `granted` / `full`, plus `keep`,
`overrides`, `max-removals-per-run`, `adopt-unattributed`) is applied in two places: to incoming events, and in a
reconciliation sweep that runs per publisher at start-up and on a timer. Every outcome is reported through a log line,
the metric `dsc_dataspace_events_participant_removed_total{publisher,action,mode}` (plus a gauge of the standing drift) and
the read-only management endpoint `GET :9090/participants-removed`. The service fails at start-up when the
trusted-issuers-list answers that it is too old for the configured mode; an unreachable list does not stop start-up.

## Decisions (apply to all steps)
- **Scope model at the TIL.** A membership grant is `scope=<publisher id>` with `scopeType=dataspace-membership`
  (`ScopeTypeVO.DATASPACE_MEMBERSHIP`; the spec value, where the ticket writes `dataspace`). An order grant is an
  *untyped* scope (`ORDER_SCOPE_TYPE = null`, unchanged). Credentials without a scope are **unattributed**. Use
  `GET /issuer/{did}/scopes` to read an issuer's grants. The number of unattributed credentials is the number of
  credentials in `GET /issuer/{did}` minus the sum of the `credentialCount` values. Before relying on that, verify in
  `FIWARE/trusted-issuers-list` `TrustedIssuersListController#getIssuer` / `TILMapper` that `getIssuer` returns all credentials.
- **What each mode does with the removed identifier X announced by publisher P:**
  | origin of X's entry | report | dataspace | granted | full |
  |---|---|---|---|---|
  | P's membership grant | keep | revoke | revoke | issuer deleted |
  | other publisher's membership grant | keep | keep | keep | issuer deleted |
  | order grants (untyped scopes) | keep | keep | revoke | issuer deleted |
  | unattributed credentials, nothing else held | keep | keep¹ | keep¹ | issuer deleted |
  | unattributed credentials next to grants (mixed) | keep | keep | keep | issuer deleted |

  ¹ Revoked instead when adoption is allowed (`adopt-unattributed`, Step 5): an entry that holds *nothing but*
  unattributed credentials counts as P's membership. This one rule applies to the sweep and the event path alike. A mixed
  entry is never adopted, because `CredentialsVO` carries no scope and its credentials cannot be split.

  An identifier on `keep` is never touched, by the event path or by the sweep. An override for an identifier replaces
  the publisher's mode for that identifier.
- **Who writes membership grants.** Contract-management writes a `dataspace-membership` grant in exactly one place:
  adoption (Step 5, `TrustedIssuersListAdapter.grantMembership`). Its order paths (`TilProductOrderHandler`,
  `ContractManagementController`) write untyped order grants only. Any other component that provisions TIL entries for
  data-space members (onboarding, helm-provisioned entries of the data-space-connector) must write them as
  `scope=<publisher id>` (the publisher's `id` from `dataspace.events.publishers`) with `scopeType=dataspace-membership`.
  The README section (Step 7) names these exact values. Without such a writer and without adoption, `dataspace` mode
  and the sweep have no attributable membership to act on: the entries stay unattributed and are only reported.
- **Convergent cleanup, membership revoked last** (*to be confirmed by the ticket owner*: the TIL has no
  multi-operation transactions, so the ticket's "nothing half-applied" cannot be met literally). All reads come first,
  so a failed read changes nothing. Writes follow in this order: order grants and unattributed credentials first, P's
  membership grant **last**. A failing write can leave an intermediate state (e.g. in `granted`, order grants revoked
  but P's membership still held), but the entry always still carries P's membership, so both the publisher's retry and
  the next sweep find it again and complete the cleanup. Every write is idempotent (`404` = already done). A retry
  recomputes the plan from the current TIL state and converges.
- **Failure answer.** If a TIL call fails while an event is handled, `EventListener` answers `502 Bad Gateway`
  (outcome `downstream_failed`) instead of `500`, and the deduplicator forgets the event so that the publisher retries.
- **Refreshable configuration.** `DataSpaceEventsProperties` is refreshable. Read the policy through the bean on
  every use and never cache it (see the class Javadoc). Follow the `TirWatcherSettings` pattern: `effective*()` getters
  clamp out-of-range values after a refresh, and the start-up guard rejects them.
- **Map keys.** DIDs contain `:` and `.`, which break Micronaut property-map keys. So `overrides` is a **list** of
  `{id, mode}`, not a map.
- **Metric.** Micrometer name `dsc.dataspace.events.participant.removed`, exported to Prometheus as
  `dsc_dataspace_events_participant_removed_total`. Tags: `publisher` (a configured publisher id), `mode` and `action`.
  `mode` is one of `report`, `dataspace`, `granted`, `full` (the effective mode, lower case) or `keep` (the identifier
  is on `keep`, so `removalMode` is empty; always paired with `action=kept`). Outcomes without an identifier (`breaker`,
  `adoption_refused`, a `failed` registry read) carry the publisher's own mode. `action` takes a fixed set of values:
  `reported`, `kept`, `not_held`, `revoked_membership`, `revoked_granted`, `deleted_issuer`, `adopted`,
  `adoption_refused`, `failed` and `breaker`. All tag values are bounded; the README lists them.
  The counter counts decisions, not sweep runs: the event path counts every handled event, the sweep counts an
  identifier only when it is newly seen for that publisher or its action changed (keyed on the report store, Step 6),
  so a `report`-mode deployment does not add the same drift every interval. The standing drift is a gauge
  `dsc.dataspace.events.participant.removed.held{publisher}` (Prometheus `dsc_dataspace_events_participant_removed_held`):
  the identifiers still holding P's membership although P's registry no longer lists them, set by each complete sweep.
- **Mass-removal cap.** `max-removals-per-run` acts as a breaker, like `tir-watcher.max-removals-per-cycle`. It counts
  only the candidates whose `RemovalPlan` contains writes; `report`-mode and `keep` candidates never trip it. If a sweep
  finds more write candidates than the cap, it applies none of them and reports all of them. The breaker is an alert in
  the `TirWatcher` style: ERROR and `action=breaker` once when it trips (or the candidate set changes), INFO when it
  clears, and a health indicator stays DEGRADED in between, so a standing trip does not log an ERROR every interval.
  Applying the first N per run was rejected: a wrongly truncated registry would then empty the TIL a few entries at a
  time. Events are not capped, because the trust anchor's own watcher already applies a breaker before it announces
  anything.
- **Adoption limit.** Adoption is not a removal and is not under the breaker. It has its own
  `max-adoptions-per-run` (default 50) that applies the first N candidates per run and continues on the next run.
  Truncation is safe here, because adoption only adds a membership grant before it removes the duplicate unattributed
  credentials; it never takes trust away.
- **Report endpoint.** The report shows which issuers this participant trusts under which scopes, so it is not served on
  the main port next to the publicly routed `POST /events`. It is a read-only micronaut-management endpoint
  `@Endpoint(id = "participants-removed", defaultSensitive = false)` with a `@Read` method on the cluster-internal
  management port (9090), following `PublishEndpoint`. A management-port guard (as `ManagementPortGuard`) fails start-up
  when `endpoints.all.port` is unset or equals the server port. No OpenAPI change is needed.
- **Start-up and an unreachable TIL.** `ParticipantRemovedCondition` activates on configuration that existing
  deployments may already have (a publisher whose `types` include participant.removed). Start-up therefore fails only on
  a definitive "too old" answer from the TIL. An unreachable TIL does not stop start-up: the capability stays
  *unverified*, a health indicator reports DOWN, and the check is repeated before the first sweep and before the first
  event write (an event then answers `502`, so the publisher retries). The README calls out this upgrade impact.

## Steps

### Step 1: Configuration of the participant-removed policy
Add the configuration only, without any behaviour.
- `src/main/java/org/fiware/iam/configuration/DataSpaceEventsProperties.java`:
  - Add `OnParticipantRemoved onParticipantRemoved = new OnParticipantRemoved()` to `Publisher`. `OnParticipantRemoved`
    is a `@Data` class with these fields:
    - `RemovalMode mode = REPORT`
    - `List<String> keep`
    - `List<Override> overrides`, where `Override` is `{String id; RemovalMode mode}`
    - `int maxRemovalsPerRun = DEFAULT_MAX_REMOVALS_PER_RUN` (5), clamped to `>= 0` by `effectiveMaxRemovalsPerRun()`
    - `boolean adoptUnattributed = false`
    - `int maxAdoptionsPerRun = DEFAULT_MAX_ADOPTIONS_PER_RUN` (50), clamped to `>= 0` by `effectiveMaxAdoptionsPerRun()`
    - `@Nullable String registryUrl`: base URL of this publisher's trusted issuers registry, used by the sweep. Without
      it the publisher gets no sweep.
  - Add a `ParticipantRemovedSettings participantRemoved` (`@ConfigurationProperties("participant-removed")`) with the
    sweep timing `reconcile.initial-delay` (default 1 min) and `reconcile.interval` (default 15 min). Give each an
    `effective*()` getter, as `TirWatcherSettings` does. Also add `page-size`/`max-pages` for reading the TIL and the
    publisher registries, reusing the `TirWatcherSettings.MIN/MAX_PAGE_SIZE` bounds.
  - Add helpers on the properties:
    - `removalPublishers()`: the publishers with a non-blank id whose `types` contain the participant.removed type.
      Move `TirWatcher.PARTICIPANT_REMOVED_TYPE` to a shared constant or reference it.
    - `removalMode(publisherId, did)`: the effective mode. Returns empty when the DID is on `keep`; otherwise an
      override wins over the publisher's mode.
  - Add `ParticipantRemovedCondition`: the listener is enabled **and** at least one publisher is authorized for
    participant.removed.
- New enum `org.fiware.iam.configuration.RemovalMode` (`REPORT, DATASPACE, GRANTED, FULL`), with a `tagValue()` in lower
  case, and a constant `KEEP_TAG_VALUE = "keep"` for the `mode` tag of identifiers on `keep`. Make sure the lower-case YAML values bind; if Micronaut's enum conversion does not accept them, add a
  `TypeConverter`.
- `src/main/resources/application.yaml`: extend the commented `dataspace.events` example with an `on-participant-removed`
  block and `participant-removed.reconcile`.
- Tests (`src/test/java/org/fiware/iam/configuration/DataSpaceEventsPropertiesTest.java`, parameterized):
  `removalMode` covers the default, the publisher mode, an override beating the mode, and `keep` beating an override.
  Also cover the effective clamps, `removalPublishers`, the binding of lower-case modes and of an `overrides` list with
  DIDs (a small `ApplicationContext.run(Map)` test).

**Acceptance:** `mvn test` is green. Without configuration, nothing changes for existing deployments.

### Step 2: TrustedIssuersListAdapter extensions for attribution and cleanup
Extend `src/main/java/org/fiware/iam/til/TrustedIssuersListAdapter.java`, leaving the order paths (`allowIssuer`,
`denyIssuer`) unchanged. Check the generated `IssuerApiClient` signatures in `target/generated-sources` first.
- `Mono<Optional<HeldEntry>> heldEntry(String did)`: reads `getIssuerScopes` and `getIssuer`. Returns empty on `404`.
  `HeldEntry` is a new record in `til/` with the did, `List<Grant(String scope, @Nullable ScopeTypeVO type, int count)>`
  and `int unattributedCount`, and helpers such as `membershipOf(publisherId)`, `otherMemberships(publisherId)`,
  `orderGrants()` and `isAttributable()`.
- `Mono<Set<String>> issuersGrantedBy(String scope, ScopeTypeVO type)`: all pages of `GET /issuer?scope=&scopeType=`
  (zero-based `page`, stop at `total` or at a short page). Fails if the list needs more than `max-pages` or is
  inconsistent, so the sweep never works from a partial list.
- `Mono<Set<String>> allIssuers()`: the same paging without a filter. Step 5 needs it.
- `revokeGrant(did, scope, @Nullable type)`: `deleteCredentialsByScope`, with `404` treated as done.
- `revokeUnattributed(did)`: `deleteCredentialsByScope(did, null, null, true)`, with `404` treated as done.
- `deleteIssuer(did)`: `deleteIssuerById`, with `404` treated as done.
- `grantMembership(did, publisherId, List<CredentialsVO>)`: `replaceCredentialsByScope` with
  `DATASPACE_MEMBERSHIP_SCOPE_TYPE = ScopeTypeVO.DATASPACE_MEMBERSHIP`. This is the single place where CM writes
  membership grants.
- Wrap every failure in `TrustedIssuersException` and keep the cause. Use new `FailureReason`s
  (`TIL_READ_FAILED`, `TIL_REVOKE_FAILED`) with the DID, scope and type in the message. Do not log inside the adapter
  (log once, see AGENTS.md).
- Update the class Javadoc: the class now also grants and revokes data-space memberships.
- Tests (`src/test/java/org/fiware/iam/til/TrustedIssuersListAdapterTest.java`, Mockito on `IssuerApiClient`,
  parameterized):
  - `404` handling for each write
  - the error wrapping
  - paging, covering one page, several pages, a short last page, an inconsistent total and too many pages
  - the `HeldEntry` classification, covering membership of P, membership of another publisher, untyped grants and
    unattributed credentials
  - that order grants are still written untyped

**Acceptance:** the adapter offers every call that Steps 3–7 need, and the existing order tests still pass.

### Step 3: ParticipantRemovedHandler: policy, cleanup, 502 and per-decision logging/metric
- New `src/main/java/org/fiware/iam/dataspace/RemovalPlan.java`: a pure function from `(RemovalMode | keep, HeldEntry,
  publisherId)` to an ordered list of TIL operations plus the resulting `action`. It implements the table in
  *Decisions* and puts P's membership revocation last.
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemoval.java` (`@Singleton`,
  `@Requires(condition = ParticipantRemovedCondition)`), shared by the event path and the sweep. Its
  `Mono<RemovalOutcome> apply(String publisherId, String did, Trigger trigger)` works in this order:
  1. Resolve the mode, then read the `HeldEntry` (all reads first).
  2. Build the plan and run its writes sequentially (`concatMap`).
  3. Write one INFO line, e.g. `Participant <did> removed by <publisher> (mode <m>, <trigger>): <action>; still held: <grants>`.
  4. Count `dsc.dataspace.events.participant.removed{publisher,action,mode}`, using the `Optional<MeterRegistry>`
     fallback that `EventListener` uses.

  On failure it counts `action=failed` and passes on a `ParticipantRemovalException` (new `ContractManagementException`
  subclass with reason `PARTICIPANT_REMOVAL_FAILED`, holding the publisher and DID and keeping the TIL cause). It does
  not log, since the failure is logged once in `EventListener`.
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemovedHandler.java`, a `DataSpaceEventHandler` for the
  participant.removed type:
  - The participant is `event.getSubject()`, falling back to `data.participantId` (see `TirWatcher.PARTICIPANT_ID_KEY`).
    An event that has neither is answered with `400`; an `InvalidEventException` mapped in `EventListener` is enough.
  - The publisher is `event.getSource()`, which `EventAuthorizer` has already matched against the caller and the
    configured publishers.
- `EventListener.dispatch`: a failure whose cause chain contains a `TrustedIssuersException` answers `502`
  (outcome `downstream_failed`). Other failures still answer `500`.
- Tests (`src/test/java/org/fiware/iam/dataspace/`):
  - `RemovalPlanTest`: parameterized `@MethodSource` over mode × origin of the entry (P's membership, another
    publisher's membership, order grant, unattributed only, mixed grants + unattributed, the combinations, not held),
    each with adoption allowed and not allowed. Covers keep (`mode=keep`, `action=kept`) and override.
  - Multi-membership test: an identifier held by P1 and P2 and removed by P1 keeps P2's grant in every mode except `full`.
  - `ParticipantRemovalTest` (Mockito adapter): the writes and their order; the metric tags; a failing read gives no
    write; a failing first write gives no further write, keeps P's membership grant and raises the exception; a
    retry against the resulting TIL state completes the cleanup.
  - `EventListenerTest`: a TIL failure answers `502`, and a redelivery is then handled again.

**Acceptance:** all mode × origin (including the mixed entry), keep/override and multi-membership tests pass. A failing
TIL call answers `502`, P's membership grant survives, and a retry completes the cleanup.

### Step 4: Per-publisher reconciliation sweep
- Make the registry reader reusable. Refactor `src/main/java/org/fiware/iam/dataspace/TrustedIssuersRegistryReader.java`
  so that its completeness checks run over a page source, `(pageSize, cursor) -> HttpResponse<IssuersResponseVO>`, with
  explicit `pageSize`/`maxPages` values. Keep `enumerate()` for the `TirWatcher` (the generated `TirApiClient`). Add
  `enumerate(String registryUrl)`, which uses a low-level `HttpClient` against `{registryUrl}/v4/issuers` with
  `page[size]`/`page[after]`. Relax the bean condition to `TirWatcherCondition` **or** `ParticipantRemovedCondition`,
  without breaking `TirWatcherTest` or `TrustedIssuersRegistryReader*Test`.
- New `src/main/java/org/fiware/iam/dataspace/ParticipantReconciler.java`. For each `removalPublishers()` entry with a
  `registry-url`:
  1. local = `issuersGrantedBy(P, DATASPACE_MEMBERSHIP)`
  2. listed = complete enumeration of P's registry; if it is incomplete, skip P with a WARN and count `action=failed`
  3. candidates = local − listed − own id, with `keep` excluded (and counted `kept` when newly seen); set the
     `held` gauge for P to the size of local − listed − own id
  4. write candidates = the candidates whose `RemovalPlan` has writes (in `report` mode: none)
  5. if `write candidates > effectiveMaxRemovalsPerRun()`: trip the breaker (alert semantics, see *Decisions*), record
     the candidates in the report as not applied, apply nothing
  6. otherwise clear the breaker and run `ParticipantRemoval.apply(P, did, RECONCILIATION)` for each candidate,
     sequentially. Log and count a candidate only if it is newly seen in the report store or its action changed. A
     failure of one candidate is logged once, with `DownstreamError.reason(e)` and the exception attached, and does not
     stop the others.

  Publishers are independent: one failing does not stop the rest. Before the first run, the sweep re-checks unverified
  TIL capabilities (Step 7) and skips the run while they are unverified or too old.
- New `ParticipantRemovedHealthIndicator` (as `TirWatcherHealthIndicator`): DOWN while the TIL capabilities are
  unverified or too old, DEGRADED while a breaker is tripped (details per publisher), else UP.
- New `ParticipantReconcilerScheduler`: its own `micronaut.executors.participant-reconciler` (type scheduled) and
  `@Named` `TaskScheduler`. A self-rescheduling one-shot chain (as in `TirWatcherScheduler` /
  `DataSpaceEventSubscriber`) runs after `effectiveInitialDelay()` and then `effectiveInterval()` after each run ends,
  so a refreshed interval takes effect. Add the executor to `src/main/resources/application.yaml`.
- Tests: `ParticipantReconcilerTest`, parameterized:
  - a removed identifier is applied with the publisher's mode; an identifier still listed is untouched; keep; own id
  - the breaker at cap, cap + 1 and a cap of 0; `report`-mode candidates beyond the cap do not trip it; a standing
    trip logs the ERROR and counts `breaker` once, and clearing it logs INFO
  - a `report`-mode drift counted once across several runs, and the `held` gauge
  - an incomplete registry; one publisher failing does not stop the next
  - a publisher without `registry-url` is skipped

  `ParticipantReconcilerSchedulerTest` uses `RecordingTaskScheduler`. Add an HTTP test of the reader against a stub
  registry URL, following `TrustedIssuersRegistryReaderHttpTest`.

**Acceptance:** an identifier whose membership grant from P is still held at the TIL but that P's registry no longer
lists is handled with P's policy at start-up and on every interval, within the cap.

### Step 5: adopt-unattributed
- Adoption is allowed only if exactly **one** publisher in `removalPublishers()` exists and it has
  `adopt-unattributed: true`. With two or more publishers it is refused:
  - At start-up and after each refresh (handled in the sweep run), log a WARN once per change of the configuration,
    e.g. `adopt-unattributed is ignored: 2 publishers announce participant removals, an unattributed entry cannot be
    attributed to one of them`.
  - Count `action=adoption_refused`, and mark the publisher in the report (Step 6) as `adoption: refused`.

  Put this logic in a small `AdoptionPolicy` helper on the properties or in `dataspace/`.
- **Sweep** (`ParticipantReconciler`): when adoption is allowed, each entry in `allIssuers() ∩ listed(P)` that holds
  *only* unattributed credentials (no grants) is adopted. First `grantMembership(did, P, credentials of getIssuer)`, then
  `revokeUnattributed(did)`, in that order, so a failure in between leaves a duplicate but no loss, and the next run
  converges. Entries that hold grants and unattributed credentials together (mixed) cannot be split, because
  `CredentialsVO` carries no scope. They are reported as unattributed and not adopted. Adoptions count as
  `action=adopted`. They are not under the removal breaker; `max-adoptions-per-run` (Step 1 config, default 50, clamped
  to `>= 0`) applies the first N candidates per run, and the rest follow on later runs.
- **Event path** (`RemovalPlan`): the same rule as the sweep. When adoption is allowed, an entry that holds *nothing but*
  unattributed credentials counts as P's membership; in `dataspace`/`granted` it is removed with `revokeUnattributed`.
  A mixed entry's unattributed credentials stay untouched in every mode except `full`. When adoption is refused, all
  unattributed credentials stay untouched.
- Tests (parameterized): one publisher adopts, in the sweep and the event path; two publishers refuse it and say so
  (log captured or a health/report flag asserted, no TIL write); a mixed entry is not adopted and, in the event path,
  keeps its unattributed credentials; more adoption candidates than `max-adoptions-per-run` adopts the first N and the
  rest on the next run; adoption leaves no loss when `revokeUnattributed` fails; the membership grant is written with
  `scopeType=dataspace-membership`.

**Acceptance:** `adopt-unattributed: true` with one publisher adopts; with two publishers it refuses and says so.

### Step 6: Removed-participants report and GET :9090/participants-removed
- Response (`@Serdeable` records in `dataspace/`, no OpenAPI change since it is a management endpoint): a list of
  `{publisher, mode, adoption: allowed|refused|off, breaker: tripped|clear, participants: [{id, trigger:
  event|reconciliation, lastSeen, action, applied: bool, attributable: bool, held: [{scope, scopeType,
  credentialCount}], unattributedCount}]}`.
- New `src/main/java/org/fiware/iam/dataspace/RemovedParticipantsReport.java`, a `@Singleton` in-memory store keyed by
  (publisher, did). `ParticipantRemoval` and `ParticipantReconciler` update it with the `HeldEntry` read after the
  action (or before it, when nothing was applied). Per publisher it is bounded by a named constant (`MAX_ENTRIES_PER_PUBLISHER`,
  1000), evicting the oldest entries. Uses the injected `Clock`. Not persisted: after a restart the sweep refills the
  attributable entries. It also answers "newly seen or action changed" for the sweep's counting (Step 4); entries the
  sweep no longer finds as candidates are dropped from that check, so a reappearance counts again.
- New `RemovedParticipantsEndpoint`: `@Endpoint(id = "participants-removed", defaultSensitive = false)`,
  `@Requires(condition = ParticipantRemovedCondition)`, one `@Read` method delegating to the report. Unauthenticated, so
  it relies on the management port being cluster-internal, like `PublishEndpoint`.
- Management-port guard: extend `ManagementPortGuard` to `PublisherCondition` **or** `ParticipantRemovedCondition`
  (or add a sibling guard) and name the endpoint in its message, so the report can never land on the public port.
- Tests: `RemovedParticipantsReportTest` covers updates, eviction, the attributable flag and the newly-seen check. Add an
  HTTP test of the endpoint on the management port, following the `PublishEndpoint` tests, with entries in `report`
  mode showing what is still held, and assert that the main port answers `404` for the path.

**Acceptance:** in every mode, including `report`, each removed identifier appears with its action, what is still held
and whether it is attributable.

### Step 7: Start-up guard for policy and TIL capabilities, documentation
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemovedStartupGuard.java`, a `@Context` bean with
  `@Requires(condition = ParticipantRemovedCondition)`, following `TirWatcherStartupGuard`. It throws a
  `ConfigurationException` that names the key and the value:
  - Configuration checks: an override with a blank id or no mode; a `registry-url` that is not an absolute http(s) URL;
    timing, cap and paging values outside their range (use the `requireEffective` pattern); a DID on both `keep` and
    `overrides`, as a WARN only.
  - TIL capability probes. The TIL has no version endpoint, so the guard sends side-effect-free requests whose answers
    differ between versions:
    - Typed scopes and the scope list filter (needed by every mode, since even `report` lists what is held):
      `GET /issuer?scopeType=dataspace-membership` without a scope must answer `400`. A `2xx` means the parameter is
      ignored, i.e. the TIL is too old.
    - `unscoped` (only if a publisher has `adopt-unattributed`): `DELETE /issuer/{probe-did}/credential?unscoped=true&scope=<random UUID>`
      must answer `400`, because the combination is rejected. A `404`/`204` means too old. It is harmless on an old
      list, since the random scope matches nothing.

    Before implementing the probes, confirm them against `FIWARE/trusted-issuers-list` `TrustedIssuersListController`
    (the `requireScope` / unscoped validation messages). Put the probes into `TrustedIssuersListAdapter` as
    `Mono<Boolean> supportsTypedScopes()` / `supportsUnscoped()`.
  - A definitive "too old" answer fails start-up with a `ConfigurationException`. The message names the required
    capability and the trusted-issuers-list ticket (TIL-1/2 for typed scopes and `/scopes`, TIL-3 for `unscoped`).
  - An unreachable TIL (connection error, timeout, `5xx`) does **not** fail start-up. The guard logs a WARN and leaves
    the capability *unverified* in a small `TilCapabilities` holder. `ParticipantRemovedHealthIndicator` reports DOWN
    while it is unverified. The sweep re-probes before each run and skips the run while unverified; the event path
    re-probes before its first write and answers `502` while unverified. A later "too old" answer is logged as ERROR
    once, keeps the health DOWN and blocks all writes (start-up can no longer fail at that point).
- Docs:
  - `README.md`: a new section *Reacting to participant removals* after *Watching the trust anchor registry*. It covers
    the modes table, keep/overrides, multi-membership, who writes membership grants (exact `scope=<publisher id>`,
    `scopeType=dataspace-membership`; CM itself only through adoption), adoption and its per-run limit, the sweep and
    its breaker, the convergent cleanup with the `502` and retry, the metric with its tag values and the `held` gauge,
    the management endpoint `GET :9090/participants-removed`, the required TIL version, and the **upgrade impact**:
    deployments whose publishers already list participant.removed in `types` activate the feature (in `report` mode,
    reads only), and start-up fails if the TIL answers too old.
  - `src/main/resources/application.yaml`: commented example.
  - `AGENTS.md`: list the new classes under `dataspace/`.
- Tests: `ParticipantRemovedStartupGuardTest`, parameterized over invalid configurations, and capable / too old /
  unreachable TIL answers with a mocked `IssuerApiClient` (unreachable: start-up succeeds, health DOWN, sweep and
  event writes blocked until a later probe succeeds). A context test checks that start-up fails with a too-old TIL
  when the mode is `dataspace` or `adopt-unattributed` is set.

**Acceptance:** a configured mode that needs a newer trusted-issuers-list makes start-up fail with a clear message; an
unreachable TIL does not.
`mvn verify` is green.
