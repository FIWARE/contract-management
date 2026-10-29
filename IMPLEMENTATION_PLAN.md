# Implementation Plan: [CM] Publisher: subscriptions, webhook handshake and delivery

## Overview
This adds the sending half of data space eventing (CM-2) on top of CM-1 (`ticket-96/work`). It covers an OID4VP/bearer-authenticated
CloudEvents-Subscriptions-shaped `POST/DELETE /subscriptions` API, a webhook handshake that must succeed before a subscription is
activated, and best-effort, rate-limited delivery with exponential backoff via `Oid4VpAuthHandler`. It also adds an internal publish
endpoint on the cluster-internal management port for OBP-1 and operator scripts. Subscriptions are kept in memory on a single
replica (architecture D-4). The CM-5 reconciliation sweep is the backstop.

### Decisions (resolving the analysis questions)
1. **Internal publish endpoint:** a Micronaut management endpoint `@Endpoint(id = "events")` with a `@Write` operation, i.e.
   `POST :9090/events`. Port 9090 is already documented as cluster-internal and unauthenticated (`/refresh` lives there). So it
   never collides with the listener's `POST /events` on 8080 and is never exposed through the ingress.
2. **`clientId`/`scopes`** live in `protocolsettings` (HTTP protocol settings: `clientId`, `scopes`). Only `protocol: HTTP` is accepted.
3. **Internal body:** `type` (required), `subject`, `data`, and an optional `id`. CM fills `specversion=1.0`, `id` (a random UUID
   when absent), `source` (= `DataSpaceEventsProperties.ownId(...)`), `time`, `dataspaceid` and `datacontenttype=application/json`.
4. **Store:** in-memory and single-replica. A restart loses subscriptions, and subscribers recover by renewing (documented in the README).
5. **Subscriber authentication:** reuse CM-1's `BearerVerifier` and its `dataspace.events.listener.verifier.*` configuration (shared,
   no new keys). Its `@Requires` switches from `ListenerCondition` to a new `VerifierCondition` (listener **or** publisher enabled).
6. **Renewal:** a `POST` by the same subscriber for the same `sink` replaces that subscription in place (same id, new lease, new types).
   The handshake runs again. Leases are always the configured TTL. The response carries `expiresAt`.
7. **No host is derived from the subscriber id** (D-8). The handshake and deliveries go only to the `sink` URI given in the request.

## Steps

### Step 1: Publisher configuration, conditions and OpenAPI for subscriptions
- `src/main/java/org/fiware/iam/configuration/DataSpaceEventsProperties.java`: add the field `publisher` of a new nested class
  `PublisherSettings` (`@ConfigurationProperties("publisher")`). Do not reuse the name `Publisher`, which is already the
  `publishers` list entry. Named default constants, Javadoc on every field like the existing ones:
  - `enabled` (`false`, startup only)
  - `origin`: own DNS name sent as `WebHook-Request-Origin`; required when enabled
  - `leaseTtl` (`PT24H`)
  - `maxSubscriptionsPerSubscriber` (`10`)
  - `maxSubscriptions` (`1000`)
  - `allowInsecureSinks` (`false`; when false, only `https` sinks are accepted)
  - nested `Delivery` (`@ConfigurationProperties("delivery")`):
    - `maxAttempts` (`5`)
    - `initialBackoff` (`PT1S`)
    - `maxBackoff` (`PT1M`)
    - `queueSize` (`1000` pending events per subscription)

  Add `PublisherCondition` (publisher enabled) and `VerifierCondition` (listener or publisher enabled).
- `src/main/java/org/fiware/iam/dataspace/BearerVerifier.java`: `@Requires(condition = VerifierCondition.class)`.
- `api/dataspace-events.yaml`: widen `info.description` and add the tag `subscriptions`. This generates
  `org.fiware.iam.events.api.SubscriptionsApi`, with no pom change because the `events` execution already covers the file.
  - `POST /subscriptions` (`createSubscription`, bearerAuth). Request `SubscriptionRequest`:
    - `subscriberId` (required)
    - `sink` (required, uri)
    - `types` (required, minItems 1)
    - `protocol` (enum `HTTP`)
    - `protocolsettings` (`HttpProtocolSettings`: `clientId`, `scopes` array)

    Response `201` with `Subscription`: the request fields plus `id` and `expiresAt` (date-time). Further responses: `400` invalid
    request or failed handshake, `401`, `403` subscriberId mismatch, `409` subscription cap reached, `503` verifier unavailable
    (with `Retry-After`).
  - `DELETE /subscriptions/{subscriptionId}` (`deleteSubscription`, bearerAuth): `204`, `401`, `404` (unknown, or owned by
    someone else), `503`.
- Tests: extend the existing properties/condition tests (or add `DataSpaceEventsPropertiesTest`) with a parameterized check of the
  defaults, the binding of `dataspace.events.publisher.*`, and `VerifierCondition` across the 4 listener/publisher combinations.
  `EventListenerTest` and `BearerVerifierTest` must stay green.
- Acceptance: `mvn test` passes, `SubscriptionsApi` and its `*VO` models are generated, and listener behaviour is unchanged.

