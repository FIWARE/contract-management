# Implementation Plan: [CM] Reacting to a removal: attribution, cleanup, reconciliation and the report

## Overview
Make a participant act on `org.fiware.dataspace.participant.removed` (CM-5 of the data space eventing epic). A
per-publisher `on-participant-removed` policy (`report` by default, or `dataspace` / `granted` / `full`, plus `keep`,
`overrides`, `max-removals-per-run`, `adopt-unattributed`) is applied in two places: to incoming events, and in a
reconciliation sweep that runs per publisher at start-up and on a timer. Every outcome is reported through a log line,
the metric `dsc_dataspace_events_participant_removed_total{publisher,action,mode}` and `GET /events/participants/removed`.
The service fails at start-up when a configured mode needs a newer trusted-issuers-list.

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
  | unattributed credentials | keep | keep¹ | keep¹ | issuer deleted |

  ¹ When the entry has been adopted (`adopt-unattributed`, Step 5), its unattributed credentials count as P's membership.

  An identifier on `keep` is never touched, by the event path or by the sweep. An override for an identifier replaces
  the publisher's mode for that identifier.
- **No partial application.** All reads come first, so a failed read changes nothing. Writes follow in this order:
  order grants and unattributed credentials first, P's membership grant **last**. If a write fails, the entry therefore
  still carries P's membership, and both the publisher's retry and the next sweep find it again. Every write is
  idempotent (`404` = already done). A retry recomputes the plan from the current TIL state and converges.
- **Failure answer.** If a TIL call fails while an event is handled, `EventListener` answers `502 Bad Gateway`
  (outcome `downstream_failed`) instead of `500`, and the deduplicator forgets the event so that the publisher retries.
- **Refreshable configuration.** `DataSpaceEventsProperties` is refreshable. Read the policy through the bean on
  every use and never cache it (see the class Javadoc). Follow the `TirWatcherSettings` pattern: `effective*()` getters
  clamp out-of-range values after a refresh, and the start-up guard rejects them.
- **Map keys.** DIDs contain `:` and `.`, which break Micronaut property-map keys. So `overrides` is a **list** of
  `{id, mode}`, not a map.
- **Metric.** Micrometer name `dsc.dataspace.events.participant.removed`, exported to Prometheus as
  `dsc_dataspace_events_participant_removed_total`. Tags: `publisher` (a configured publisher id), `mode` (the
  effective mode, lower case) and `action`. `action` takes a fixed set of values: `reported`, `kept`, `not_held`,
  `revoked_membership`, `revoked_granted`, `deleted_issuer`, `adopted`, `adoption_refused`, `failed` and `breaker`. All
  tag values are bounded.
- **Mass-removal cap.** `max-removals-per-run` acts as a breaker, like `tir-watcher.max-removals-per-cycle`. If a sweep
  finds more candidates than the cap, it applies none of them, reports all of them and logs an ERROR. Applying the
  first N per run was rejected: a wrongly truncated registry would then empty the TIL a few entries at a time. Events
  are not capped, because the trust anchor's own watcher already applies a breaker before it announces anything.
- **Report endpoint.** The new endpoint goes into `api/dataspace-events.yaml` on the main port, next to `POST /events`
  (OpenAPI-first). The README states that the gateway must route only `POST`/`OPTIONS /events` publicly, because the
  report shows local trust.

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
  case. Make sure the lower-case YAML values bind; if Micronaut's enum conversion does not accept them, add a
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
    publisher's membership, order grant, unattributed, the combinations, not held). Covers keep and override.
  - Multi-membership test: an identifier held by P1 and P2 and removed by P1 keeps P2's grant in every mode except `full`.
  - `ParticipantRemovalTest` (Mockito adapter): the writes and their order; the metric tags; a failing read gives no
    write; a failing first write gives no further write, keeps P's membership grant and raises the exception.
  - `EventListenerTest`: a TIL failure answers `502`, and a redelivery is then handled again.

**Acceptance:** all mode × origin, keep/override and multi-membership tests pass. A failing TIL call answers `502` and
leaves P's membership grant in place.

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
  3. candidates = local − listed − own id, with `keep` excluded (and counted `kept`)
  4. if `candidates > effectiveMaxRemovalsPerRun()`: ERROR with the counts, count `action=breaker`, record the
     candidates in the report as not applied, apply nothing
  5. otherwise `ParticipantRemoval.apply(P, did, RECONCILIATION)` for each candidate, sequentially. A failure of one
     candidate is logged once, with `DownstreamError.reason(e)` and the exception attached, and does not stop the others.

  Publishers are independent: one failing does not stop the rest.
