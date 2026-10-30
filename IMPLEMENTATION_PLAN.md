# Implementation Plan: [CM] Subscriber: registration and renewal at every configured publisher

## Overview
Add the client side of the CM-2 `POST /subscriptions` API. A `DataSpaceEventSubscriber` modelled on
`tmforum/notification/NotificationSubscriber` registers this CM's public `POST /events` listener as a webhook sink at every
configured publisher. It does so at startup and whenever a refresh (CM-1) adds a publisher. It retries failures with
backoff, renews each lease before it expires, and reports through a health indicator like `SubscriptionHealthIndicator`.
Base: `ticket-100/work` (CM-1 + CM-2 merged).

### Decisions (resolving the analysis questions)
1. **Publisher subscriptions URL:** a new optional `subscriptions-url` on each `dataspace.events.publishers[]` entry. Entries
   without it are listener-only: they may still deliver to us, but we never subscribe to them. The URL is never derived
   from the publisher id or origin (D-8).
2. **Own sink:** `dataspace.events.subscriber.sink`, the public URL of this CM's `POST /events`. It is required when the
   subscriber is enabled. The subscriber also requires `listener.enabled`, because the publisher's `OPTIONS` handshake hits
   our listener. Both are checked at startup.
3. **Types and origin:** each publisher is subscribed for its configured `types`. Its `origin` must be set as well:
   during the publisher's `OPTIONS` handshake, our `EventListener` answers `WebHook-Allowed-Origin` only for an origin
   configured on a `publishers[]` entry (`DataSpaceEventsProperties.allowedOrigin`). Without it every registration would fail
   with an opaque `400 handshake failed` and retry forever. An entry with `subscriptions-url` but no types or a blank
   `origin` is therefore skipped with a WARN at reconcile time (not at startup, because publishers are refreshable). It is
   not contacted, does not affect the health status, and shows as `SKIPPED` with the reason in the health details. A
   wrong-but-present origin cannot be detected locally; the README states that `origin` must equal the publisher's
   `dataspace.events.publisher.origin`.
4. **Credentials:** these are two different pairs of values.
   - `subscriber.protocol-settings.client-id` / `.scopes` go into the request's `protocolsettings`. They are the OID4VP client
     at **our** verifier that the publisher uses when our sink answers 401. `client-id` is required (the API requires it).
   - `subscriber.client-id` / `.scopes` are what **we** use for OID4VP towards the publisher's subscriptions API (set as
     `CLIENT_ID_ATTRIBUTE`/`SCOPE_ATTRIBUTE`). Each publisher entry may override them with optional `client-id` / `scopes`.
   - `subscriberId` = `DataSpaceEventsProperties.ownId(generalProperties)`. It must match the participant claim the publisher's
     verifier puts in our token.
5. **Enable flag and timing:** `subscriber.enabled` is startup only. Everything else is refreshable and read through the bean
   on every use, with named defaults:
   - `initial-delay` `PT5S`: delay before the first registration after startup.
   - `retry.initial-backoff` `PT5S` and `retry.max-backoff` `PT5M`: exponential backoff with no attempt limit, because a
     publisher may be down for a long time.
   - `renewal-fraction` `0.5`: renew after this fraction of the remaining lease (`expiresAt - now`). A value outside the open
     interval `(0, 1)` falls back to the default: `>= 1` would renew at or after expiry, `<= 0` would renew every
     `min-renewal-delay`.
   - `min-renewal-delay` `PT10S`: lower bound on the renewal delay, so a tiny or past `expiresAt` cannot cause a tight loop.
   - `conflict-renewal-interval` `PT1H`: when to re-register after a `409`, since that response has no `expiresAt`.
   - `reconcile-interval` `PT1M`: periodic backstop reconcile (see Step 4).
6. **409:** counts as success (acceptance criterion): it does not degrade the health status, and the publisher is
   re-registered after `conflict-renewal-interval`. **Review note:** a CM-2 publisher answers `409` when its subscription
   cap is reached, not for an existing subscription (it renews in place with `201`). This rule therefore mainly serves
   other publishers. To keep the cap case visible, the publisher's state in the health details is `CONFLICT` (not
   `REGISTERED`), and the INFO line says "409 treated as existing subscription".