### Step 2: Subscription registry and webhook handshake
Add these under `src/main/java/org/fiware/iam/dataspace/` (`publisher` subpackage optional; keep it flat if simpler). All beans use
`@Requires(condition = PublisherCondition.class)`:
- `Subscription` (record): `id`, `subscriberId`, `sink` (`URI`), `types`, `clientId`, `scopes`, `expiresAt` (`Instant`),
  `allowedRate` (`@Nullable Integer`, requests per minute; null means unlimited). Plus `isExpired(Instant)` and `matches(String type)`.
- `SubscriptionRegistry` (`@Singleton`, `ConcurrentHashMap`, injected `java.time.Clock` bean, with a `Clock.systemUTC()` factory if
  none exists):
  - `register(...)` upserts by `(subscriberId, sink)`. It evicts expired entries first, then enforces `maxSubscriptionsPerSubscriber`
    and `maxSubscriptions`. A replacement does not count twice. Caps are read from the properties on every call, so they are
    refreshable. On a cap, throw a new `SubscriptionLimitException`.
  - `remove(id, subscriberId)`: returns false if the id is unknown or not owned.
  - `remove(id)`.
  - `active(type)`: unexpired subscriptions matching the type.
  - `get(id)`.
  - `@Scheduled(fixedDelay = ...)` eviction of expired leases (named constant interval).
- `WebhookHandshake` (`@Singleton`, `HttpClient`): `Mono<Optional<Integer>> validate(URI sink)` sends `OPTIONS <sink>` with
  `WebHook-Request-Origin: <publisher.origin>`. It succeeds only on a 2xx whose `WebHook-Allowed-Origin` equals the origin
  (case-insensitive) or is `*`. It then parses `WebHook-Allowed-Rate`: a positive integer, or `*`/absent for unlimited.
  Timeouts, errors, non-2xx or a missing/non-matching origin fail with a new `WebhookHandshakeException` that keeps the cause and
  the sink.
- Header names go in a small shared constants holder (e.g. `WebhookHeaders`). `EventListener.WEBHOOK_ALLOWED_ORIGIN` moves there
  too, so receiver and publisher share them.
- New exceptions under `exception/`, extending `ContractManagementException` with new `FailureReason` codes, following the existing
  ones.
- Tests (Mockito, `@ParameterizedTest` + `@MethodSource`):
  - `SubscriptionRegistryTest`: upsert, per-subscriber and total caps, expired entries not counted/returned/delivered (fixed and
    advanced `Clock`), and delete by a non-owner.
  - `WebhookHandshakeTest`: matching, case-differing, `*`, missing, non-matching, non-2xx, connection error, and rate parsing
    (`120`, `*`, absent, garbage).

### Step 3: Authenticated subscription controller
- `SubscriptionController implements SubscriptionsApi`:
  - `@Controller("${general.basepath:/}")`, `@Requires(condition = PublisherCondition.class)`, `@Slf4j`.
  - Takes the bearer token from `ServerRequestContext` as `EventListener` does, and runs `BearerVerifier.participantIdOf` on
    `Schedulers.boundedElastic()`.
- `createSubscription`, checked in this order:
  1. A missing or invalid token gives `401`.
  2. `subscriberId` must equal the participant id, else `403`.
  3. `sink` must be absolute with scheme `https`, or `http` when `allowInsecureSinks` is set. `types` must be non-empty. The
     protocol must be `HTTP`. Otherwise `400`.
  4. `publisher.origin` must be configured, else `503` with an error log.
  5. The handshake runs. A failure gives `400` with a short reason, and nothing is stored.
  6. Register; a cap gives `409`.
  7. Return `201` with the `Subscription`.

  `VerifierUnavailableException` gives `503` with `Retry-After`, mirroring `EventListener`. Log once at the decision point
  (INFO on activation and refusal: subscriber, sink, reason).
- `deleteSubscription`: a missing or invalid token gives `401`. Not found or not owned gives `404`. Otherwise `204`.
- Tests: `SubscriptionControllerTest` (`@MicronautTest` + `TestPropertyProvider` like `EventListenerTest`, with mocked
  `BearerVerifier`/`WebhookHandshake` via `@MockBean`, or plain Mockito). Parameterized over: subscriberId mismatch → 403
  (**acceptance**), handshake failing or non-matching origin → 400 and nothing stored (**acceptance**), insecure sink, empty types,
  wrong protocol, cap → 409, verifier down → 503, delete by owner/non-owner/unknown.

### Step 4: Delivery dispatcher with backoff, rate limit and metrics
- `EventDelivery` (`@Singleton`, `PublisherCondition`): `void publish(DataSpaceEventVO event)` fans out to
  `registry.active(event.getType())` and returns immediately (best-effort, stateless, D-4).
- Each subscription has its own `SubscriptionChannel`: a bounded `Sinks.many().unicast()` queue of size `delivery.queueSize`,
  drained with `concatMap`. One subscriber's slow or failing deliveries therefore never block the others. The channel paces sends to
  `allowedRate` (minimum interval = 60s / rate between send starts, including retries), so the granted rate is never exceeded.
  Channels are created lazily, and disposed when the subscription is removed, expires or is replaced.
