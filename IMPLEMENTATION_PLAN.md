# Implementation Plan: [CM] Reacting to a removal: attribution, cleanup, reconciliation and the report

## Overview
Make a participant act on `org.fiware.dataspace.participant.removed` (CM-5 of the data space eventing epic). A
per-publisher `on-participant-removed` policy (`report` by default, or `dataspace` / `granted` / `full`, plus `keep`,
`overrides`, `max-removals-per-run`, `adopt-unattributed`) is applied in two places: to incoming events, and in a
reconciliation sweep that runs per publisher at start-up and on a timer. Every outcome is reported through a log line,
the metric `dsc_dataspace_events_participant_removed_total{publisher,action,mode}` (plus a gauge of the standing drift) and
the read-only management endpoint `GET :9090/participants-removed`. The service fails at start-up when the
trusted-issuers-list answers that it is too old for a configured writing mode or adoption; `report` only degrades, and
an unreachable list does not stop start-up.

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
  | order grants (untyped scopes) | keep | keep | revoke² | issuer deleted |
  | unattributed credentials, nothing else held | keep | keep¹ | keep¹ | issuer deleted |
  | unattributed credentials next to grants (mixed) | keep | keep | keep | issuer deleted |

  ¹ Revoked instead when adoption is allowed (`adopt-unattributed`, Step 5) and the identifier comes from an **event**:
  P's explicit announcement attributes an entry that holds *nothing but* unattributed credentials to P. A mixed entry is
  never adopted or revoked, because `CredentialsVO` carries no scope and its credentials cannot be split.
  **Confirmed by the ticket owner** as a deliberate departure from the literal acceptance line "unattributed entries
  survive every mode but `full`". It applies only when adoption is allowed (the operator opted into
  `adopt-unattributed`, declaring that unattributed entries of this participant belong to P's data space); without
  adoption, that acceptance line holds as written. `RemovalPlanTest` names the case explicitly
  (`unattributedOnlyEntryIsRevokedByEventWhenAdoptionAllowed`), and the README states it.

  ² Only when the entry is attributed to P **alone**: it holds P's membership grant and no other publisher's
  membership grant. Order grants carry no data space, so they are attributed through the entry's memberships. If X
  also holds P2's membership, its order grants may come from contracts within P2's data space and are kept; if X holds
  no membership at all, nothing ties them to P and they are kept and reported. The rows are therefore not independent:
  `RemovalPlan` decides the order-grant row from the membership rows. A later removal by P2 (now the only membership)
  revokes them, so the cleanup still converges. Revoking them for any removing publisher was rejected, because a
  removal by P1 would then take away trust that P2's data space still needs. **To be confirmed by the ticket owner**;
  the alternative is one `RemovalPlan` branch.

  **The sweep never removes unattributed entries** (deliberate asymmetry). It acts only on entries that hold P's
  membership grant (`local`). An unattributed-only entry that P no longer lists is indistinguishable from an
  operator-managed entry that was never in P's data space, so the sweep leaves it alone; only an event, which names the
  identifier, removes it. With adoption allowed the gap is small: every listed unattributed-only entry is adopted
  (Step 5) and, once delisted, is a normal sweep candidate. Only an entry delisted before it was adopted stays until an
  event names it.

  An identifier on `keep` and this participant's **own id** (`DataSpaceEventsProperties.ownId(generalProperties)`:
  `own-participant-id`, falling back to `general.did`, as the `TirWatcher` resolves it) are never touched, by the event
  path, the sweep or adoption. Both are excluded inside `removalMode`, and every path (adoption included, Step 5)
  drops identifiers for which it is empty, so none can forget it: a `full` event about our own removal never deletes
  our own issuer entry, and an operator-provisioned unattributed own entry is never adopted. An event whose `subject`
  is the own id never reaches the handler: `EventAuthorizer` already answers it with `SELF` (`EventListener.skipSelf`,
  INFO, no handler call), so it is not counted by the new metric. `mode=keep, action=kept` for the own id therefore
  appears only for an event that names it through the `data.participantId` fallback and in the sweep; the README and
  the metric description say so. An override for an identifier replaces the publisher's mode for that identifier.

  **`keep` protects the identifier against every publisher.** It is configured under a publisher's
  `on-participant-removed`, but `removalMode(P, X, ownId)` is empty when X is on the `keep` list of *any* removal
  publisher. Otherwise P1's `keep` of X would not stop P2 in `full` from deleting X's whole issuer entry, P1's
  membership included, which breaks the ticket's "an identifier on `keep` is never touched in either path". To stop
  only P's removals from writing while other publishers still act, use an override `{id: X, mode: report}` under P;
  that is the per-publisher tool, `keep` is the participant-wide one. The log line of a `kept` decision names the
  publisher whose `keep` applied (or the own id).
- **Stale removal events.** An event can arrive late: the publisher retries a `502` for as long as the TIL is down,
  and X may be re-admitted in the meantime with a fresh membership grant from P. The TIL stores no grant timestamps,
  so the event's `time` cannot be compared with anything. Instead, before any write, the event path asks P's registry
  (`GET {registry-url}/v4/issuers/{did}`, the TIR spec's `getIssuerV4`) whether X is listed again. If it is, nothing is
  written: `action=still_listed`, `2xx`. A `404` means delisted and the cleanup proceeds; any other answer fails with
  `TirLookupException` (`TIR_LOOKUP_FAILED`), answered `502`, so the publisher retries. The check runs only for
  writing modes (`report` writes nothing to protect) and only when P has a `registry-url`; without one the race stays,
  the README names it, and the start-up guard WARNs for a writing mode without `registry-url`. A registry that lags
  behind the announcement also yields `still_listed`; the next sweep (which reads `local − listed` fresh) then
  completes the cleanup, so nothing is lost. The sweep itself needs no extra lookup: its window between the listing and
  the writes is one run.
- **No empty issuer entries.** `deleteCredentialsByScope` keeps the issuer "even when no credential remains", so a
  `dataspace`/`granted` cleanup (or the adoption-path `revokeUnattributed`) can leave an issuer with zero credentials,
  still listed in the TIL's TIR view. That is not harmless: FIWARE VCVerifier (`verifier/trustedissuer.go`,
  `verifyWithCredentialsConfig`) skips base types such as `VerifiableCredential` that have no entry, so for an
  existing issuer without credentials a credential typed only with base types would pass. So when a writing plan would
  leave the entry empty, its **final write is replaced by `deleteIssuer`** (`404` = done): `RemovalPlan` decides it
  from the `HeldEntry`, and `ParticipantRemoval` re-reads the entry just before that final write and falls back to
  the scope-targeted revoke if anything else appeared in the meantime. Because the final write is P's membership
  revoke whenever P's membership is held, P's membership survives until the delete succeeds, so a failed delete is
  still found by the retry and the next sweep (the convergence rule above holds). An entry that is already empty when
  read in a writing mode (e.g. a crash after an earlier revoke) is deleted as well. A credential written between the
  re-read and the delete is lost; this is the same accepted race as in adoption and the README names it. `report`
  mode writes nothing and deletes nothing. The report never shows a held-but-empty entry: after such a cleanup it
  shows the mode's revoking action (`revoked_membership`/`revoked_granted`; `deleted_issuer` stays reserved for
  `full`) with `held: []` and `unattributedCount: 0`, and an event about an identifier absent from the TIL shows
  `action=not_held` with the same empty `held`.