7. **Refresh changes:**
   - An added publisher is registered without a restart.
   - A publisher whose `subscriptions-url`, `types`, `origin` or credentials changed is re-registered immediately. So is
     every publisher when `sink`, `protocol-settings` or the global `client-id`/`scopes` changed.
   - CM-2 keys subscriptions by `(subscriberId, sink)`. A changed `sink` or `subscriptions-url` would therefore leave the old
     subscription live (it keeps receiving deliveries until its lease expires and counts against
     `maxSubscriptionsPerSubscriber`, which can turn the re-POST into a `409`). When the old subscription id is known, the
     re-registration first sends the best-effort `DELETE <old subscriptions-url>/<old id>` with the old snapshot's
     credentials and then POSTs the new one. Deleting first frees the cap slot; the gap is one round trip, and the old sink
     is no longer the one we want deliveries on. A changed `types`, `origin` or credential keeps the same key, so the publisher
     renews in place and no DELETE is sent. Without a known id (never registered, or `409`) nothing can be deleted; the old
     lease expires on its own.
   - A removed publisher, or one that lost its `subscriptions-url`, gets its pending tasks cancelled and is dropped from the
     health check. A best-effort `DELETE <subscriptions-url>/<id>` is sent when the subscription id is known; a failure is
     only logged at INFO, because the lease expires anyway.
8. **Health:** `DataSpaceSubscriptionHealthIndicator`, named `dataspace-subscriptions`. **It never reports `DOWN`.**
   - Why: the publisher's `OPTIONS` handshake reaches us through the Service/ingress. A pod that is not ready never receives
     it, so registration could never succeed (deadlock), and a failing liveness probe would restart the pod in a loop. The
     k3s manifest (`src/test/resources/k3s/contract-management-provider.yaml`) probes plain `/health` for both, and
     deployments may do the same. Checked in the micronaut-management 4.7 source: `/health` and `/health/readiness` both
     aggregate every indicator not qualified `@Liveness` (`HealthEndpoint.getReadinessHealthIndicators`), so excluding the
     indicator from readiness is not possible. Requiring operators to change their probes was rejected because the deadlock
     would come back silently with any chart that keeps `/health`.
   - Status: `UP` when every subscribable publisher holds a registration that has not expired (`REGISTERED`, or `CONFLICT`
     until the next re-registration is due). Otherwise a named constant `DEGRADED` status,
     `new HealthStatus("DEGRADED", <description>, null, null)`: `operational` and `severity` are null, so
     `DefaultHealthAggregator` ranks it above `UP` and below `DOWN` (`HealthStatus.compareTo`), and `HealthResultFilter` keeps
     HTTP 200 (it only answers 503 for a mapped `DOWN` or `operational == false`). A real `DOWN` from another indicator still
     wins the aggregate.
   - This `DEGRADED` result is the "unhealthy" signal of the acceptance criteria. It is asserted on the indicator's result and
     details, and is visible in `/health` (overall status and the `dataspace-subscriptions` entry) without failing a probe.
   - It is `DEGRADED` from startup until the first success. It is also `DEGRADED` when a renewal keeps failing past
     `expiresAt`. A failed renewal before expiry stays `UP`, because the publisher still delivers.
   - Details map each publisher id to its state (`PENDING|REGISTERED|CONFLICT|EXPIRED|SKIPPED`), `expiresAt` and, for
     `SKIPPED`, the reason (Decision 3). No error bodies.
   - With zero subscribable publishers it is `UP`.
9. **HTTP client:** the subscriptions URL is chosen by the operator, not by a participant, so the default `HttpClient` is used
   (not `PublisherHttpClientConfiguration`). Auth goes through `Optional<AuthHandler>.executeWithAuth`, as in
   `ContractManagementAdapter`. No new OpenAPI generation is needed: requests and responses use the existing
   `SubscriptionRequestVO`, `HttpProtocolSettingsVO` and `SubscriptionVO` from `org.fiware.iam.events.model`.

## Steps

### Step 1: Subscriber configuration, condition and startup guard
- `src/main/java/org/fiware/iam/configuration/DataSpaceEventsProperties.java`:
  - Extend `Publisher` with `@Nullable subscriptionsUrl`, `@Nullable clientId` and `@Nullable List<String> scopes`. Add Javadoc
    on the new fields and keep the class's existing style.
  - Add the field `subscriber` of a new nested `SubscriberSettings` class (`@Data`, `@ConfigurationProperties("subscriber")`).
    It holds:
    - `enabled`, `sink`, `clientId`, `scopes`
    - a nested `ProtocolSettings` (`@ConfigurationProperties("protocol-settings")`) with `clientId` and `scopes`
    - `initialDelay`, `renewalFraction`, `minRenewalDelay`, `conflictRenewalInterval`, `reconcileInterval`
    - a nested `Retry` (`@ConfigurationProperties("retry")`) with `initialBackoff` and `maxBackoff`
  - Every default is a `DEFAULT_SUBSCRIBER_*` constant next to the existing ones (Decision 5). Javadoc goes on every field, one
    line each, matching the file.
  - Add `SubscriberCondition` (subscriber enabled), modelled on `PublisherCondition`.
