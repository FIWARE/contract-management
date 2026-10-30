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
3. **Types:** each publisher is subscribed for its configured `types`. An entry with `subscriptions-url` but no types is
   skipped with a WARN at reconcile time.
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
   - `renewal-fraction` `0.5`: renew after this fraction of the remaining lease (`expiresAt - now`).
   - `min-renewal-delay` `PT10S`: lower bound on the renewal delay, so a tiny or past `expiresAt` cannot cause a tight loop.
   - `conflict-renewal-interval` `PT1H`: when to re-register after a `409`, since that response has no `expiresAt`.
   - `reconcile-interval` `PT1M`: periodic backstop reconcile (see Step 4).
6. **409:** counts as success (acceptance criterion). The publisher is marked registered and re-registered after
   `conflict-renewal-interval`. **Review note:** a CM-2 publisher answers `409` when its subscription cap is reached, not
   for an existing subscription (it renews in place with `201`). This rule therefore mainly serves other publishers. The INFO
   line says "409 treated as existing subscription" so an operator can spot the cap case.
7. **Refresh changes:**
   - An added publisher is registered without a restart.
   - A publisher whose `subscriptions-url`, `types` or credentials changed is re-registered immediately. So is every publisher
     when `sink` or `protocol-settings` changed.
   - A removed publisher, or one that lost its `subscriptions-url`, gets its pending tasks cancelled and is dropped from the
     health check. A best-effort `DELETE <subscriptions-url>/<id>` is sent when the subscription id is known; a failure is
     only logged at INFO, because the lease expires anyway.
8. **Health:** `DataSpaceSubscriptionHealthIndicator`, named `dataspace-subscriptions`.
   - It is UP only when every subscribable publisher holds a registration that has not expired. A 409 counts as unexpired
     until the next re-registration is due.
   - It is DOWN from startup until the first success. It also goes DOWN when a renewal keeps failing past `expiresAt`. A failed
     renewal before expiry stays UP, because the publisher still delivers.
   - Details map each publisher id to its state (`PENDING|REGISTERED|EXPIRED`) and `expiresAt`, without error bodies.
   - With zero subscribable publishers it is UP.
   - **It must not gate readiness:** the publisher's handshake reaches us through the ingress, so a pod that is not ready
     could never register (deadlock). Step 3 checks in the micronaut-management 4.7 source which indicators
     `/health/readiness` aggregates. If this one would be included, exclude it there, for example with the qualifier the
     endpoint filters on. A test asserts that `/health/readiness` stays UP while the subscriber is DOWN. If no exclusion is
     possible, stop and report it in the PR instead of shipping the deadlock.
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
  - `onApplicationEvent(ServerStartupEvent)`: for every publisher with `subscriptions-url` and types, create a `PENDING` state
    and schedule a registration after `initial-delay`. Duplicate publisher ids are skipped with a WARN.
  - Registration task, which re-reads the current publisher from the properties and stops if it is gone:
    - Success: store the id and `expiresAt` and mark the state `REGISTERED`. Schedule renewal after
      `max(minRenewalDelay, (expiresAt - now) * renewalFraction)`. A missing `expiresAt` is treated like a 409. Log at INFO
      (`Subscribed to <publisher> at <url> until <expiresAt>`).
    - 409: same as success, with renewal after `conflict-renewal-interval` (Decision 6).
    - Failure: log a WARN with `DownstreamError.reason(e)` and the next delay, attached once. Schedule a retry after
      `min(maxBackoff, initialBackoff * 2^attempt)`. Negative or zero configured durations fall back to the defaults, as
      `EventDelivery.positiveOrDefault` does. Renewal is the same task, so a failed renewal retries with backoff.
- `src/main/java/org/fiware/iam/dataspace/DataSpaceSubscriptionHealthIndicator.java` (`@Singleton`, `SubscriberCondition`,
  implements `HealthIndicator`):
  - It computes the result from the subscriber's states and the `Clock` (Decision 8). It does not keep its own boolean map, so
    expiry is evaluated at query time.
  - Details hold the per-publisher state.
  - Verify and document the readiness exclusion (Decision 8).
- Tests: `DataSpaceEventSubscriberTest` uses a mocked client, a fake `TaskScheduler` that records `(delay, Runnable)` and runs
  tasks on demand, and `MutableClock`. Parameterize where the shape repeats.
  - **Acceptance:** the publisher is unavailable at startup. The client fails N times, the retries follow the backoff and are
    capped at `maxBackoff`, and the health is DOWN after every failure. It is UP right after the first success.
  - **Acceptance:** renewal is scheduled before `expiresAt` (fractions, a tiny lease → `minRenewalDelay`, a past `expiresAt`).
    Running the task re-registers. A renewal failing past `expiresAt` turns the health DOWN, and the next success turns it UP.
  - **Acceptance:** `409` → healthy, re-registration after `conflict-renewal-interval`.
  - Publishers without `subscriptions-url` or without types are not contacted and not part of the health check.
  - A stale-generation callback is ignored.
  - `DataSpaceSubscriptionHealthIndicatorTest`: UP with no publishers, DOWN while pending, the expiry boundary, and the details.

### Step 4: Runtime refresh: added, changed and removed publishers
- `DataSpaceEventSubscriber` also implements `ApplicationEventListener<RefreshEvent>`:
  - The listener can run before Micronaut has rebound `DataSpaceEventsProperties`. It therefore only schedules `reconcile()`
    with zero delay on the `TaskScheduler`.
  - `reconcile()` reads the properties inside `ConfigurationRefreshLock.exclusively(...)`. Both refresh paths
    (`ConfigFileRefresher`, `SerializedRefreshEndpoint`) publish under that lock, so the read waits for the rebind. The
    registrations themselves run outside the lock.
- `reconcile()` compares the subscribable publishers with the states:
  - **added:** create a `PENDING` state and register immediately (**acceptance**: subscribed without a restart).
  - **changed** `subscriptions-url`, `types`, `client-id` or `scopes`, or a changed global `sink`/`protocol-settings`/`client-id`/
    `scopes`: bump the generation, cancel the future and re-register immediately. The attempt counter is reset. The health
    stays as it was until the new outcome (the old lease is still valid). If the URL changed, the old subscription id is not
    reused.
  - **removed** (or the URL dropped): cancel, remove the state (it leaves the health check), and fire a best-effort
    `unsubscribe` when an id is known (Decision 7).
- A periodic backstop: `reconcile()` also runs every `reconcile-interval`, scheduled with `TaskScheduler.scheduleWithFixedDelay`
  at startup. It catches a `RefreshEvent` published outside the lock (e.g. tests, other refresh paths) and refresh keys that
  are rebound late. A reconcile with no differences does nothing.
- Tests:
  - Extend `DataSpaceEventSubscriberTest`, parameterized over add / change types / change URL / change sink / remove / remove
    URL. Each case checks registrations, cancellations, `unsubscribe` calls and the health details.
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
  - The `subscriber.*` keys with defaults, and the per-publisher `subscriptions-url` / `client-id` / `scopes`.
  - Which client id is used where (Decision 4).
  - Retry/backoff, renewal timing, 409 handling (including the CM-2 cap note).
  - Refresh behaviour (added, changed, removed), the health indicator `dataspace-subscriptions` and why it does not gate
    readiness.
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