- **Who writes membership grants.** Contract-management writes a `dataspace-membership` grant in exactly one place:
  adoption (Step 5, `TrustedIssuersListAdapter.grantMembership`). Its order paths (`TilProductOrderHandler`,
  `ContractManagementController`) write untyped order grants only. Other components **will** write membership grants
  (confirmed by the ticket owner), e.g. onboarding or helm-provisioned entries of the data-space-connector. They must
  write them as `scope=<publisher id>` (exactly the publisher's `id` from `dataspace.events.publishers`, i.e. the
  `source` of its events) with `scopeType=dataspace-membership`, one grant per data space. The README (Step 7) gets a
  subsection *Writing membership grants* with these exact values, an example `PUT /issuer/{did}/credential` request
  body, and what happens to entries written differently (untyped scope = treated as an order grant; no scope =
  unattributed, only reported).
- **Convergent cleanup, membership revoked last** (confirmed by the ticket owner as meeting "nothing half-applied":
  the TIL has no multi-operation transactions, so it cannot be met literally). All reads come first,
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
  is on `keep` or is the own id, so `removalMode` is empty; always paired with `action=kept`; the log line names which
  of the two, and for `keep` which publisher's list). Outcomes without an identifier (`breaker`,
  `adoption_refused`, a `failed` registry read) carry the publisher's own mode. `action` takes a fixed set of values:
  `reported`, `kept`, `not_held`, `still_listed`, `revoked_membership`, `revoked_granted`, `deleted_issuer`, `adopted`,
  `adoption_refused`, `failed` and `breaker`. All tag values are bounded; the README lists them.
  The counter counts decisions, not sweep runs: the event path counts every handled event, the sweep counts an
  identifier only when it is newly seen for that publisher or its effective mode changed (keyed on the sweep's own
  `seen` set, Step 4, not on the evicting report store), so a `report`-mode deployment does not add the same drift
  every interval. The standing drift is a gauge
  `dsc.dataspace.events.participant.removed.held{publisher}` (Prometheus `dsc_dataspace_events_participant_removed_held`):
  the identifiers still holding P's membership although P's registry no longer lists them, set by each complete sweep.
- **Mass-removal cap.** `max-removals-per-run` acts as a breaker, like `tir-watcher.max-removals-per-cycle`. It counts
  only the write candidates; `report`-mode, `keep` and own-id candidates never trip it. This is decided from
  configuration alone, without TIL reads: every sweep candidate is in `local`, so it holds P's membership grant, and it
  is a write candidate exactly when `removalMode` is `dataspace`, `granted` or `full`. `HeldEntry`s are read only for
  candidates that are applied or newly reported (Step 4), so a tripped breaker on a truncated registry costs one
  listing per run, not one read per candidate. If a sweep
  finds more write candidates than the cap, it applies none of them and reports all of them. The breaker is an alert in
  the `TirWatcher` style: ERROR and `action=breaker` once when it trips (or the candidate set changes), INFO when it
  clears, and a health indicator stays DEGRADED in between, so a standing trip does not log an ERROR every interval.
  Applying the first N per run was rejected: a wrongly truncated registry would then empty the TIL a few entries at a
  time. Events are not capped, because the trust anchor's own watcher already applies a breaker before it announces
  anything.
- **Adoption limit.** Adoption is not a removal and is not under the breaker. It has its own
  `max-adoptions-per-run` (default 50) that applies the first N candidates per run and continues on the next run.
  Truncation is safe here, because adoption only adds a membership grant before it removes the duplicate unattributed
  credentials. Adoption re-reads the entry before the removal and skips it if the unattributed credentials changed
  since they were copied (Step 5); the TIL has no conditional delete, so a credential added between that re-read and
  the delete is still lost. This remaining window is an accepted race, documented in the README.
- **Adoption assumes one data space.** An unattributed entry can only be attributed to P if this participant belongs to
  P's data space alone. Adoption is therefore allowed (`DataSpaceEventsProperties.adoption()`, Step 1) only when `dataspace.events.dataspace-id` is set (the operator
  declares the instance's single data space) **and**
  `removalPublishers()` contains exactly one publisher, which has `adopt-unattributed: true`. Counting all
  configured publishers instead was rejected: `dataspace.events.publishers` also lists peers that publish other event
  types within the same data space, so that rule would refuse adoption in every real deployment. The code does not
  enforce the one-data-space assumption: `EventAuthorizer.isOtherDataspace` rejects an event only when both the event
  and the configuration name a data space, and events without `dataspaceid` pass. The assumption rests on the
  operator's publisher configuration. The README says exactly that (adoption assumes membership in one data space; a
  participant in several data spaces must not enable it; the event filter does not detect a second data space) and
  promises nothing more.
- **Report endpoint.** The report shows which issuers this participant trusts under which scopes, so it is not served on
  the main port next to the publicly routed `POST /events`. It is a read-only micronaut-management endpoint
  `@Endpoint(id = "participants-removed", defaultSensitive = false)` with a `@Read` method on the cluster-internal
  management port (9090), following `PublishEndpoint`. A management-port guard (as `ManagementPortGuard`) fails start-up
  when `endpoints.all.port` is unset or equals the server port. No OpenAPI change is needed. This departs from the
  ticket's `GET /events/participants/removed` (path and port). The ticket owner has not objected since the change
  (comment 11168), but has not explicitly confirmed it either; **to be confirmed**. If the ticket's path is required,
  only Step 6 changes.