- `src/main/java/org/fiware/iam/dataspace/SubscriberStartupGuard.java` (`@Context`, `@Requires(condition = SubscriberCondition.class)`),
  modelled on `ManagementPortGuard`. It throws a `ConfigurationException` naming the offending key when any of these holds:
  - `listener.enabled` is false;
  - `sink` is blank or not an absolute `http(s)` URI;
  - `protocol-settings.client-id` is blank.

  The publisher entries (types, origin) are not checked here: they are refreshable, so `DataSpaceEventSubscriber` checks
  them on every reconcile (Decision 3).
- Tests:
  - Extend `DataSpaceEventsPropertiesTest` with a parameterized check of the defaults and of the binding of
    `dataspace.events.subscriber.*` and the new `publishers[]` fields.
  - Add `SubscriberStartupGuardTest`, a parameterized `ApplicationContext.run(...)` over: valid → starts; listener disabled,
    blank sink, relative sink, missing protocol client id → fail; subscriber disabled with nothing set → starts.
- Acceptance: `mvn test` is green, and existing listener/publisher behaviour is unchanged.

### Step 2: Publisher subscriptions client
- `src/main/java/org/fiware/iam/dataspace/PublisherSubscriptionClient.java` (`@Singleton`, `SubscriberCondition`), with
  `HttpClient` and `Optional<AuthHandler>` injected:
  - `Mono<Registration> subscribe(DataSpaceEventsProperties.Publisher publisher)`:
    - Builds a `SubscriptionRequestVO` with `subscriberId` = own id, `sink`, `types` = the publisher's types, `protocol` = `HTTP`,
      and `protocolsettings` from the `subscriber.protocol-settings` values.
    - POSTs it to `subscriptions-url` with the client id and scopes resolved from the publisher override or the
      `subscriber.*` default. The `SCOPE_ATTRIBUTE` is a `Set`.
    - Uses `executeWithAuth`, or a plain `exchange` when no handler exists, exactly as in `ContractManagementAdapter`.
  - `Registration` (record): `@Nullable String id` and `@Nullable Instant expiresAt`. Both are null for a 409.
  - Error shapes: a returned non-2xx `HttpResponse` (first call inside `executeWithAuth`), a thrown
    `HttpClientResponseException` (after the token retry, or the no-handler branch), `BadGatewayException`, or a transport
    error.
    - Map them with one function. Reuse `EventDelivery.statusOf` by moving it to a small package-private helper
      `DownstreamStatus` in `dataspace/`, and point `EventDelivery` at it.
    - `2xx` with a body gives a `Registration`. `409` gives an empty `Registration`. Everything else fails with a new
      `EventSubscriptionException` (`exception/`, extends `ContractManagementException`, new
      `FailureReason.EVENT_SUBSCRIPTION_FAILED("event_subscription_failed")`). It carries the publisher id and URL and keeps the
      cause and the status, so `DownstreamError.reason(e)` shows the downstream answer.
  - `Mono<Void> unsubscribe(Publisher publisher, String subscriptionId)`: `DELETE <subscriptions-url>/<id>`, same auth path. A
    `404` counts as success.
- Tests: `PublisherSubscriptionClientTest`, using Mockito `HttpClient`/`AuthHandler` and `@ParameterizedTest` + `@MethodSource`.
  - The request body: own id, sink, types and protocolsettings.
  - Client id and scopes from the default vs. the per-publisher override.
  - Status mapping over both shapes, returned response and thrown exception: `201` → registration with id/expiry, `409` →
    empty registration, `400/401/403/500/503` → `EventSubscriptionException` with the status.
  - `BadGatewayException` and a connection error → exception without a status.
  - `unsubscribe` with `204`, `404` and `500`.
  - `EventDeliveryTest` stays green after the `statusOf` move.

