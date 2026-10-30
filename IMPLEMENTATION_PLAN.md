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
   never collides with the listener's `POST /events` on 8080 and is never exposed through the ingress. That holds only while
   `endpoints.all.port` is a separate port, so a startup guard fails a publisher-enabled CM when the management port is unset or
   equal to `micronaut.server.port` (Step 5).
2. **`clientId`/`scopes`** live in `protocolsettings` (HTTP protocol settings: `clientId`, `scopes`). Only `protocol: HTTP` is accepted.
3. **Internal body:** `type` (required), `subject`, `data`, and an optional `id`. CM fills `specversion=1.0`, `id` (a random UUID
   when absent), `source` (= `DataSpaceEventsProperties.ownId(...)`), `time`, `dataspaceid` and `datacontenttype=application/json`.
4. **Store:** in-memory and single-replica. A restart loses subscriptions, and subscribers recover by renewing (documented in the README).
5. **Subscriber authentication:** reuse CM-1's `BearerVerifier` and its `dataspace.events.listener.verifier.*` configuration (shared,
   no new keys). Its `@Requires` switches from `ListenerCondition` to a new `VerifierCondition` (listener **or** publisher enabled).
6. **Renewal:** a `POST` by the same subscriber for the same `sink` replaces that subscription in place (same id, new lease, new types).
   The handshake runs again. Leases are always the configured TTL. The response carries `expiresAt`. If the repeated handshake
   fails, the request gets `400` and the existing subscription is kept unchanged until its old lease expires (a transient sink
   failure must not drop a working subscription). A renewal never resets the subscription's delivery channel (Step 4).
7. **No host is derived from the subscriber id** (D-8). The handshake and deliveries go only to the `sink` URI given in the request.
8. **Sink trust (accepted in review):** the subscriber chooses `sink`, `clientId` and `scopes`. On a 401 from the sink, CM runs
   OID4VP against the sink host and presents the credentials that host's DCQL requests. The x509_san_dns resolver binds the token
   audience to that host, which limits replay but not disclosure. Sinks are not restricted to `publishers[].origin`. This trust
   assumption is documented in the README (Step 6).
9. **No redirects to participant-chosen hosts:** handshake and delivery use a dedicated HTTP client
   (`@Client(configuration = PublisherHttpClientConfiguration.class)`), which turns redirect following off in code. A 3xx counts as a failure, so a sink cannot redirect CM to an internal address and bypass the
   handshake. Responses to the subscriber never carry the downstream status or connection error.

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
- `WebhookHandshake` (`@Singleton`, an `HttpClient` injected via `@Client(configuration = PublisherHttpClientConfiguration.class)`,
  see Decision 9). `PublisherHttpClientConfiguration` copies the global client settings, then sets `followRedirects=false` and
  connect/read timeouts from `publisher.sink-connect-timeout`/`sink-read-timeout` (`PT5S`/`PT10S`, startup only; a
  whole handshake is bounded by the refreshable `publisher.handshake-timeout`, `PT15S`). A service entry in `application.yaml` would be lost whenever a deployment or the test classpath
  replaces that file (the test `application.yaml` already shadows the main one):
  `Mono<Optional<Integer>> validate(URI sink)` sends `OPTIONS <sink>` with
  `WebHook-Request-Origin: <publisher.origin>`. It succeeds only on a 2xx whose `WebHook-Allowed-Origin` equals the origin
  (case-insensitive) or is `*`. It then parses `WebHook-Allowed-Rate`: a positive integer, or `*`/absent for unlimited.
  Timeouts, errors, 3xx (not followed), other non-2xx or a missing/non-matching origin fail with a new `WebhookHandshakeException`
  that keeps the cause and the sink.
- Header names go in a small shared constants holder (e.g. `WebhookHeaders`). `EventListener.WEBHOOK_ALLOWED_ORIGIN` moves there
  too, so receiver and publisher share them.
- New exceptions under `exception/`, extending `ContractManagementException` with new `FailureReason` codes, following the existing
  ones.