- **TIL capabilities** (holder and probes in Step 2, used by Steps 3–7). The TIL has no version endpoint, so
  side-effect-free probes decide each required capability. There are three: `typed-scopes` (the typed list filter,
  TIL-1), `scopes-endpoint` (`GET /issuer/{did}/scopes`, TIL-2) and `unscoped` (TIL-3). `/scopes` gets its own probe
  because a list may have the filter without it (TIL-1 without TIL-2, or a proxy that does not route the path), and
  every mode, `report` included, reads it through `heldEntry`. `TilCapabilities` keeps one state per capability:
  `SUPPORTED`, `TOO_OLD` or `UNVERIFIED`. Answers map as follows:
  | probe answer | state |
  |---|---|
  | `400` whose body contains the expected validation message (a named constant, confirmed against the TIL source) | `SUPPORTED` |
  | any other `400`, `404`, `405`, any other `4xx` except those below, any `2xx` | `TOO_OLD` |
  | `401`, `403`, `408`, `429` (not a version signal) | `UNVERIFIED` |
  | `5xx`, connection error, timeout | `UNVERIFIED` |

  The `scopes-endpoint` probe asks for an issuer that cannot exist, so its positive answer is a `404`, not a `400`: it
  is `SUPPORTED` only on a `404` whose body is the TIL's `ProblemDetails` with the issuer-not-found message (a named
  constant, confirmed against the TIL source). Any other `404` (a route that does not exist: Micronaut's
  `Page Not Found`, a proxy page, an empty body) is `TOO_OLD`; the other rows of the table apply unchanged.

  Required capabilities follow the current (refreshable) configuration: `typed-scopes` and `scopes-endpoint` always;
  `unscoped` only while adoption is **allowed** (`adoption()`, Step 1). A refused adoption makes no `unscoped` call, so
  it requires nothing; the refusal is already reported through its WARN and `action=adoption_refused`. While a required capability is not `SUPPORTED`, every TIL access of this feature
  fails with `TrustedIssuersException` (new `FailureReason`s `TIL_CAPABILITY_UNVERIFIED` / `TIL_TOO_OLD`, naming the
  capability), so the `TrustedIssuersException` → `502` mapping that Step 3 adds to `EventListener.dispatch` covers
  events (today `dispatch` answers every handler error with `500`). `UNVERIFIED` is re-probed
  before each sweep run and before each event is handled (at most once per `PROBE_RETRY_INTERVAL`, 30 s); `TOO_OLD` is
  final until a restart or a refresh.
- **Start-up and an old or unreachable TIL.** `ParticipantRemovedCondition` activates on configuration that existing
  deployments may already have (a publisher whose `types` include participant.removed). So:
  - Start-up fails only when the TIL definitively answers `TOO_OLD` **and** the configuration needs writes: a publisher
    mode or an override other than `report`, or an allowed adoption (a refused `adopt-unattributed` writes nothing). That is what the ticket's "a configured mode
    needs a newer TIL" means; `report` is the default, not a choice to fail on.
  - A `report`-only configuration with a `TOO_OLD` TIL degrades instead: ERROR logged once, health DOWN, the report
    endpoint shows `til: too-old`, no sweep runs.
  - With a `TOO_OLD` TIL, an event is decided by **its own** effective mode (`removalMode(P, did, ownId)`), not by the
    global configuration: if that mode is `report`, the event is accepted (`2xx`, nothing to retry because `report`
    writes nothing) and recorded as `action=reported` with `held` unknown. This also holds after a refresh that mixes
    P1 in `report` with P2 in `dataspace`, so P1's events are not retried forever. Events whose effective mode writes
    answer `502`.
  - An unreachable TIL never stops start-up: health DOWN, sweep runs skipped, and events answer `502` (the publisher
    retries) until a re-probe succeeds.
  - A refresh that switches to a writing mode while the TIL is `TOO_OLD` cannot fail start-up any more: ERROR once,
    health DOWN, all writes blocked. The README calls out this upgrade impact.

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
    - `@Nullable String registryUrl`: base URL of this publisher's trusted issuers registry, used by the sweep and by
      the event path's stale-event check (*Decisions*). Without it the publisher gets neither.
  - Add a `ParticipantRemovedSettings participantRemoved` (`@ConfigurationProperties("participant-removed")`) with the
    sweep timing `reconcile.initial-delay` (default 1 min) and `reconcile.interval` (default 15 min). Give each an
    `effective*()` getter, as `TirWatcherSettings` does. Also add `page-size`/`max-pages` for reading the TIL and the
    publisher registries, reusing the `TirWatcherSettings.MIN/MAX_PAGE_SIZE` bounds.
  - Add helpers on the properties:
    - `removalPublishers()`: the publishers with a non-blank id whose `types` contain the participant.removed type.
      Move `TirWatcher.PARTICIPANT_REMOVED_TYPE` to a shared constant or reference it.
    - `removalMode(publisherId, did, ownId)`: the effective mode. Returns empty when the DID is on the `keep` list of
      **any** removal publisher (see *Decisions*) or equals `ownId` (callers pass `ownId(generalProperties)`; a blank
      own id excludes nothing); otherwise an override of `publisherId` wins over its mode. A companion
      `keptBy(did)` returns the publisher whose `keep` applies, for the log line.
    - `adoption()`: the `AdoptionPolicy` record `{State state (ALLOWED|REFUSED|OFF), @Nullable String reason}` under
      the rule in *Decisions* (*Adoption assumes one data space*). `OFF` when no removal publisher sets
      `adopt-unattributed`; `REFUSED` with the reason (more than one removal publisher, or no `dataspace-id`)
      otherwise; `ALLOWED` only for exactly one removal publisher with the flag and a `dataspace-id`. Pure configuration,
      so `TilCapabilities` (Step 2), `RemovalPlan` (Step 3) and the sweep (Step 5) all read the same answer.
    - `removalWritesConfigured()`: true if any removal publisher has a mode or an override other than `report`, or
      `adoption()` is `ALLOWED`. The start-up guard (Step 7) fails on a too-old TIL only then.
  - Add `ParticipantRemovedCondition`: the listener is enabled **and** at least one publisher is authorized for
    participant.removed.