### Step 3: DataSpaceEventSubscriber core and health indicator
- `src/main/java/org/fiware/iam/dataspace/DataSpaceEventSubscriber.java` (`@Context`, `SubscriberCondition`, `@Slf4j`):
  - It implements `ApplicationEventListener<ServerStartupEvent>`. Do not use the `@EventListener` annotation, because the
    simple name clashes with `dataspace.EventListener`.
  - Inject `DataSpaceEventsProperties`, `GeneralProperties`, `PublisherSubscriptionClient`, `TaskScheduler` (named
    `TaskExecutors.SCHEDULED`), `Clock` and the health indicator.
  - State: a `ConcurrentHashMap<String, PublisherState>` keyed by publisher id. `PublisherState` is a small class holding:
    - the `Publisher` snapshot it was registered with (to detect changes in Step 4)
    - `subscriptionId` and `expiresAt`
    - `attempt` (for the backoff)
    - the `ScheduledFuture` of the next task
    - a `generation` counter; a callback whose generation is stale is ignored, so cancelled or replaced loops never act twice.
    Mutations synchronize on the state.
  - `onApplicationEvent(ServerStartupEvent)`: for every publisher with `subscriptions-url`, types and a non-blank `origin`,
    create a `PENDING` state and schedule a registration after `initial-delay`. An entry with `subscriptions-url` but no
    types or no `origin` gets a `SKIPPED` state with the reason and a WARN (Decision 3). Duplicate publisher ids are
    skipped with a WARN.
  - Registration task, which re-reads the current publisher from the properties and stops if it is gone:
    - Success: store the id and `expiresAt` and mark the state `REGISTERED`. Schedule renewal after
      `max(minRenewalDelay, (expiresAt - now) * renewalFraction)`. A missing `expiresAt` is treated like a 409. Log at INFO
      (`Subscribed to <publisher> at <url> until <expiresAt>`).
    - 409: state `CONFLICT`, no id, renewal after `conflict-renewal-interval` (Decision 6).
    - Failure: log a WARN with `DownstreamError.reason(e)` and the next delay, attached once. Schedule a retry after
      `min(maxBackoff, initialBackoff * 2^attempt)`. Negative or zero configured durations fall back to the defaults, as
      `EventDelivery.positiveOrDefault` does, and a `renewal-fraction` outside `(0, 1)` falls back to its default
      (Decision 5). Renewal is the same task, so a failed renewal retries with backoff.
- `src/main/java/org/fiware/iam/dataspace/DataSpaceSubscriptionHealthIndicator.java` (`@Singleton`, `SubscriberCondition`,
  implements `HealthIndicator`):
  - It computes the result from the subscriber's states and the `Clock` (Decision 8). It does not keep its own boolean map, so
    expiry is evaluated at query time.
  - `UP` or the `DEGRADED` constant status, never `DOWN`. Details hold the per-publisher state.
  - A short class Javadoc states why it never reports `DOWN` (probe deadlock).
- Tests: `DataSpaceEventSubscriberTest` uses a mocked client, a fake `TaskScheduler` that records `(delay, Runnable)` and runs
  tasks on demand, and `MutableClock`. Parameterize where the shape repeats.
  - **Acceptance:** the publisher is unavailable at startup. The client fails N times, the retries follow the backoff and are
    capped at `maxBackoff`, and the indicator is `DEGRADED` after every failure. It is `UP` right after the first success.
  - **Acceptance:** renewal is scheduled before `expiresAt`, parameterized over fractions (including `0`, `1`, `1.5` and
    negative values falling back to the default), a tiny lease → `minRenewalDelay`, and a past `expiresAt`. Running the
    task re-registers. A renewal failing past `expiresAt` turns the indicator `DEGRADED`, and the next success turns it `UP`.
  - **Acceptance:** `409` → `UP` with the publisher in state `CONFLICT`, re-registration after `conflict-renewal-interval`.
  - Publishers without `subscriptions-url` are not contacted and not in the details. Entries with `subscriptions-url` but no
    types or a blank `origin` are not contacted, do not affect the status, and show as `SKIPPED` with the reason.
  - A stale-generation callback is ignored.
  - `DataSpaceSubscriptionHealthIndicatorTest`: `UP` with no publishers, `DEGRADED` while pending, the expiry boundary,
    `CONFLICT` counting as `UP`, and the details.
  - Probe test (context with the management port, modelled on `PublishEndpointTest`): with the subscriber
    `DEGRADED` (client mocked to fail), `GET /health`, `/health/readiness` and `/health/liveness` on port 9090 all return
    200, and `/health` shows the `dataspace-subscriptions` entry as `DEGRADED`.

### Step 4: Runtime refresh: added, changed and removed publishers
- `DataSpaceEventSubscriber` also implements `ApplicationEventListener<RefreshEvent>`:
  - The listener can run before Micronaut has rebound `DataSpaceEventsProperties`. It therefore only schedules `reconcile()`
    with zero delay on the `TaskScheduler`.
  - `reconcile()` reads the properties inside `ConfigurationRefreshLock.exclusively(...)`. Both refresh paths
    (`ConfigFileRefresher`, `SerializedRefreshEndpoint`) publish under that lock, so the read waits for the rebind. The
    registrations themselves run outside the lock.