- New `ParticipantReconcilerScheduler`: its own `micronaut.executors.participant-reconciler` (type scheduled) and
  `@Named` `TaskScheduler`. A self-rescheduling one-shot chain (as in `TirWatcherScheduler` /
  `DataSpaceEventSubscriber`) runs after `effectiveInitialDelay()` and then `effectiveInterval()` after each run ends,
  so a refreshed interval takes effect. Add the executor to `src/main/resources/application.yaml`.
- Tests: `ParticipantReconcilerTest`, parameterized:
  - a removed identifier is applied with the publisher's mode; an identifier still listed is untouched; keep; own id
  - the breaker at cap, cap + 1 and a cap of 0
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
  converges. Entries that hold grants and unattributed credentials together cannot be split, because `CredentialsVO`
  carries no scope. They are reported as unattributed and not adopted. Adoptions count as `action=adopted` and also
  respect the cap.
- **Event path** (`RemovalPlan`): when adoption is allowed, the unattributed credentials of the removed identifier count
  as P's membership. In `dataspace`/`granted` they are removed with `revokeUnattributed`, before the membership
  revocation. When adoption is refused they stay untouched.
- Tests (parameterized): one publisher adopts, in the sweep and the event path; two publishers refuse it and say so
  (log captured or a health/report flag asserted, no TIL write); a mixed entry is not adopted; adoption leaves no loss
  when `revokeUnattributed` fails; the membership grant is written with `scopeType=dataspace-membership`.

**Acceptance:** `adopt-unattributed: true` with one publisher adopts; with two publishers it refuses and says so.

### Step 6: Removed-participants report and GET /events/participants/removed
- `api/dataspace-events.yaml`: add `GET /events/participants/removed` (operationId `getRemovedParticipants`). Response:
  a list of `{publisher, mode, adoption: allowed|refused|off, participants: [{id, trigger: event|reconciliation,
  lastSeen, action, applied: bool, attributable: bool, held: [{scope, scopeType, credentialCount}],
  unattributedCount}]}`. The models are generated with the `VO` suffix.
- New `src/main/java/org/fiware/iam/dataspace/RemovedParticipantsReport.java`, a `@Singleton` in-memory store keyed by
  (publisher, did). `ParticipantRemoval` and `ParticipantReconciler` update it with the `HeldEntry` read after the
  action (or before it, when nothing was applied). Per publisher it is bounded by a named constant (`MAX_ENTRIES_PER_PUBLISHER`,
  1000), evicting the oldest entries. Uses the injected `Clock`. Not persisted: after a restart the sweep refills the
  attributable entries.
- New `RemovedParticipantsController` (`@Controller("${general.basepath:/}")`,
  `@Requires(condition = ParticipantRemovedCondition)`) implementing the generated API method. If the generated
  `EventsApi` now contains the new operation, implement it in `EventListener` by delegating to the report instead, or
  give the operation its own tag so that a separate `*Api` interface is generated. Prefer the separate tag.
- Tests: `RemovedParticipantsReportTest` covers updates, eviction and the attributable flag. Add an HTTP test of the
  endpoint, following `SubscriptionControllerHttpTest`, with entries in `report` mode showing what is still held.

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
  - If the TIL is unreachable, retry with backoff for at most `participant-removed.startup-probe-timeout` (default 60 s,
    a named constant), then fail. The message names the required capability and the trusted-issuers-list ticket (TIL-1/2
    for typed scopes and `/scopes`, TIL-3 for `unscoped`).
- Docs:
  - `README.md`: a new section *Reacting to participant removals* after *Watching the trust anchor registry*. It covers
    the modes table, keep/overrides, multi-membership, adoption, the sweep and its breaker, the `502` and retry, the
    metric, the report endpoint and the gateway note, and the required TIL version.
  - `src/main/resources/application.yaml`: commented example.
  - `AGENTS.md`: list the new classes under `dataspace/`.
- Tests: `ParticipantRemovedStartupGuardTest`, parameterized over invalid configurations, and capable / too old /
  unreachable TIL answers with a mocked `IssuerApiClient`. A context test checks that start-up fails with a too-old TIL
  when the mode is `dataspace` or `adopt-unattributed` is set.

**Acceptance:** a configured mode that needs a newer trusted-issuers-list makes start-up fail with a clear message.
`mvn verify` is green.