- New enum `org.fiware.iam.configuration.RemovalMode` (`REPORT, DATASPACE, GRANTED, FULL`), with a `tagValue()` in lower
  case, and a constant `KEEP_TAG_VALUE = "keep"` for the `mode` tag of identifiers on `keep`. Make sure the lower-case YAML values bind; if Micronaut's enum conversion does not accept them, add a
  `TypeConverter`.
- `src/main/resources/application.yaml`: extend the commented `dataspace.events` example with an `on-participant-removed`
  block and `participant-removed.reconcile`.
- Tests (`src/test/java/org/fiware/iam/configuration/DataSpaceEventsPropertiesTest.java`, parameterized):
  `removalMode` covers the default, the publisher mode, an override beating the mode, `keep` beating an override,
  P1's `keep` excluding the DID for P2 in `full` (and P2's override), and the own id excluded even with a `full`
  override. `adoption()` covers off, allowed, refused for two removal
  publishers and for a missing `dataspace-id`, and a peer publisher for other types that does not refuse it.
  `removalWritesConfigured` covers report-only, a writing mode, a writing override, allowed adoption (true) and refused
  adoption with report-only (false). Also cover the effective clamps, `removalPublishers`, the binding of lower-case modes and of an `overrides` list with
  DIDs (a small `ApplicationContext.run(Map)` test).

**Acceptance:** `mvn test` is green. Without configuration, nothing changes for existing deployments.

### Step 2: TrustedIssuersListAdapter extensions for attribution and cleanup
Extend `src/main/java/org/fiware/iam/til/TrustedIssuersListAdapter.java`, leaving the order paths (`allowIssuer`,
`denyIssuer`) unchanged. Check the generated `IssuerApiClient` signatures in `target/generated-sources` first.
- `Mono<Optional<HeldEntry>> heldEntry(String did)`: reads `getIssuer` first, then `getIssuerScopes`. Only a `404` of
  `getIssuer` means "not held" (empty). A `404` of `getIssuerScopes` after `getIssuer` answered `200` means the
  `/scopes` endpoint is missing (an old TIL or a proxy), not that nothing is held: it fails with `TIL_READ_FAILED`, so
  an event answers `502` and is retried instead of being recorded as `not_held`. With the `scopes-endpoint` probe this
  is only a backstop (e.g. a proxy route removed after start-up).
  The two reads are not atomic. If the derived `unattributedCount` (credentials of `getIssuer` minus Σ
  `credentialCount`) is negative, the entry changed between the reads: fail with `TIL_READ_FAILED` (DID and both
  counts in the message), so the event answers `502` and is retried, or the next sweep reads it again, instead of
  acting on an impossible state. A non-negative but stale count can only come from a concurrent grant or revoke and
  errs to the state after it (a grant added in between makes the entry look mixed or granted, so nothing unattributed
  is touched; a grant revoked in between leaves an entry that really is unattributed-only by then). Every write that
  acts on it is scope-targeted, and adoption re-reads before it revokes (Step 5).
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
- Capability probes `Mono<TilCapability.State> probeTypedScopes()` / `probeUnscoped()`, classified by the table in
  *Decisions* (*TIL capabilities*):
  - typed scopes and the list filter: `GET /issuer?scopeType=dataspace-membership` without a scope; `SUPPORTED` only
    on `400` with the `requireScope` validation message.
  - `scopes-endpoint` (`probeScopesEndpoint()`): `GET /issuer/{PROBE_DID}/scopes` with a DID that cannot exist
    (`PROBE_DID_PREFIX` + random UUID); `SUPPORTED` only on the issuer-not-found `404` described in *Decisions*.
  - `unscoped`: `DELETE /issuer/{PROBE_DID}/credential?unscoped=true&scope=<random UUID>`; `SUPPORTED` only on `400`
    with the unscoped/scope validation message. Harmless on an old list, since the random scope matches nothing.

  Before implementing, confirm all three requests and the exact messages against `FIWARE/trusted-issuers-list`
  `TrustedIssuersListController` and keep the messages as named constants.
- Adoption (Step 5) needs the TIL to hold the same credentials twice for one issuer: once under P's membership scope
  and once unscoped (`C + C`). At entity level this is confirmed: `TrustedIssuer.credentials` is a `Collection`, each
  `Credential` row has its own generated id and its own `scope`/`scopeType`, and the entity has no unique constraint
  on (issuer, `credentialsType`). Before implementing `grantMembership`, also confirm that the database migrations
  add no such unique index and that `replaceCredentialsByScope` (controller and `TILMapper`) replaces only the
  credentials of the given scope without merging identical `CredentialsVO`s across scopes. If either does, adoption
  cannot work as planned: stop and report it (`blocked`, `[plan-update]`) instead of working around it.
- New `src/main/java/org/fiware/iam/til/TilCapabilities.java` (`@Singleton`): holds the state per capability, derives
  the required set from the current `DataSpaceEventsProperties` (`typed-scopes` and `scopes-endpoint` always,
  `unscoped` only while `adoption()` is `ALLOWED`), and offers `Mono<Void> requireSupported()`. That re-probes `UNVERIFIED` capabilities (throttled
  by `PROBE_RETRY_INTERVAL`, injected `Clock`) and fails with `TrustedIssuersException` (`TIL_CAPABILITY_UNVERIFIED`
  or `TIL_TOO_OLD`, naming the capability) unless all required ones are `SUPPORTED`. Also `state()` for health and
  report, and a log of the transition to `TOO_OLD` (ERROR, once). A refresh resets `TOO_OLD` to `UNVERIFIED`, so an
  upgraded TIL is picked up without a restart.
- Wrap every failure in `TrustedIssuersException` and keep the cause. Use new `FailureReason`s
  (`TIL_READ_FAILED`, `TIL_REVOKE_FAILED`, `TIL_CAPABILITY_UNVERIFIED`, `TIL_TOO_OLD`) with the DID, scope and type in
  the message. Do not log inside the adapter (log once, see AGENTS.md).