- `reconcile()` compares the subscribable publishers with the states:
  - **added:** create a `PENDING` state and register immediately (**acceptance**: subscribed without a restart).
  - **changed** `subscriptions-url`, `types`, `origin`, `client-id` or `scopes`, or a changed global `sink`/`protocol-settings`/
    `client-id`/`scopes`: bump the generation, cancel the future and re-register immediately. The attempt counter is reset.
    The health stays as it was until the new outcome (the old lease is still valid). If the `sink` or `subscriptions-url`
    changed and an old id is known, the re-registration first sends the best-effort `unsubscribe` for the old URL/id with
    the old snapshot (Decision 7); a failure is logged at INFO and does not stop the POST. The old id is never reused.
  - A publisher that becomes invalid (types or `origin` removed) is treated like a removal (cancel, best-effort
    `unsubscribe`) and then gets a `SKIPPED` state; one that becomes valid again is treated like an addition.
  - **removed** (or the URL dropped): cancel, remove the state (it leaves the health check), and fire a best-effort
    `unsubscribe` when an id is known (Decision 7).
- A periodic backstop: `reconcile()` also runs every `reconcile-interval`, scheduled with `TaskScheduler.scheduleWithFixedDelay`
  at startup. It catches a `RefreshEvent` published outside the lock (e.g. tests, other refresh paths) and refresh keys that
  are rebound late. A reconcile with no differences does nothing.
- Tests:
  - Extend `DataSpaceEventSubscriberTest`, parameterized over add / change types / change origin / change URL / change sink /
    remove / remove URL / remove origin. Each case checks registrations, cancellations, `unsubscribe` calls (old URL and id,
    sent before the new POST, for change URL and change sink only when an id is known; none for change types or after a
    `409`) and the health details.
  - A context test modelled on `EventDeduplicatorTest.refreshedLimitsApplyToTheLiveCache`:
    - Start `ApplicationContext` with the subscriber enabled and a `@MockBean`/`@Replaces` client.
    - Add a publisher to the mutable property source and publish `RefreshEvent(refreshAndDiff())` inside
      `ConfigurationRefreshLock.exclusively`.
    - Use Awaitility to assert that the new publisher is subscribed (**acceptance**). Removing it again drops it from the
      health check.

### Step 5: Documentation and verification
- `README.md`, under `### Data space events`: add a `#### Subscribing to publishers` subsection next to `#### Publishing events`.
  It covers:
  - Enabling the subscriber and its prerequisites (listener enabled, public `sink`, `protocol-settings.client-id`).
  - The `subscriber.*` keys with defaults (incl. the valid `renewal-fraction` range), and the per-publisher
    `subscriptions-url` / `client-id` / `scopes`. A subscribable entry needs `types` and an `origin` that equals the
    publisher's `dataspace.events.publisher.origin`, or the handshake fails.
  - Which client id is used where (Decision 4).
  - Retry/backoff, renewal timing, 409 handling (including the CM-2 cap note and the `CONFLICT` state).
  - Refresh behaviour (added, changed, removed; the DELETE of the old subscription on a sink/URL change).
  - The health indicator `dataspace-subscriptions`: its states, and why it reports `DEGRADED` instead of `DOWN` (it must not
    fail `/health`, readiness or liveness probes, or the publisher's handshake could never reach the pod).
  - A YAML example.
  - Update the refresh paragraph: `subscriber.enabled` is startup-only, all other `subscriber.*` keys are refreshable.
- `src/main/resources/application.yaml`: add a commented `subscriber` block and `subscriptions-url` on the sample publisher,
  next to the existing commented `dataspace.events` sample.
- `AGENTS.md`: list `DataSpaceEventSubscriber`, `PublisherSubscriptionClient`, `DataSpaceSubscriptionHealthIndicator` and
  `SubscriberStartupGuard` in the `dataspace/` line.
- Run `mvn test` and `mvn -DskipITs verify` (or `mvn verify` where k3s is available). Fix regressions in `EventListenerTest`,
  `EventDeliveryTest`, `DataSpaceEventsRefreshTest` and `DataSpaceEventsPropertiesTest`.
- Acceptance: each of the four ticket acceptance criteria maps to a named test from Steps 3–4, and the docs match the
  implemented behaviour.