- Tests (Mockito, `@ParameterizedTest` + `@MethodSource`):
  - `SubscriptionRegistryTest`: upsert, per-subscriber and total caps, expired entries not counted/returned/delivered (fixed and
    advanced `Clock`), and delete by a non-owner.
  - `SubscriptionRegistryTest` also covers renewal: same id, new lease/types/rate, and no change to the stored subscription when
    the caller does not register (failed handshake, Decision 6).
  - `WebhookHandshakeTest`: matching, case-differing, `*`, missing, non-matching, non-2xx, a 3xx with `Location` pointing elsewhere
    (fails, the target is never called), connection error, and rate parsing (`120`, `*`, absent, garbage).

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
  5. The handshake runs. A failure gives `400` with the generic reason `handshake failed` (no downstream status, host or
     connection error, so the endpoint cannot be used to probe internal hosts/ports). Nothing is stored; on a renewal the existing
     subscription stays as it was (Decision 6). The details go only into the INFO log.
  6. Register; a cap gives `409`.
  7. Return `201` with the `Subscription`.

  `VerifierUnavailableException` gives `503` with `Retry-After`, mirroring `EventListener`. Log once at the decision point
  (INFO on activation and refusal: subscriber, sink, reason incl. the handshake cause).
- `deleteSubscription`: a missing or invalid token gives `401`. Not found or not owned gives `404`. Otherwise `204`.
- Tests: `SubscriptionControllerTest` (`@MicronautTest` + `TestPropertyProvider` like `EventListenerTest`, with mocked
  `BearerVerifier`/`WebhookHandshake` via `@MockBean`, or plain Mockito). Parameterized over: subscriberId mismatch → 403
  (**acceptance**), handshake failing or non-matching origin → 400 with the generic body and nothing stored (**acceptance**),
  failed renewal handshake → 400 and the old subscription (lease, types) kept, insecure sink, empty types, wrong protocol,
  cap → 409, verifier down → 503, delete by owner/non-owner/unknown.

### Step 4: Delivery dispatcher with backoff, rate limit and metrics
- `EventDelivery` (`@Singleton`, `PublisherCondition`): `void publish(DataSpaceEventVO event)` fans out to
  `registry.active(event.getType())` and returns immediately (best-effort, stateless, D-4).
- Each subscription has its own `SubscriptionChannel`: a bounded `Sinks.many().unicast()` queue of size `delivery.queueSize`,
  drained with `concatMap`. One subscriber's slow or failing deliveries therefore never block the others.
- Rate limit: the channel owns a pacer that enforces the minimum interval 60s / `allowedRate` between **HTTP requests to the sink**,
  not between events. The pacer is applied inside the `executor` function passed to `executeWithAuth`, so the unauthenticated
  first POST, the POST after the OID4VP token fetch and every backoff retry each wait for a slot. `Oid4VpAuthHandler` caches no
  token, so an OID4VP-protected sink sees 2 requests per event; pacing per request keeps the granted rate regardless. (A token
  cache per sink origin/clientId/scopes would halve the requests but changes `Oid4VpAuthHandler`; out of scope here.)
- Channel lifecycle: keyed by subscription id, created lazily, and disposed only when the subscription is removed (DELETE, 410) or
  expires. A renewal keeps the same id and the same channel: queued events stay queued and the pacer keeps its state. The channel
  reads the current `Subscription` (lease, types, rate, clientId/scopes) from the registry before each attempt, so a renewal takes
  effect in place.
- One delivery attempt:
  - `Mono.defer` builds a new `HttpRequest.POST(sink, event)` with content type `application/cloudevents+json` and the
    `WebHook-Request-Origin` header.
  - It sets `CLIENT_ID_ATTRIBUTE` and `SCOPE_ATTRIBUTE` (a `Set`) from the subscription and sends via
    `Optional<AuthHandler>.executeWithAuth`, or a plain `httpClient.exchange` when no handler exists (exactly as in
    `ContractManagementAdapter`). A 401 thus triggers one OID4VP token fetch and a retry inside the handler. The executor is the
    paced publisher client (`PublisherHttpClientConfiguration`, no redirects, Decision 9).
- Errors arrive in several shapes, and all of them are classified by one function into a status (or none):
  - a returned `HttpResponse` (first call inside `executeWithAuth`, which turns the first `HttpClientResponseException` into a
    response);
  - a thrown `HttpClientResponseException` (the second call after the token fetch is not wrapped, and the plain
    `httpClient.exchange` branch throws on every error status): use `getStatus()`;
  - `BadGatewayException` (transport error on the first call, or a failed token fetch): no status, retryable;
  - any other exception (connection refused, timeout): no status, retryable.