- Update the class Javadoc: the class now also grants and revokes data-space memberships.
- Tests (`src/test/java/org/fiware/iam/til/TrustedIssuersListAdapterTest.java`, Mockito on `IssuerApiClient`,
  parameterized):
  - `404` handling for each write
  - the error wrapping
  - paging, covering one page, several pages, a short last page, an inconsistent total and too many pages
  - the `HeldEntry` classification, covering membership of P, membership of another publisher, untyped grants and
    unattributed credentials; `404` of `getIssuer` gives empty, `404` of `getIssuerScopes` after a `200` of `getIssuer`
    gives `TIL_READ_FAILED`; a negative derived `unattributedCount` (Σ `credentialCount` larger than the credential
    list) gives `TIL_READ_FAILED`
  - `grantMembership` sends credentials identical to existing unscoped ones (the `C + C` case) under
    `scopeType=dataspace-membership` and leaves the unscoped ones alone
  - that order grants are still written untyped
  - every row of the probe classification for all three probes: `400` with and without the expected message, `404`,
    `405`, another `4xx`, `401`/`403`/`408`/`429`, `2xx`, `5xx`, connection error, timeout; for `scopes-endpoint` the
    issuer-not-found `404` (`SUPPORTED`) against a route-not-found `404`, a proxy `404` and an empty `404` (`TOO_OLD`)
- `TilCapabilitiesTest`: required set per configuration (`unscoped` required with allowed adoption, not with refused
  adoption); `requireSupported` fails with the right reason per state; an
  `UNVERIFIED` capability is re-probed, but not within `PROBE_RETRY_INTERVAL` (`MutableClock`); `TOO_OLD` is logged once
  and reset by a refresh.

**Acceptance:** the adapter and `TilCapabilities` offer every call that Steps 3–7 need, and the existing order tests
still pass.

### Step 3: ParticipantRemovedHandler: policy, cleanup, 502 and per-decision logging/metric
- New `src/main/java/org/fiware/iam/dataspace/RemovalPlan.java`: a pure function from `(RemovalMode | keep, HeldEntry,
  publisherId)` to an ordered list of TIL operations plus the resulting `action`. It implements the table in
  *Decisions* and puts P's membership revocation last; when the result would be an empty entry, the final operation is
  `deleteIssuer` (*No empty issuer entries*); `RemovalPlanTest` covers it per mode × origin. The order-grant row depends on the membership rows (footnote
  ²): order grants are revoked in `granted` only when the entry holds P's membership and no other.
- New `src/main/java/org/fiware/iam/dataspace/RemovedParticipantsReport.java`: the in-memory report store, without
  the endpoint (Step 6), created here so that Steps 3–5 can record into it. A `@Singleton` keyed by (publisher, did);
  each entry holds trigger, `lastSeen`, mode tag, action, `applied` and the `HeldEntry` (or `null` when it was not
  read). `ParticipantRemoval` and `ParticipantReconciler` (Step 4) update it with the `HeldEntry` read after the action
  (or before it, when nothing was applied). Per publisher it is bounded by a named constant
  (`MAX_ENTRIES_PER_PUBLISHER`, 1000), evicting the oldest entries. Uses the injected `Clock`. Not persisted: after a
  restart the sweep refills the attributable entries. It does **not** decide "newly seen" for the sweep; that is the
  sweep's own unevicted `seen` set (Step 4), so eviction here cannot make the sweep repeat counts or reads.
  Publisher-level state (TIL capability, adoption, breaker) is not stored here; the endpoint (Step 6) reads it from
  its owners when it renders.
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemoval.java` (`@Singleton`,
  `@Requires(condition = ParticipantRemovedCondition)`), shared by the event path and the sweep. Its
  `Mono<RemovalOutcome> apply(String publisherId, String did, Trigger trigger)` works in this order:
  1. Resolve the mode (`removalMode`, own id included). For `keep`/own id: count `kept` and stop, no TIL access.
     Otherwise `TilCapabilities.requireSupported()`; if it fails with `TIL_TOO_OLD` and this identifier's effective
     mode is `report`, record `reported` with `held` unknown instead of failing (decided per event, not from the global
     configuration; see *Decisions*). For a writing mode, trigger `EVENT` and a `registry-url`: ask
     `PublisherRegistryLookup.isListed` (*Stale removal events*); if listed, record `still_listed` and stop without TIL
     reads or writes. Then read the `HeldEntry` (all reads first).
  2. Build the plan and run its writes sequentially (`concatMap`). When the plan would leave the entry empty, its
     final write is `deleteIssuer`, preceded by a re-read that falls back to the scope-targeted revoke if the entry
     gained anything (*No empty issuer entries*).
  3. Write one INFO line, e.g. `Participant <did> removed by <publisher> (mode <m>, <trigger>): <action>; still held: <grants>`,
     and record the outcome in `RemovedParticipantsReport`.
  4. Count `dsc.dataspace.events.participant.removed{publisher,action,mode}`, using the `Optional<MeterRegistry>`
     fallback that `EventListener` uses.

  On failure it counts `action=failed` and passes on a `ParticipantRemovalException` (new `ContractManagementException`
  subclass with reason `PARTICIPANT_REMOVAL_FAILED`, holding the publisher and DID and keeping the TIL cause). It does
  not log, since the failure is logged once in `EventListener`.
- New `src/main/java/org/fiware/iam/dataspace/PublisherRegistryLookup.java` (`@Singleton`,
  `@Requires(condition = ParticipantRemovedCondition)`): `Mono<Boolean> isListed(String registryUrl, String did)` with
  the low-level `HttpClient`, `GET {registryUrl}/v4/issuers/{did}` (DID URL-encoded). `200` → true, `404` → false,
  anything else or an IO error/timeout → new `TirLookupException` (`ContractManagementException` subclass, new
  `FailureReason` `TIR_LOOKUP_FAILED`, with DID, registry URL and status, keeping the cause). Step 4 may reuse its
  client for `enumerate(registryUrl)`.
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemovedHandler.java`, a `DataSpaceEventHandler` for the
  participant.removed type:
  - The participant is `event.getSubject()`, falling back to `data.participantId` (see `TirWatcher.PARTICIPANT_ID_KEY`).
    An event that has neither raises a new `InvalidEventException` (`ContractManagementException` subclass, new
    `FailureReason` `INVALID_EVENT`, naming the event id and source), created in this step.
  - The publisher is `event.getSource()`, which `EventAuthorizer` has already matched against the caller and the
    configured publishers.