- One delivery attempt:
  - `Mono.defer` builds a new `HttpRequest.POST(sink, event)` with content type `application/cloudevents+json` and the
    `WebHook-Request-Origin` header.
  - It sets `CLIENT_ID_ATTRIBUTE` and `SCOPE_ATTRIBUTE` (a `Set`) from the subscription and sends via
    `Optional<AuthHandler>.executeWithAuth`, or a plain `httpClient.exchange` when no handler exists (exactly as in
    `ContractManagementAdapter`). A 401 thus triggers one OID4VP token fetch and a retry inside the handler.
- Outcome mapping:
  - 2xx: delivered.
  - `410 Gone`: remove the subscription (CloudEvents webhook unsubscribe).
  - 5xx, 408, 429, `BadGatewayException` or a connection error: retry with
    `Retry.backoff(maxAttempts - 1, initialBackoff).maxBackoff(maxBackoff)`.
  - Any other 4xx, including a 401 that survives the token retry: no retry.
  - Before each attempt, an expired or removed subscription stops delivery (**acceptance**: an expired lease stops delivery).
  - Exhausted attempts or a non-retryable status: one ERROR log (`DownstreamError.reason(e)`, subscription id, subscriber, event
    id/type) and the drop is counted. A full queue is counted as `overflow`.
- Metric: counter `dataspace.events.delivered` with tags `type` (only types present in some subscription, else `other`) and
  `outcome` (`delivered|failed|gone|expired|overflow`). Use `Optional<MeterRegistry>` with a `CompositeMeterRegistry` fallback.
- Tests (`EventDeliveryTest`, Mockito `HttpClient`/`AuthHandler`, short backoff via properties, Awaitility):
  - A 401 then 2xx through a real `Oid4VpAuthHandler` with a mocked `OID4VPClient` gives exactly one token fetch and a retry
    (**acceptance**).
  - A 503 sequence retries up to `maxAttempts`, then drops and counts.
  - 400 gets no retry; 410 removes the subscription; an expired lease is not delivered.
  - A permanently failing (hanging/500) subscriber does not delay delivery to a healthy one (**acceptance**).
  - With `allowedRate`, N events are not sent faster than the granted interval (**acceptance**).
  - Parameterize the status → outcome mapping.

### Step 5: Internal publish endpoint
- `PublishEndpoint`: `@Endpoint(id = "events", defaultSensitive = false)` with `@Requires(condition = PublisherCondition.class)`,
  served on the management port as `POST :9090/events`.
  - Its `@Write` operation takes `type` (required), `@Nullable subject`, `@Nullable data` (Object) and `@Nullable id`. It builds the
    `DataSpaceEventVO` per Decision 3, calls `EventDelivery.publish`, and returns `202`-style accepted info (event id and number of
    matching subscriptions).
  - A blank `type` gives a `400`.
- If Micronaut endpoint body binding cannot carry an untyped `data` value, fall back to a `@Controller` under `/internal/events`
  plus a filter that rejects requests whose local port is not the management port. Note the fallback in the PR.
- `src/main/resources/application.yaml`: add `endpoints.events.enabled: true` / `sensitive: false` next to `refresh`, and a
  commented `dataspace.events.publisher` sample next to the existing commented `dataspace.events` block.
- Tests: `PublishEndpointTest` (`@MicronautTest`, publisher enabled, mocked `EventDelivery`) checks the envelope fields filled by CM
  (source = own id, dataspaceid, specversion, generated id, time), an explicit id kept, and a missing type → 400.

### Step 6: Documentation and verification
- `README.md`: add a `#### Publishing events` subsection under `### Data space events` covering:
  - Enabling it, and the `publisher.*` keys with their defaults.
  - The subscription request/response with an example, and the order of the checks (401/403/400/409/503).
  - The handshake: `WebHook-Request-Origin` = `publisher.origin`, the allowed origin must match, and `WebHook-Allowed-Rate` is
    honoured. No host is derived from the subscriber id.
  - Renewal by re-POSTing; lease expiry.
  - Delivery semantics: best-effort, backoff and attempts, 401 → OID4VP, 410 unsubscribes, metric `dataspace.events.delivered`.
  - In-memory, single replica, lost on restart.
  - The internal `POST :9090/events` with a curl example; keep 9090 cluster-internal.
  - The verifier config is shared with the listener.
- Update the refresh paragraph: `publisher.enabled` is startup-only, all other `publisher.*` values are refreshable.
- Update `AGENTS.md` (project structure: the new classes in `dataspace/`, the `subscriptions` tag in `dataspace-events.yaml`).
- Run `mvn test` and `mvn -DskipITs verify` (or `mvn verify` if the k3s environment is available). Fix any regressions in
  `EventListenerTest`, `BearerVerifierTest` and `DataSpaceEventsRefreshTest`.
- Acceptance: all acceptance criteria of the ticket are covered by the tests named in Steps 3–4, and the docs match the
  implemented behaviour.