- Outcome mapping:
  - 2xx: delivered.
  - 3xx: not followed; treated as a non-retryable failure.
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
  - With `allowedRate`, an OID4VP-protected sink (401 → token → 2xx per event) is not called more often than the granted rate:
    the test counts **requests at the sink**, not events (**acceptance**).
  - A renewal while events are queued keeps the queue and the pacing (no event lost, no burst).
  - Parameterize the status → outcome mapping over both shapes: returned responses and thrown `HttpClientResponseException`s,
    including the paths 401 → token → 503 (retried), 401 → token → 410 (removed), 401 → token → 401 (failed, no retry), a token
    fetch failure (`BadGatewayException`, retried), and the no-`AuthHandler` branch throwing on 5xx/410.

### Step 5: Internal publish endpoint
- `PublishEndpoint`: `@Endpoint(id = "events", defaultSensitive = false)` with `@Requires(condition = PublisherCondition.class)`,
  served on the management port as `POST :9090/events`.
  - Its `@Write` operation takes `type` (required), `@Nullable subject`, `@Nullable data` (Object) and `@Nullable id`. It builds the
    `DataSpaceEventVO` per Decision 3, calls `EventDelivery.publish`, and returns `202`-style accepted info (event id and number of
    matching subscriptions).
  - A blank `type` gives a `400`.
- If Micronaut endpoint body binding cannot carry an untyped `data` value, fall back to a `@Controller` under `/internal/events`
  plus a filter that rejects requests whose local port is not the management port. Note the fallback in the PR.
- `ManagementPortGuard` (`@Context`, `@Requires(condition = PublisherCondition.class)`): at startup, reads `endpoints.all.port`
  and `micronaut.server.port` (default `8080`) and throws a `ConfigurationException` when the management port is unset or equals
  the server port. The unauthenticated publish endpoint would otherwise move to the public port, collide with the listener's
  `POST /events` and be reachable through the ingress. The exception message names both ports.
- `src/main/resources/application.yaml`: add `endpoints.events.enabled: true` / `sensitive: false` next to `refresh`, a commented
  `dataspace.events.publisher` sample next to the existing commented `dataspace.events` block. The publisher client settings
  are in code (`PublisherHttpClientConfiguration`, Step 2).
- Tests: `PublishEndpointTest` (`@MicronautTest`, publisher enabled, mocked `EventDelivery`) checks the envelope fields filled by CM
  (source = own id, dataspaceid, specversion, generated id, time), an explicit id kept, and a missing type → 400.
- `ManagementPortGuardTest`: parameterized `ApplicationContext.run(...)` with the publisher enabled: management port unset → startup
  fails; equal to the server port → fails; distinct → starts. With the publisher disabled, an unset management port still starts.

### Step 6: Documentation and verification
- `README.md`: add a `#### Publishing events` subsection under `### Data space events` covering:
  - Enabling it, and the `publisher.*` keys with their defaults.
  - The subscription request/response with an example, and the order of the checks (401/403/400/409/503).
  - The sink timeouts `sink-connect-timeout`/`sink-read-timeout` (startup only) and `handshake-timeout`.
  - The handshake: `WebHook-Request-Origin` = `publisher.origin`, the allowed origin must match, and `WebHook-Allowed-Rate` is
    honoured. No host is derived from the subscriber id.
  - Renewal by re-POSTing; lease expiry.
  - Delivery semantics: best-effort, backoff and attempts, 401 → OID4VP, 410 unsubscribes, metric `dataspace.events.delivered`.
  - In-memory, single replica, lost on restart.
  - The internal `POST :9090/events` with a curl example; keep 9090 cluster-internal. The publisher refuses to start when
    `endpoints.all.port` is unset or equal to the server port.
  - Security notes: the sink trust assumption from Decision 8 (CM presents its OID4VP credentials to the subscriber-chosen sink
    host; audience-bound but disclosed), redirects are never followed, and a failed handshake is reported only as
    `handshake failed`.
  - Rate limiting counts HTTP requests (an OID4VP-protected sink sees two per event without a cached token).
  - The verifier config is shared with the listener.
- Update the refresh paragraph: `publisher.enabled` is startup-only, all other `publisher.*` values are refreshable.
- Update `AGENTS.md` (project structure: the new classes in `dataspace/`, the `subscriptions` tag in `dataspace-events.yaml`).
- Run `mvn test` and `mvn -DskipITs verify` (or `mvn verify` if the k3s environment is available). Fix any regressions in
  `EventListenerTest`, `BearerVerifierTest` and `DataSpaceEventsRefreshTest`.
- Acceptance: all acceptance criteria of the ticket are covered by the tests named in Steps 3–4, and the docs match the
  implemented behaviour.