- `EventListener.dispatch`: today every handler error answers `500` and calls `deduplicator.forget(key)`. Split by the
  cause chain:
  - `InvalidEventException`: `400` (outcome `invalid_event`), and `deduplicator.complete(key)` instead of `forget`,
    because a redelivery of the same event can never succeed. Logged once at WARN.
  - `TrustedIssuersException` or `TirLookupException`: `502` (outcome `downstream_failed`), `forget`, so the
    publisher retries.
  - anything else: `500` and `forget`, unchanged.
- Tests (`src/test/java/org/fiware/iam/dataspace/`):
  - `RemovalPlanTest`: parameterized `@MethodSource` over mode × origin of the entry (P's membership, another
    publisher's membership, order grant, unattributed only, mixed grants + unattributed, the combinations, not held),
    each with adoption allowed and not allowed. Covers keep (`mode=keep`, `action=kept`) and override. A separately
    named case, `unattributedOnlyEntryIsRevokedByEventWhenAdoptionAllowed`, shows the footnote ¹ departure: an
    unattributed-only entry from an event is revoked in `dataspace`/`granted` only with adoption allowed, and survives
    without it.
  - Multi-membership test: an identifier held by P1 and P2 and removed by P1 keeps P2's grant in every mode except `full`.
    Footnote ² is pinned by named cases in `granted`, removed by P1: `P1 membership + order grants` revokes both
    (membership last); `P2 membership + order grants` keeps everything; `P1 + P2 membership + order grants` revokes only
    P1's membership and keeps the order grants; `order grants only` keeps them (`action=reported`, nothing attributable to P).
    `keep` across publishers: X on P1's `keep`, held by P1 and P2, removed by P2 in `full` (and in `dataspace`) makes
    no TIL call and counts `mode=keep, action=kept`; an override `{X, report}` under P1 instead keeps only P1's
    removals from writing, while P2's removal in `full` still deletes the issuer.
  - `RemovedParticipantsReportTest`: updates, eviction at `MAX_ENTRIES_PER_PUBLISHER` and the attributable flag.
  - `ParticipantRemovalTest` (Mockito adapter): the writes and their order; the metric tags; a failing read gives no
    write; a failing first write gives no further write, keeps P's membership grant and raises the exception; a
    retry against the resulting TIL state completes the cleanup. An event about the own id (`own-participant-id` and
    the `general.did` fallback) with mode `full` and with a `full` override makes no TIL call and counts
    `mode=keep, action=kept`. An unverified capability raises `TrustedIssuersException` before any read; with a
    `TOO_OLD` TIL, an event whose effective mode is `report` records `reported` with `held` unknown, also when another
    publisher (or an override) is in a writing mode, while an event whose effective mode writes raises the exception.
    Stale events (`PublisherRegistryLookup` mocked): X listed again → `still_listed`, no TIL read or write; `404` → the
    cleanup runs; a lookup failure raises `TirLookupException` before any TIL call; `report` mode, a publisher without
    `registry-url` and the `RECONCILIATION` trigger make no lookup. Empty entries: a `dataspace`/`granted` cleanup that
    would leave nothing ends with `deleteIssuer` instead of the membership revoke, and the report shows the revoking
    action with `held: []`; one that leaves another grant does not delete; a grant appearing before the re-read turns
    the delete back into the scope-targeted revoke; an already empty entry is deleted in a writing mode; `report` mode
    never deletes; a failing `deleteIssuer` keeps P's membership, raises the exception, and the retry completes.
  - `PublisherRegistryLookupTest` (HTTP stub, as `TrustedIssuersRegistryReaderHttpTest`): `200`, `404`, `5xx`, a
    connection error and a DID that needs URL encoding.
  - `EventListenerTest` (parameterized over the three failure kinds): a TIL failure, a registry lookup failure and an
    unverified capability answer `502` and a redelivery is handled again; an event without a participant answers `400` and its redelivery is a
    duplicate (`202`, not dispatched again); another handler error still answers `500`.

**Acceptance:** all mode × origin (including the mixed entry), keep/override and multi-membership tests pass. A failing
TIL call answers `502`, P's membership grant survives, and a retry completes the cleanup. A late event about a
re-admitted identifier writes nothing, and no cleanup leaves an empty issuer entry.

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
  3. candidates = local − listed; split by `removalMode(P, did, ownId)` (no TIL reads): empty → `kept` (keep or own
     id, counted when newly seen), `report` → report candidates, `dataspace`/`granted`/`full` → write candidates. Set
     the `held` gauge for P to the number of candidates that are not the own id
  4. if `write candidates > effectiveMaxRemovalsPerRun()`: trip the breaker (alert semantics, see *Decisions*), record
     the write candidates in the report (`RemovedParticipantsReport`, Step 3) as not applied, apply nothing. The
     per-publisher breaker state is exposed by `ParticipantReconciler` for the health indicator and the endpoint (Step 6)
  5. otherwise clear the breaker and run `ParticipantRemoval.apply(P, did, RECONCILIATION)` for each write candidate,
     sequentially. A failure of one candidate is logged once, with `DownstreamError.reason(e)` and the exception
     attached, and does not stop the others.
  6. candidates not applied (report candidates, and write candidates while the breaker is tripped) are logged,
     counted and read (`heldEntry`, for the report) only when they are not in the `seen` set or their effective mode
     changed; already-known ones only get `lastSeen` updated. At most `MAX_NEW_CANDIDATES_PER_RUN` (200) are read and
     added to `seen` per run; the rest stay unseen and are handled on the next runs, so every candidate is read once
     and the reads per run stay bounded.

  The `seen` set is per publisher, held by `ParticipantReconciler` and separate from the report store (Step 3): a map
  DID → effective mode tag. After each complete run it is pruned to the current candidates, so it is bounded by
  `local` and needs no eviction, and an identifier that leaves the candidates and comes back counts again. The report
  store's eviction (`MAX_ENTRIES_PER_PUBLISHER`) therefore only drops details from the report, never repeats a count,
  a log line or a read. Not persisted: after a restart every candidate counts once more.

  The sweep never touches unattributed entries outside adoption (see the footnote in *Decisions*). Publishers are
  independent: one failing does not stop the rest. Each run starts with `TilCapabilities.requireSupported()` (Step 2)
  and skips the run (WARN once per state change) while a required capability is not `SUPPORTED`.
- New `ParticipantRemovedHealthIndicator` (as `TirWatcherHealthIndicator`): DOWN while a required TIL capability
  (`TilCapabilities`, Step 2) is `UNVERIFIED` or `TOO_OLD`, DEGRADED while a breaker is tripped (details per
  publisher), else UP.
- New `ParticipantReconcilerScheduler`: its own `micronaut.executors.participant-reconciler` (type scheduled) and
  `@Named` `TaskScheduler`. A self-rescheduling one-shot chain (as in `TirWatcherScheduler` /
  `DataSpaceEventSubscriber`) runs after `effectiveInitialDelay()` and then `effectiveInterval()` after each run ends,
  so a refreshed interval takes effect. Add the executor to `src/main/resources/application.yaml`.
- Tests: `ParticipantReconcilerTest`, parameterized:
  - a removed identifier is applied with the publisher's mode; an identifier still listed is untouched; keep (also
    another publisher's `keep`); own id; the sweep makes no `PublisherRegistryLookup` call
  - the breaker at cap, cap + 1 and a cap of 0; `report`-mode candidates beyond the cap do not trip it; a standing
    trip logs the ERROR and counts `breaker` once, and clearing it logs INFO; a tripped breaker makes no `heldEntry`
    read for already-known candidates (verify the adapter calls)
  - a `report`-mode drift counted and read once across several runs, re-counted after a mode change; the `held` gauge
  - more candidates than `MAX_ENTRIES_PER_PUBLISHER`: across runs each is counted and read exactly once (no repeats
    after the report store evicts); more new candidates than `MAX_NEW_CANDIDATES_PER_RUN`: the rest follow on the next
    run; a candidate that leaves and comes back counts again
  - an unattributed-only entry that P no longer lists is not touched by the sweep
  - a run is skipped while `TilCapabilities` is unverified or too old, and runs again once a probe succeeds
  - an incomplete registry; one publisher failing does not stop the next
  - a publisher without `registry-url` is skipped

  `ParticipantReconcilerSchedulerTest` uses `RecordingTaskScheduler`. Add an HTTP test of the reader against a stub
  registry URL, following `TrustedIssuersRegistryReaderHttpTest`.

**Acceptance:** an identifier whose membership grant from P is still held at the TIL but that P's registry no longer
lists is handled with P's policy at start-up and on every interval, within the cap.

### Step 5: adopt-unattributed
- Adoption is allowed only under the rule in *Decisions* (*Adoption assumes one data space*): `dataspace-id` is set
  and `removalPublishers()` contains exactly one publisher, which has `adopt-unattributed: true`. Otherwise, if any
  publisher asks for it, it is refused:
  - At start-up and after each refresh (handled in the sweep run), log a WARN once per change of the configuration,
    naming the reason, e.g. `adopt-unattributed is ignored: 2 publishers announce participant removals, an unattributed
    entry cannot be attributed to one of them` or `... dataspace.events.dataspace-id is not set`.
  - Count `action=adoption_refused`. The report (Step 6) shows `adoption: refused` by reading `adoption()` when it
    renders, so this step stores nothing for it.

  The decision itself is `DataSpaceEventsProperties.adoption()` (Step 1); this step adds the WARN and the metric.
- **Sweep** (`ParticipantReconciler`): when adoption is allowed, the adoption candidates are
  `(allIssuers() ∩ listed(P)) − local`, minus every DID for which `removalMode(P, did, ownId)` is empty (`keep` and
  the own id are never adopted, see *Decisions*). `local` (step 1 of the sweep) already holds P's membership, so
  adopted entries are never read again. Each candidate whose `heldEntry` holds *only* unattributed credentials (no
  grants) is adopted:
  1. `grantMembership(did, P, C)`, where `C` is the credential list read from `getIssuer`.
  2. Re-read the entry (`heldEntry` and `getIssuer`). Continue only if it now holds exactly P's membership grant with
     `|C|` credentials and its unattributed credentials are still exactly `C` (the issuer's credentials are the
     multiset `C + C`). Otherwise an operator changed the entry in the meantime: skip the revoke, log a WARN naming the
     DID, and leave the duplicate in place. Nothing is lost, and the operator resolves the duplicate.
  3. `revokeUnattributed(did)`.

  A failure between the steps leaves a duplicate but no loss. The TIL has no conditional delete, so a credential added
  between the re-read and the revoke is still deleted; that window is the accepted race from *Decisions*. Entries that hold grants and unattributed credentials together (mixed) cannot be split, because
  `CredentialsVO` carries no scope. They are reported as unattributed and not adopted. Adoptions count as
  `action=adopted`. They are not under the removal breaker; `max-adoptions-per-run` (Step 1 config, default 50, clamped
  to `>= 0`) applies the first N candidates per run, and the rest follow on later runs.
  Candidates found not adoptable (mixed, or holding grants only) go into a per-publisher `notAdoptable` map
  (DID → time of the read, injected `Clock`), held by `ParticipantReconciler` next to `seen` and pruned to the current
  adoption candidates after each run. They are not read again until `NOT_ADOPTABLE_RECHECK_INTERVAL` (24 h) has passed,
  so an entry that later loses its grants is still adopted eventually. At most `MAX_NEW_CANDIDATES_PER_RUN` adoption
  candidates are read per run (the rest follow on the next runs); a steady data space costs no reads per interval
  beyond the 24 h rechecks.
- **Event path** (`RemovalPlan`): when adoption is allowed, an entry that holds *nothing but* unattributed credentials
  counts as P's membership, because the event names it; in `dataspace`/`granted` it is removed with `revokeUnattributed`.
  The sweep does not do this for delisted unattributed entries (asymmetry stated in *Decisions*).
  A mixed entry's unattributed credentials stay untouched in every mode except `full`. When adoption is refused, all
  unattributed credentials stay untouched.
- Tests (parameterized): one publisher adopts, in the sweep and the event path; two publishers, or a missing
  `dataspace-id`, refuse it and say so (log captured or a health/report flag asserted, no TIL write); a peer publisher
  configured for other types only does not refuse it; an unattributed-only entry listed by P that is on `keep` or is
  the own id (`own-participant-id` and the `general.did` fallback) is not adopted and gets no TIL call; an entry whose
  unattributed credentials change between the grant and the re-read keeps them (no revoke, WARN); an unattributed-only entry that P no longer lists is removed by
  an event but not by the sweep; a mixed entry is not adopted and, in the event path,
  keeps its unattributed credentials; more adoption candidates than `max-adoptions-per-run` adopts the first N and the
  rest on the next run; adoption leaves no loss when `revokeUnattributed` fails; the membership grant is written with
  `scopeType=dataspace-membership`; an already adopted entry (in `local`) and a known mixed entry make no `heldEntry`
  read on the next run (verify the adapter calls), and a mixed entry is read again after
  `NOT_ADOPTABLE_RECHECK_INTERVAL` (`MutableClock`).

**Acceptance:** `adopt-unattributed: true` with one publisher adopts; with two publishers it refuses and says so.

### Step 6: Removed-participants report and GET :9090/participants-removed
- Response (`@Serdeable` records in `dataspace/`, no OpenAPI change since it is a management endpoint): a list of
  `{publisher, mode, til: supported|unverified|too-old, adoption: allowed|refused|off, breaker: tripped|clear,
  participants: [{id, trigger: event|reconciliation, lastSeen, mode, action, applied: bool, attributable: bool|null,
  held: [{scope, scopeType, credentialCount}] | null, unattributedCount}]}`. `held`/`attributable` are `null` when the
  entry was not read (own id/keep, `still_listed`, or a `report`-mode event against a `TOO_OLD` TIL); `held: []` with
  `unattributedCount: 0` means nothing is held (after a cleanup that deleted the emptied issuer, or `not_held`).
- New `RemovedParticipantsEndpoint`: `@Endpoint(id = "participants-removed", defaultSensitive = false)`,
  `@Requires(condition = ParticipantRemovedCondition)`, one `@Read` method that assembles the response from the
  report store (Step 3), `TilCapabilities.state()` (Step 2), `adoption()` (Step 1) and the reconciler's breaker state
  (Step 4). Unauthenticated, so
  it relies on the management port being cluster-internal, like `PublishEndpoint`.
- Management-port guard: extend `ManagementPortGuard` to `PublisherCondition` **or** `ParticipantRemovedCondition`
  (or add a sibling guard) and name the endpoint in its message, so the report can never land on the public port.
- Tests: an HTTP test of the endpoint on the management port, following the `PublishEndpoint` tests, with entries in `report`
  mode showing what is still held, and assert that the main port answers `404` for the path.

**Acceptance:** in every mode, including `report`, each removed identifier appears with its action, what is still held
and whether it is attributable.

### Step 7: Start-up guard for policy and TIL capabilities, documentation
- New `src/main/java/org/fiware/iam/dataspace/ParticipantRemovedStartupGuard.java`, a `@Context` bean with
  `@Requires(condition = ParticipantRemovedCondition)`, following `TirWatcherStartupGuard`. It throws a
  `ConfigurationException` that names the key and the value:
  - Configuration checks: an override with a blank id or no mode; a `registry-url` that is not an absolute http(s) URL;
    timing, cap and paging values outside their range (use the `requireEffective` pattern); as WARNs only, a DID on
    any publisher's `keep` that also appears in any `overrides`, and a publisher in a writing mode (or with a writing
    override) without `registry-url` (no stale-event check, no sweep).
  - TIL capabilities (probes and `TilCapabilities` from Step 2): the guard probes the required capabilities once.
    - `TOO_OLD` **and** `removalWritesConfigured()`: start-up fails with a `ConfigurationException` naming the
      capability, the configured mode or the allowed adoption that needs it, and the trusted-issuers-list ticket
      (TIL-1 for `typed-scopes`, TIL-2 for `scopes-endpoint`, TIL-3 for `unscoped`).
    - `TOO_OLD` in a `report`-only configuration: no failure; ERROR once, the degraded behaviour from *Decisions*.
    - `UNVERIFIED`: no failure; WARN, and `TilCapabilities` re-probes later (Steps 3 and 4).
- Docs:
  - `README.md`: a new section *Reacting to participant removals* after *Watching the trust anchor registry*. It covers
    the modes table (with footnote ²: order grants are revoked in `granted` only for an entry attributed to P alone),
    keep/overrides (`keep` protects against every publisher; an override `report` is the per-publisher alternative),
    stale events (`still_listed` via `registry-url`; without it a late event can revoke a fresh grant), empty issuer
    entries are deleted after a cleanup (why, and the race of a credential written just before the delete),
    the own id (an event whose `subject` is the own id is answered `SELF` and never counted; only the
    `data.participantId` fallback and the sweep count it as `mode=keep`), multi-membership, a subsection *Writing membership grants* for other
    components (exact `scope=<publisher id>`, `scopeType=dataspace-membership`, an example request, what happens to
    untyped or unscoped entries; CM itself writes them only through adoption), adoption with its one-data-space
    assumption, per-run limit, the exclusion of `keep`/own id and the accepted race (a credential added between the
    re-read and the revoke is lost), the sweep (it never removes unattributed entries; only events do) and
    its breaker, the convergent cleanup with the `502` and retry, the metric with its tag values and the `held` gauge,
    the management endpoint `GET :9090/participants-removed`, the required TIL version, and the **upgrade impact**:
    deployments whose publishers already list participant.removed in `types` activate the feature (in `report` mode,
    reads only, degrading instead of failing on a too-old TIL), and start-up fails only if a writing mode or
    adoption is configured and the TIL answers too old.
  - `src/main/resources/application.yaml`: commented example.
  - `AGENTS.md`: list the new classes under `dataspace/`.
- Tests: `ParticipantRemovedStartupGuardTest`, parameterized over invalid configurations and over
  `SUPPORTED` / `TOO_OLD` / `UNVERIFIED` × `report`-only / writing mode / writing override / allowed adoption / refused
  adoption (behaves as `report`-only), the two WARNs (cross-publisher `keep`/override, writing mode without
  `registry-url`), with a mocked
  `TilCapabilities` (the probe classification itself is tested in Step 2). Unreachable: start-up succeeds, health
  DOWN, sweep and event writes blocked until a later probe succeeds. A context test checks that start-up fails with a
  too-old TIL when the mode is `dataspace` or adoption is allowed, and succeeds with `report` only or a refused adoption.

**Acceptance:** a configured writing mode or adoption that needs a newer trusted-issuers-list makes start-up fail with
a clear message; `report` only and an unreachable TIL do not.
`mvn verify` is green.
