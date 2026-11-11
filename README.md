# Contract Management

The Contract Management Service uses events defined by
the [TM Forum API](https://raw.githubusercontent.com/FIWARE/trusted-issuers-list/main/api/trusted-issuers-list.yaml) to
reflect the state of a contract in a
data space. Depending on the provided data, permission to grant
specified [VerifiableCredentials](https://www.w3.org/TR/vc-data-model/) is set at a
[Trusted Issuers List API](./api/trusted-issuers-list.yaml) service implementation.

[![FIWARE Security](https://nexus.lab.fiware.org/repository/raw/public/badges/chapters/security.svg)](https://www.fiware.org/developers/catalogue/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Container Repository on Quay](https://img.shields.io/badge/quay.io-fiware%2Fcontract-management-grey?logo=red%20hat&labelColor=EE0000)](https://quay.io/repository/fiware/contract-management)
[![Coverage Status](https://coveralls.io/repos/github/FIWARE/contract-management/badge.svg?branch=main)](https://coveralls.io/github/FIWARE/contract-management?branch=main)
[![Test](https://github.com/FIWARE/contract-management/actions/workflows/test.yml/badge.svg)](https://github.com/FIWARE/contract-management/actions/workflows/test.yml)
[![Release](https://github.com/FIWARE/contract-management/actions/workflows/release.yml/badge.svg)](https://github.com/FIWARE/contract-management/actions/workflows/release.yml)

## Installation

### Container

The Contract Management Service is provided as a container
at [quay.io](https://quay.io/repository/fiware/contract-management).

The container can be started with

```shell
docker run --network host quay.io/fiware/contract-management:0.0.1
```

### Configuration

Configurations can be provided with the standard mechanisms of the [Micronaut-Framework](https://micronaut.io/),
e.g. [environment variables or appliction.yaml file](https://docs.micronaut.io/3.1.3/guide/index.html#configurationProperties).
The following table concentrates on the most important configuration parameters:

| Property                               | Env-Var                                | Description                                                        | Default                          |
|----------------------------------------|----------------------------------------|--------------------------------------------------------------------|----------------------------------|
| `micronaut.server.port`                | `MICRONAUT_SERVER_PORT`                | Server port to be used for the listener endpoint.                  | 8080                             |
| `micronaut.metrics.enabled`            | `MICRONAUT_METRICS_ENABLED`            | Enable the metrics gathering                                       | true                             |
| `micronaut.http.services.til.url`      | `MICRONAUT_HTTP_SERVICES_TIL_URL`      | URL of the Trusted Issuers List Endpoint                           | http://trusted-issuers-list:8080 |
| `micronaut.http.services.til.path`     | `MICRONAUT_HTTP_SERVICES_TIL_PATH`     | Subpath of the Trusted Issuers List Endpoint                       | ""                               |
| `micronaut.http.services.product.url`  | `MICRONAUT_HTTP_SERVICES_PRODUCT_URL`  | URL of the TM Forum Product Order Endpoint                         | http://tmforum:8080              |
| `micronaut.http.services.product.path` | `MICRONAUT_HTTP_SERVICES_PRODUCT_PATH` | Subpath of the TM Forum Product Order Endpoint                     | "productordering"                |
| `micronaut.http.services.party.url`    | `MICRONAUT_HTTP_SERVICES_PARTY_URL`    | URL of the TM Forum Party Endpoint                                 | http://tmforum:8080              |
| `micronaut.http.services.party.path`   | `MICRONAUT_HTTP_SERVICES_PARTY_PATH`   | Subpath of the TM Forum Party Endpoint                             | "party"                          |
| `general.name`                         | `GENERAL_NAME`                         | Name of the service, used for the callback/listener subscription   | contract-management              |
| `general.basepath`                     | `GENERAL_BASEPATH`                     | Basepath used for the provided listener endpoint                   | ""                               |
| `dataspace.events.dataspace-id` | `DATASPACE_EVENTS_DATASPACE_ID` | Id of the data space; events carrying a different `dataspaceid` are rejected | - |
| `dataspace.events.own-participant-id` | `DATASPACE_EVENTS_OWN_PARTICIPANT_ID` | Participant id of this instance, events about it are not dispatched | `general.did` |
| `dataspace.events.listener.enabled` | `DATASPACE_EVENTS_LISTENER_ENABLED` | Enable the data space event endpoint (startup only, needs a restart) | false |
| `dataspace.events.listener.identity.header` | `DATASPACE_EVENTS_LISTENER_IDENTITY_HEADER` | Request header the API gateway sets with the claims of the token it verified | X-Userinfo |
| `dataspace.events.listener.identity.participant-claim` | `DATASPACE_EVENTS_LISTENER_IDENTITY_PARTICIPANT_CLAIM` | Top-level claim carrying the caller's participant id | participantId |
| `dataspace.events.listener.dedup.max-entries` | `DATASPACE_EVENTS_LISTENER_DEDUP_MAX_ENTRIES` | Number of remembered `(source, id)` pairs | 10000 |
| `dataspace.events.listener.dedup.ttl` | `DATASPACE_EVENTS_LISTENER_DEDUP_TTL` | How long a `(source, id)` pair is remembered | PT1H |
| `dataspace.events.listener.watch.enabled` | `DATASPACE_EVENTS_LISTENER_WATCH_ENABLED` | Refresh when the config directory changes (startup only, needs a restart) | false |
| `dataspace.events.listener.watch.path` | `DATASPACE_EVENTS_LISTENER_WATCH_PATH` | Watched configuration directory (e.g. the ConfigMap mount `/config`, not the file) | - |
| `dataspace.events.publishers` | - | List of `id` (participant id), `origin` (webhook origin) and `types` (allowed event types); for the subscriber also `subscriptions-url` and optional `client-id`/`scopes`, see [Subscribing to publishers](#subscribing-to-publishers) | [] |
| `dataspace.events.publisher.enabled` | `DATASPACE_EVENTS_PUBLISHER_ENABLED` | Enable subscriptions and event delivery (startup only, needs a restart) | false |
| `dataspace.events.publisher.origin` | `DATASPACE_EVENTS_PUBLISHER_ORIGIN` | Own DNS name, sent as `WebHook-Request-Origin`; required with the publisher | - |
| `dataspace.events.publisher.lease-ttl` | `DATASPACE_EVENTS_PUBLISHER_LEASE_TTL` | Lease of a subscription; it expires unless renewed within this time | PT24H |
| `dataspace.events.publisher.max-subscriptions-per-subscriber` | `DATASPACE_EVENTS_PUBLISHER_MAX_SUBSCRIPTIONS_PER_SUBSCRIBER` | Subscriptions a single subscriber may hold | 10 |
| `dataspace.events.publisher.max-subscriptions` | `DATASPACE_EVENTS_PUBLISHER_MAX_SUBSCRIPTIONS` | Subscriptions in total | 1000 |
| `dataspace.events.publisher.allow-insecure-sinks` | `DATASPACE_EVENTS_PUBLISHER_ALLOW_INSECURE_SINKS` | Accept `http` sinks as well; otherwise only `https` | false |
| `dataspace.events.publisher.sink-connect-timeout` | `DATASPACE_EVENTS_PUBLISHER_SINK_CONNECT_TIMEOUT` | Connect timeout of handshake and delivery requests (startup only) | PT5S |
| `dataspace.events.publisher.sink-read-timeout` | `DATASPACE_EVENTS_PUBLISHER_SINK_READ_TIMEOUT` | Read timeout of handshake and delivery requests (startup only) | PT10S |
| `dataspace.events.publisher.handshake-timeout` | `DATASPACE_EVENTS_PUBLISHER_HANDSHAKE_TIMEOUT` | Upper bound of a whole handshake | PT15S |
| `dataspace.events.publisher.delivery.max-attempts` | `DATASPACE_EVENTS_PUBLISHER_DELIVERY_MAX_ATTEMPTS` | Attempts per event, including the first one | 5 |
| `dataspace.events.publisher.delivery.initial-backoff` | `DATASPACE_EVENTS_PUBLISHER_DELIVERY_INITIAL_BACKOFF` | Wait before the first retry, doubled on each further one | PT1S |
| `dataspace.events.publisher.delivery.max-backoff` | `DATASPACE_EVENTS_PUBLISHER_DELIVERY_MAX_BACKOFF` | Upper bound of the wait between retries | PT1M |
| `dataspace.events.publisher.delivery.queue-size` | `DATASPACE_EVENTS_PUBLISHER_DELIVERY_QUEUE_SIZE` | Pending events per subscription; further events are dropped while it is full | 1000 |
| `dataspace.events.publisher.delivery.attempt-timeout` | `DATASPACE_EVENTS_PUBLISHER_DELIVERY_ATTEMPT_TIMEOUT` | Upper bound of one attempt incl. an OID4VP token fetch, not counting the wait for the rate limit | PT30S |
| `dataspace.events.subscriber.enabled` | `DATASPACE_EVENTS_SUBSCRIBER_ENABLED` | Register the own listener at every publisher with a `subscriptions-url` (startup only, needs a restart and the listener) | false |
| `dataspace.events.subscriber.sink` | `DATASPACE_EVENTS_SUBSCRIBER_SINK` | Public absolute http(s) URL of the own `POST /events`; required with the subscriber | - |
| `dataspace.events.subscriber.client-id` | `DATASPACE_EVENTS_SUBSCRIBER_CLIENT_ID` | OID4VP client id towards the publishers' subscriptions API, unless a publisher overrides it | - |
| `dataspace.events.subscriber.scopes` | - | OID4VP scopes towards the publishers' subscriptions API, unless a publisher overrides them | [] |
| `dataspace.events.subscriber.protocol-settings.client-id` | `DATASPACE_EVENTS_SUBSCRIBER_PROTOCOL_SETTINGS_CLIENT_ID` | Client at the own verifier the publishers use when the sink answers `401`; required with the subscriber | - |
| `dataspace.events.subscriber.protocol-settings.scopes` | - | Scopes at the own verifier, sent along with `protocol-settings.client-id` | [] |
| `dataspace.events.subscriber.initial-delay` | `DATASPACE_EVENTS_SUBSCRIBER_INITIAL_DELAY` | Wait after startup before the first registrations | PT5S |
| `dataspace.events.subscriber.retry.initial-backoff` | `DATASPACE_EVENTS_SUBSCRIBER_RETRY_INITIAL_BACKOFF` | Wait before retrying a failed registration, doubled on each further failure | PT5S |
| `dataspace.events.subscriber.retry.max-backoff` | `DATASPACE_EVENTS_SUBSCRIBER_RETRY_MAX_BACKOFF` | Upper bound of the wait between retries; retried without an attempt limit | PT5M |
| `dataspace.events.subscriber.renewal-fraction` | `DATASPACE_EVENTS_SUBSCRIBER_RENEWAL_FRACTION` | Renew after this fraction of the remaining lease; values outside `(0, 1)` use the default | 0.5 |
| `dataspace.events.subscriber.min-renewal-delay` | `DATASPACE_EVENTS_SUBSCRIBER_MIN_RENEWAL_DELAY` | Lower bound of the wait before a renewal | PT10S |
| `dataspace.events.subscriber.conflict-renewal-interval` | `DATASPACE_EVENTS_SUBSCRIBER_CONFLICT_RENEWAL_INTERVAL` | Wait before re-registering after a `409` (or a `201` without `expiresAt`) | PT1H |
| `dataspace.events.subscriber.reconcile-interval` | `DATASPACE_EVENTS_SUBSCRIBER_RECONCILE_INTERVAL` | Interval of the periodic comparison of the configured publishers with the registrations | PT1M |

All `dataspace.events.*` values except the four `enabled` flags and the two `sink-*-timeout`s are reloaded on a refresh without
a restart. A key removed from
the configuration keeps its previous value until restart; set it to an empty value instead.

### Logging

| Env-Var                        | Description                                                                          | Default |
|--------------------------------|--------------------------------------------------------------------------------------|---------|
| `LOG_FORMAT`                   | `TEXT` for human readable lines, `JSON` for one JSON object per line (case-sensitive; an unknown value makes logback print why there is no output) | `TEXT`  |
| `LOG_LEVEL`                    | Root log level                                                                       | `INFO`  |
| `LOGGER_LEVELS_ORG_FIWARE_IAM` | Level of the contract-management itself, e.g. `DEBUG` to trace how an order is resolved | `INFO`  |
| `HTTP_CLIENT_LOG_EXCEPTION`    | Add the stack trace to failed outgoing calls                                         | `false` |
| `LOG_LEVEL_MICRONAUT_HTTP_CLIENT` | Level of micronaut's own http client logging (its connection errors duplicate the one-line WARN of the failed call) | `OFF` |

Levels are used as follows:

* `ERROR` - the service itself is broken (e.g. an unexpected exception, a failed startup registration)
* `WARN` - something was not applied, always with the reason: a downstream service rejected a call (with its status and body), a configuration is invalid, an order handler failed
* `INFO` - business results: a notification was received, and one line per handled order
* `DEBUG` - how the result was reached: every outgoing call, the resolved specifications, policies and credentials, each policy created at the PAP and each issuer allowed at the TIL

Every line concerning an order starts with `Order <id>:`. A failing order handler is logged once, with its name and the reason, and each handled order ends with one line, e.g.

```
WARN  ProductOrderEventHandler - Order urn:ngsi-ld:product-order:1: completion failed in handler PapProductOrderHandler: [pap_rejected_policy] The PAP rejected policy p-1-urn:ngsi-ld:product-order:1 for assignee did:web:consumer.org. - downstream answered with status=400 body={"detail":"..."}
org.fiware.iam.exception.PapException: [pap_rejected_policy] The PAP rejected policy p-1-urn:ngsi-ld:product-order:1 for assignee did:web:consumer.org.
	at ...
WARN  ProductOrderEventHandler - Order urn:ngsi-ld:product-order:1: completion failed for customer urn:ngsi-ld:organization:2 in the handlers [PapProductOrderHandler].
```

Failures carry a machine-readable code in brackets (`[organization_did_missing]`, `[specification_not_resolvable]`, `[til_rejected_issuer]`, ...), see `org.fiware.iam.exception.FailureReason`.

### Data space events

With `dataspace.events.listener.enabled`, CM receives [CloudEvents](https://cloudevents.io/) (structured mode,
`application/cloudevents+json`) from other participants of the data space on `POST /events` (below `general.basepath`).
`OPTIONS /events` answers the CloudEvents webhook validation and grants a `WebHook-Request-Origin` only if it is the `origin` of a
configured publisher. Event types are dispatched to `DataSpaceEventHandler` beans; this release ships none.

A `POST` is checked in this order:

1. The request must carry the identity header the gateway sets (`identity.header`, by default `X-Userinfo`), else `401`.
2. Its claims must contain the participant id claim (`identity.participant-claim`), else `401`.
3. The event `source` must equal that participant id, else `403`.
4. If both the event and `dataspace-id` name a data space, they must match, else `403`.
5. A type no handler supports is answered with `202` and ignored.
6. The `(source, type)` pair must be configured in `dataspace.events.publishers`, else `403`.
7. A `(source, id)` seen before is answered with `202` and not dispatched again; while the first delivery is still being processed,
   the retry gets `503` with `Retry-After`.
8. An event whose `subject` is this participant (`own-participant-id`) is answered with `202` and not dispatched.
9. Otherwise the event is dispatched and answered with `202`.

The counter `dataspace.events.received` (tags `type`, `outcome`) counts every request; `type` is `other` for types without a handler.

#### Who the caller is

CM does not verify the bearer token of an incoming request. Like every other ingress path of CM, `POST /events` and the
subscriptions API expect an API gateway in front that verified the token and passes its claims on in a request header. CM only
reads the participant id out of that header.

The header is `identity.header`, by default `X-Userinfo`, the header APISIX' `openid-connect` plugin sets. Its value is the
claims of the verified token, either base64-encoded (standard or URL alphabet, padded or not) or as plain JSON. The participant
id is the top-level claim named in `identity.participant-claim`, by default `participantId`. A request without the header, with
an undecodable one, or without a usable claim is answered with `401`; nothing else about the token is looked at, so an expired
or wrongly-addressed token is accepted if the gateway let it through.

A matching route for APISIX, whose `openid-connect` plugin verifies the token against the verifier's JWKS and clears a
client-supplied `X-Userinfo` before setting its own:

```yaml
plugins:
  openid-connect:
    discovery: https://verifier.provider.org/.well-known/openid-configuration
    client_id: contract-management
    bearer_only: true
    use_jwks: true
    set_userinfo_header: true
    claim_validator:
      issuer:
        valid_issuers: [ "https://verifier.provider.org" ]
      audience:
        claim: aud
        required: true
        match_with_client_id: true
```

**This only holds as long as the gateway is the only way to reach CM.** The header is not signed, so anyone who can open a
connection to CM's HTTP port can claim to be any participant: publish events in their name, subscribe for them, or delete their
subscriptions. Restrict the port to the gateway (NetworkPolicy, service mesh) and make sure the gateway strips the header from
incoming requests. The same holds for the management port, see [Publishing events](#publishing-events).

#### Publishing events

With `dataspace.events.publisher.enabled`, other participants subscribe a webhook (`sink`) to event types, and CM delivers
matching events to it. Events are published through the internal `POST :9090/events`. `publisher.origin` (CM's own DNS name) is
required, and so is `own-participant-id` or `general.did`, which becomes the `source` of every published event.
Subscribers are identified by the gateway, like the callers of the listener; the `dataspace.events.listener.identity.*`
configuration is shared with the listener and also applies when only the publisher is enabled.

A subscription follows the [CloudEvents Subscriptions API](https://github.com/cloudevents/spec/blob/main/subscriptions/spec.md),
only the protocol `HTTP` is supported:

```shell
curl -X POST https://cm.provider.org/subscriptions \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{
        "subscriberId": "did:web:consumer.org",
        "sink": "https://events.consumer.org/events",
        "types": [ "org.fiware.dataspace.example" ],
        "protocol": "HTTP",
        "protocolsettings": { "clientId": "contract-management", "scopes": [ "default" ] }
      }'
```

```json
{
  "id": "0f6a3c1e-8a52-4a3b-9a59-3f0c1f3f3a59",
  "subscriberId": "did:web:consumer.org",
  "sink": "https://events.consumer.org/events",
  "types": [ "org.fiware.dataspace.example" ],
  "protocol": "HTTP",
  "protocolsettings": { "clientId": "contract-management", "scopes": [ "default" ] },
  "expiresAt": "2026-10-31T09:00:00Z"
}
```

`clientId` and `scopes` are used for the OID4VP flow when the sink answers a delivery with `401` (only with `oid4vp.enabled`).
A `POST` is checked in this order:

1. The identity header must carry a participant id, else `401`.
2. `subscriberId` is required (`400`) and must equal that participant id, else `403`.
3. `sink` must be an absolute `https` URI (`http` too with `allow-insecure-sinks`), `types` must not be empty, `protocol` must
   be `HTTP` and `protocolsettings.clientId` is required, else `400` with a `reason`.
4. Without a configured `publisher.origin`, the request gets `503`.
5. Neither `max-subscriptions-per-subscriber` nor `max-subscriptions` may be reached, else `503`. Expired leases are evicted
   first, and a renewal does not count twice. A reached cap is a temporary refusal the subscriber retries; CM never answers
   `409`, which a subscriber reads as "the subscription already exists".
6. The sink must pass the webhook handshake, else `400` with the reason `handshake failed`.
7. Otherwise the subscription is activated and answered with `201`.

`DELETE /subscriptions/{id}` removes a subscription of the caller (`204`); an unknown id or one owned by another participant
gets `404`.

**Handshake:** CM sends `OPTIONS <sink>` with `WebHook-Request-Origin: <publisher.origin>`. The sink has to answer with a `2xx`
and a `WebHook-Allowed-Origin` equal to that origin (case-insensitive) or `*`. An optional `WebHook-Allowed-Rate` (requests per
minute, a positive integer or `*`) limits the deliveries to the sink. The handshake is bounded by `handshake-timeout`, single
requests by `sink-connect-timeout` and `sink-read-timeout`. CM only ever contacts the `sink` given in the request; no host is
derived from the subscriber id.

**Leases:** every subscription expires after `lease-ttl`; `expiresAt` tells when. To renew, the subscriber repeats the `POST`
for the same `sink`: the subscription keeps its id and pending events, and gets a new lease and the new types and protocol
settings. The handshake runs again; if it fails, the request gets `400` and the existing subscription stays unchanged until its
old lease expires.

**Delivery** is best-effort. Each event is `POST`ed in structured mode (`application/cloudevents+json`) to every active
subscription of its type, through a queue per subscription, so a slow or failing sink does not delay others. A full queue
drops further events until the sink catches up.

* A `2xx` is delivered. Redirects are never followed, so a `3xx` is a failure.
* Transport errors, timeouts, `408`, `429` and `5xx` are retried with exponential backoff (`initial-backoff`, doubled up to
  `max-backoff`) for at most `max-attempts` attempts in total; afterwards the event is dropped and logged.
* With `oid4vp.enabled`, a `401` makes CM run OID4VP against the sink host with the subscription's `clientId` and `scopes`,
  and retry once with the token. A `401` after that is not retried. Without OID4VP, a `401` drops the event like any other
  `4xx`.
* A `410 Gone` removes the subscription. Other `4xx` drop the event without a retry.
* The rate granted in the handshake counts HTTP requests, not events: an OID4VP-protected sink sees two requests per
  event (CM caches no OID4VP token), so grant twice the intended event rate.

The counter `dataspace.events.delivered` (tags `type`, `outcome` = `delivered`, `failed`, `gone`, `expired`, `overflow`) counts
the final result of every event at every subscription.

Subscriptions are kept in memory on a single replica: run the publisher with one replica. A restart loses all subscriptions
and their pending events, and subscribers recover by renewing.

**Publishing** is done on the management port, e.g. by the marketplace or operator scripts inside the cluster:

```shell
curl -X POST http://contract-management:9090/events -H "Content-Type: application/json" \
  -d '{ "type": "org.fiware.dataspace.example", "subject": "did:web:consumer.org", "data": { "offer": "urn:offer:1" } }'
```

`type` is required (`400`), `subject`, `data` and `id` are optional. CM fills `specversion` (`1.0`), `id` (a random UUID when
absent), `source` (`own-participant-id`), `time`, `dataspaceid` and `datacontenttype` (`application/json`), queues the event and
answers `202` with the event id and the number of matching subscriptions, e.g. `{"id": "...", "subscriptions": 2}`. Without
`own-participant-id` and `general.did` it answers `503`. The endpoint is not authenticated: keep port 9090 cluster-internal and
never expose it through the ingress. CM refuses to start the publisher when `endpoints.all.port` is unset or equal to
`micronaut.server.port`, since `POST /events` would then be public and collide with the listener.

**Security notes:**

* The subscriber chooses `sink`, `clientId` and `scopes`. With `oid4vp.enabled`, when the sink answers `401`, CM presents the
  credentials the sink host's OID4VP request asks for. The resulting token is bound to that host, which limits replay, but the
  credentials are disclosed to it. Sinks are not restricted to the `origin`s in `dataspace.events.publishers`, so only hold
  credentials in CM you are willing to present to any authenticated participant's host.
* Handshake and delivery never follow redirects, so a sink cannot redirect CM to an internal address. The OID4VP token
  fetch a sink's `401` triggers uses its own non-redirecting client as well, so a redirect cannot move it to another host
  either. It still goes to the OID4VP endpoints the sink host itself advertises, which is inherent to the flow: run CM
  where it cannot reach hosts you would not let a participant name as `sink`.
* Events are broadcast: every participant the gateway identifies may subscribe to every type and then receives every event
  of that type, including events whose `subject` is another participant. There is no per-subscriber type allowlist and no
  filtering by `subject`, unlike the `publishers[].types` of the listener. Only publish events through `POST :9090/events`
  that every authenticated participant of the data space may see.
* A failed handshake is answered only with `handshake failed`; the sink's status or the connection error is logged, never
  returned, so the API cannot be used to probe hosts reachable from CM.

#### Subscribing to publishers

With `dataspace.events.subscriber.enabled`, CM registers its own `POST /events` as webhook `sink` at every configured publisher
that has a `subscriptions-url`, using the publisher's `POST /subscriptions` (the API described above). This happens shortly after
startup (`initial-delay`) and whenever a refresh adds or changes a publisher. Each lease is renewed before it expires.

Startup fails unless all of the following hold:

* `dataspace.events.listener.enabled` is `true`, because the publisher's `OPTIONS` handshake is answered by the listener.
* `subscriber.sink` is the public absolute http(s) URL of the own `POST /events`, as the publishers reach it.
* `subscriber.protocol-settings.client-id` is set.
* `own-participant-id` or `general.did` is set. It is sent as `subscriberId` and has to equal the participant id that the
  publisher's gateway reads out of CM's token.

A `publishers[]` entry is only subscribed to if it has a `subscriptions-url`. CM never derives that URL from the publisher id or
origin, and entries without one are listener-only. A subscribable entry also needs:

* `types`: the event types CM subscribes to at that publisher.
* `origin`: it must equal the publisher's own `dataspace.events.publisher.origin`. The publisher sends it as
  `WebHook-Request-Origin` in the handshake, and the listener only grants configured origins.

An entry with a `subscriptions-url` but no `types` or no `origin` is skipped with a WARN and is not contacted. A wrong but
non-empty `origin` cannot be detected locally; the registration then fails with `400 handshake failed` and is retried.

**Two pairs of client credentials** are used:

* `subscriber.client-id` / `subscriber.scopes` are what CM uses for OID4VP towards the publisher's subscriptions API, i.e. when
  the publisher answers `401`. A publisher entry can override them with its own `client-id` / `scopes`. A blank `client-id`
  or an empty `scopes` list counts as "no override".
* `subscriber.protocol-settings.client-id` / `.scopes` are sent as `protocolsettings` in the subscription. They name the client
  at **CM's own** verifier that the publisher uses when CM's sink answers a delivery with `401`.

**Retries and renewal:**

* A failed registration (transport error, timeout, any non-`2xx` other than `409`, e.g. the `503` of a publisher at its cap)
  is retried without an attempt limit, because a publisher may be down for a long time. The wait starts at
  `retry.initial-backoff` and doubles up to `retry.max-backoff`. Until such a registration succeeds the publisher counts as
  `PENDING` and, once `validUntil` has passed, makes the health check `DOWN`.
* After a `201`, the lease is renewed after `renewal-fraction` of the remaining time until `expiresAt`, but never sooner than
  `min-renewal-delay`. A `renewal-fraction` outside `(0, 1)` falls back to `0.5`. A renewal repeats the `POST` for the same
  sink, so the publisher keeps the subscription id.
* A `409` counts as success: it is logged at INFO as "409 treated as existing subscription", and the publisher is registered
  again after `conflict-renewal-interval`. It is kept for publishers that report an existing subscription that way; a CM
  publisher never answers `409` (it renews with `201` and refuses a reached cap with `503`). The state `CONFLICT` in the
  health details keeps that case visible.
* A `201` without `expiresAt` is also registered again after `conflict-renewal-interval`.

**Configuration changes at runtime** are applied on every refresh and by a periodic check every `reconcile-interval`:

* An added publisher is registered right away, without a restart.
* A publisher whose `subscriptions-url`, `types`, `origin`, `client-id` or `scopes` changed is registered again immediately.
  So is every publisher when `sink`, `protocol-settings`, the global `client-id`/`scopes` or the own participant id changed.
  * A changed `types`, `origin` or credential keeps the subscription key `(subscriberId, sink)`. The publisher renews the
    subscription in place, and the old lease stays valid until then.
  * A changed `sink`, participant id or `subscriptions-url` means a different subscription. CM first sends a best-effort
    `DELETE <old subscriptions-url>/<old id>` with the old credentials, then the new `POST`. This frees the slot in the
    publisher's cap. Until the new registration succeeds, the publisher counts as `PENDING`.
* A removed publisher, or one whose `subscriptions-url` was set to `""`, is no longer retried or renewed and leaves the health
  check. CM sends a best-effort `DELETE <subscriptions-url>/<id>`; a failure is only logged, because the lease expires anyway.
* An entry that becomes invalid (no `types`, no `origin`) is dropped the same way and shows as `SKIPPED`.
* Without a known subscription id (never registered, or answered `409`), nothing can be deleted, and the old lease simply expires.

A key removed from the configuration keeps its previous value, see below. To make a publisher listener-only at runtime, set
`subscriptions-url: ""`. To drop a per-publisher override, set `client-id: ""` or `scopes: []`.

**Health:** the indicator `dataspace-subscriptions` is `UP` when every subscribable publisher holds a registration that has not
expired; once the first reconcile has run, that includes the case that no publisher is subscribable. Otherwise it reports the
status `DEGRADED`:

* from startup until the first reconcile (`initial-delay`), and after that until every subscribable publisher is registered;
* while a renewal keeps failing past `expiresAt` (a failed renewal before that stays `UP`, because the publisher still delivers);
* after a `sink`, participant id or `subscriptions-url` change, until the new registration succeeds;
* while the subscriber settings are invalid after a refresh (detail `subscriber-settings`).

It never reports `DOWN`. The publisher's handshake reaches CM through its Service or ingress. A pod that fails readiness would
never receive it, so registration could never succeed, and a failing liveness probe would restart the pod in a loop. `/health`
and `/health/readiness` include the indicator and keep answering `200` with `DEGRADED`; `/health/liveness` does not include it.
A `DOWN` of another indicator still wins. `SKIPPED` publishers do not affect the status.

The details map each publisher id to its `state` (`PENDING`, `REGISTERED`, `CONFLICT`, `EXPIRED` or `SKIPPED`), `expiresAt`,
and for `SKIPPED` the `reason`. Error responses of the publishers are only logged. An anonymous `/health` only shows the overall
status. The default `endpoints.health.details-visible` is `AUTHENTICATED`; to see the per-publisher details on the
cluster-internal port 9090, set it to `ANONYMOUS`.

```yaml
dataspace:
  events:
    own-participant-id: "did:web:consumer.org"
    listener:
      enabled: true
    publishers:
      - id: "did:web:provider.org"
        # must equal the provider's dataspace.events.publisher.origin
        origin: "cm.provider.org"
        types: [ "org.fiware.dataspace.example" ]
        subscriptions-url: "https://cm.provider.org/subscriptions"
        # optional, overrides subscriber.client-id / subscriber.scopes for this publisher
        client-id: "contract-management-provider"
        scopes: [ "events" ]
    subscriber:
      enabled: true
      sink: "https://cm.consumer.org/events"
      client-id: "contract-management"
      scopes: [ "default" ]
      protocol-settings:
        client-id: "contract-management"
        scopes: [ "default" ]
```

#### Watching the trust anchor registry

The CM of the **trust anchor** can announce participants that left the data space. With
`dataspace.events.tir-watcher.enabled`, it polls the Trusted Issuers Registry (TIR) configured as the HTTP service
`trusted-issuers-registry` (`micronaut.http.services.trusted-issuers-registry.url`) every `interval`, enumerates all listed
DIDs through `GET /v4/issuers` and compares them with the previous snapshot. A DID that is missing for `confirmations`
consecutive complete cycles is published to the subscribers as an event of type `org.fiware.dataspace.participant.removed`
with `subject` and `data.participantId` set to the DID. Additions are taken into the snapshot but never announced. The latency
of an announcement is therefore about `confirmations × interval` (by default 2 minutes).

Startup fails with a message naming the key unless `publisher.enabled` is `true` (the events go out through the publisher),
`own-participant-id` or `general.did` is set (it is the event `source`), and every `tir-watcher.*` value is in its range.

| Key (`dataspace.events.tir-watcher.`) | Default | Range | Meaning |
|---|---|---|---|
| `enabled` | `false` | | Startup-time condition, needs a restart. |
| `initial-delay` | `PT30S` | positive | Delay of the first cycle after startup. |
| `interval` | `PT1M` | positive | Delay between the end of one cycle and the start of the next. |
| `page-size` | `100` | 1..100 | `page[size]` of the first request. |
| `max-pages` | `1000` | >= 1 | Pages per enumeration; a registry that needs more is never enumerated completely. |
| `confirmations` | `2` | >= 1 | Consecutive complete cycles a DID must be missing before its removal is announced. |
| `max-removals-per-cycle` | `5` | >= 0 | Circuit breaker, see below. `0` holds back every removal for the operator. |

An out-of-range value fails startup. After a refresh it is clamped into its range instead; an `interval` or `initial-delay`
that is not positive or empty falls back to its default.

**Safeguards.** The watcher only ever acts on a registry it can trust:

* **Complete enumeration only.** A cycle counts only if every page answered `2xx`, every non-empty page reports the `total` of
  the first page, no DID is blank, and the number of distinct DIDs equals that `total`. The pages are followed through the
  `page[after]` (and `page[size]`) of each page's `links.next`; host and path of the link are ignored, so the registry may
  report a host CM cannot reach. The cursor must be an **integer**, as the TIR API specifies it (FIWARE trusted-issuers-list
  and 0- or 1-based registries work). A registry with string cursors never completes an enumeration and logs a WARN every
  cycle; nothing is ever announced from it. An incomplete cycle (outcome `incomplete`) changes nothing and logs a WARN
  (`tir_enumeration_failed`); it neither confirms nor refutes a pending removal.
* **Confirmations.** A removal is announced only after `confirmations` complete cycles in a row. A DID that is listed again
  before that is forgotten.
* **Circuit breaker.** If more than `max-removals-per-cycle` DIDs are missing at once, nothing changes and nothing is
  announced (outcome `breaker`, alert `tir_mass_removal`). The count covers **all** DIDs missing from the snapshot, including
  those still awaiting confirmation from earlier cycles, so a registry that loses entries a few at a time is caught as well.
  Example (cap 5, confirmations 2): 3 DIDs removed in one cycle and 3 more in the next are 6 missing, and the breaker trips.
  It trips again on every cycle until the registry is restored or the cap is raised. If the removals are legitimate, raise
  `max-removals-per-cycle` through `POST :9090/refresh` and keep it raised for at least `confirmations` cycles
  (≈ `confirmations × interval`): a tripped cycle does not count as a confirmation, so lowering the cap earlier trips the
  breaker again and the removals stay pending.
* **Empty registry.** A registry that turns empty (from at least one participant to none) is far likelier wiped or
  misconfigured than really empty. Nothing changes and nothing is announced (outcome `empty`, alert `tir_empty`) until a
  participant is listed again. Consequently the removal of the **last** participant is never announced; it would have no
  recipient anyway. A TIR that is empty from the start is not alerted on: the first cycle logs one WARN
  (`The trust anchor registry is empty; nothing to watch yet.`) and the watcher waits for participants.
* **Silent first cycle.** The first complete cycle only takes the snapshot (outcome `initial`). The state is kept in memory,
  so after a restart the first cycle is silent again, and removals that happened while CM was down are not announced.

**Delivery.** A confirmed removal is published once at least one subscription of a participant **other than the removed one**
is registered for `org.fiware.dataspace.participant.removed` (the removed participant's own lease may still be active). Until
then, e.g. right after a publisher restart or in a subscriber's lease gap, it is retried on every **complete** cycle. While
cycles end `incomplete`, `empty` or `breaker`, the retry waits, so an alert or an unreachable registry also delays already
confirmed removals. Once the event is queued for at least one such subscription, the watcher is done with it: a queue that
overflows or a delivery that exhausts its retries afterwards is not retried by the watcher, and subscribers that register
later do not get it.

**Alerts** (`tir_empty`, `tir_mass_removal`) are visible in three ways:

* an ERROR log line with the code and the counts when the alert is raised, DEBUG while it persists, and INFO when the next
  `complete` or `initial` cycle clears it;
* the counter `dataspace.tir.cycles` with tag `outcome` (`initial`, `complete`, `incomplete`, `empty`, `breaker`), counted every
  cycle; announced removals count in `dataspace.tir.removals`;
* the health indicator `dataspace-tir-watcher`, which reports `DEGRADED` with `reason` `tir_empty` or `tir_mass_removal`. It
  also reports `DEGRADED` with `reason: no_successful_cycle` when no cycle ended `complete` or `initial` for
  `3 × interval` (counted from when the first cycle was due, so the initial delay never counts). Its details show the last
  outcome, the last successful cycle and the sizes of the snapshot, the pending and the undelivered removals. As for the
  subscriptions, `DEGRADED` keeps `/health` and the liveness and readiness probes at `200`.

```yaml
dataspace:
  events:
    own-participant-id: "did:web:trust-anchor.org"
    publisher:
      enabled: true
      origin: "cm.trust-anchor.org"
    tir-watcher:
      enabled: true
      interval: PT1M
      confirmations: 2
      max-removals-per-cycle: 5
micronaut:
  http:
    services:
      trusted-issuers-registry:
        url: http://trusted-issuers-list:8080
```

#### Reacting to participant removals

A participant can act on the `org.fiware.dataspace.participant.removed` events of a trust anchor (see above). The handling is
active when `listener.enabled` is `true` and at least one entry of `dataspace.events.publishers` lists that type in `types`.
Each such publisher has an `on-participant-removed` policy. It is applied in two places: to the publisher's events (the
`subject`, or `data.participantId` when there is no subject, is the removed participant) and in a reconciliation sweep that
runs per publisher shortly after startup and then on a timer. Nothing is written to the trusted-issuers-list (TIL) unless a
writing mode is configured: the default `report` only reads and reports.

```yaml
dataspace:
  events:
    dataspace-id: "my-dataspace"
    listener:
      enabled: true
    publishers:
      - id: "did:web:trust-anchor.org"
        origin: "cm.trust-anchor.org"
        types: [ "org.fiware.dataspace.participant.removed" ]
        on-participant-removed:
          mode: dataspace
          keep: [ "did:web:partner.org" ]
          overrides:
            - id: "did:web:special.org"
              mode: report
          max-removals-per-run: 5
          adopt-unattributed: false
          max-adoptions-per-run: 50
          registry-url: "https://tir.trust-anchor.org"
    participant-removed:
      page-size: 100
      max-pages: 1000
      reconcile:
        initial-delay: PT1M
        interval: PT15M
```

| Key (`publishers[].on-participant-removed.`) | Default | Meaning |
|---|---|---|
| `mode` | `report` | `report`, `dataspace`, `granted` or `full`, see below. Every mode but `report` writes and requires `registry-url`. |
| `keep` | `[]` | Identifiers never touched, whichever publisher removes them. |
| `overrides` | `[]` | `{id, mode}` pairs replacing `mode` for one identifier (a list, because DIDs cannot be property-map keys). |
| `max-removals-per-run` | `5` | Breaker of the sweep, >= 0, see below. |
| `adopt-unattributed` | `false` | Adoption of unattributed entries, see below. |
| `max-adoptions-per-run` | `50` | Adoptions per sweep, >= 0; the rest follow in the next sweep. |
| `registry-url` | | Base URL of the publisher's trusted issuers registry (`GET /v4/issuers`). Needed by the sweep, the stale-event check and adoption. |

`dataspace.events.participant-removed.page-size` (1..100) and `max-pages` (>= 1) bound the listings of the TIL and of the
registries; `reconcile.initial-delay` and `reconcile.interval` (positive) time the sweep. All of these keys are refreshable.

**Where an entry comes from.** CM tells the origin of what an issuer holds in the TIL by its scope:

* a **membership grant** is `scope=<publisher id>` with `scopeType=dataspace-membership`;
* an **order grant** is an untyped scope (`scope=<order id>`), as CM writes it for a product order;
* credentials **without a scope** are **unattributed**, e.g. entries an operator provisioned by hand.

**Modes.** What each mode does with the identifier X that publisher P announces as removed:

| what X holds | `report` | `dataspace` | `granted` | `full` |
|---|---|---|---|---|
| P's membership grant | kept | revoked | revoked | issuer deleted |
| another publisher's membership grant | kept | kept | kept | issuer deleted |
| order grants | kept | kept | revoked² | issuer deleted |
| only unattributed credentials | kept | kept¹ | kept¹ | issuer deleted |
| unattributed credentials next to grants | kept | kept | kept | issuer deleted |

¹ Revoked instead when adoption is allowed (see below) and X comes from an **event**: the publisher's explicit announcement
attributes an entry holding nothing but unattributed credentials to its data space. Without adoption, unattributed entries
survive every mode but `full`. Unattributed credentials next to grants are never touched, because the TIL cannot tell
which of them belong to which grant.

² Only when X is attributed to P **alone**: it holds P's membership grant and no other publisher's. Order grants carry no
data space, so they are attributed through the entry's memberships. If X also holds P2's membership, its order grants may
stem from contracts in P2's data space and are kept; if X holds no membership at all, nothing ties them to P and they are
kept and reported. A later removal by P2 (then the only membership) revokes them.

**Keep and overrides.** An identifier on `keep` is protected against **every** publisher, although `keep` is configured
under one publisher: P1's `keep` of X stops P2 in `full` from deleting X's issuer entry. To stop only P's removals of X from
writing while other publishers still act, use an override `{id: X, mode: report}` under P. The own participant id
(`own-participant-id`, falling back to `general.did`) is never touched either, by events, the sweep or adoption: a `full`
event about our own removal does not delete our own issuer entry. An event whose `subject` is the own id is answered `202`
before it reaches the handling (see the checks above) and is not counted; only an event naming the own id in
`data.participantId` and the sweep count it, as `mode=keep, action=kept`. An identifier on `keep` that also has an override
gets a WARN at startup: `keep` wins.

**Invalid policy values.** A value that cannot be converted, e.g. `mode: dataspce` or a non-numeric `max-removals-per-run`,
drops the **whole** publisher entry, which then is not authorized for any event type. For an entry that carries
`on-participant-removed` or lists participant.removed in `types`, startup fails with a message naming
`dataspace.events.publishers[<index>]` (when entries repeating an id differ only in a value outside the policy, it names
each of them it cannot tell apart); for other entries it is a WARN, as before. After a refresh the sweep logs the same
findings, once per configuration change (ERROR for a dropped removal publisher). `on-participant-removed` takes effect only
when the entry's `types` include participant.removed; otherwise it gets a WARN and is ignored. A publisher split over several
entries needs a single policy: repeated removal entries of one id with different policies fail startup (after a refresh, the
first entry's policy applies).

**Unknown keys.** The binding accepts a key whose camelCase form is the field name, i.e. kebab-case or camelCase
(`registry-url`, `registryUrl`), and silently ignores any other key, so a misspelt or miscased key such as `mod`,
`max-removal-per-run`, `maxremovalsperrun`, `registryURL` or `registry_url` leaves the field at its default (`report`, no
registry). A key under `on-participant-removed` that names no policy field fails startup for an entry listing
participant.removed in `types`, naming `dataspace.events.publishers[<index>].on-participant-removed.<key>`; on other
entries it is a WARN. An unknown key directly on a publisher entry is a WARN only, so existing deployments keep starting,
except on an entry listing participant.removed when the key holds policy fields (e.g. `on-participant-remove: {keep: [...]}`):
the whole policy would be lost, a `keep` list included, so another publisher in `full` could delete an identifier the
operator meant to protect. That fails startup too. Such a key counts as a misspelt policy when it names the participant
(e.g. `participant-removed`) or sets a policy-specific field (`keep`, `overrides`, `registry-url`, ...); an unrelated map
that only has a `mode` key stays a WARN.

**Lists.** `types`, `keep` and override `id`s are YAML lists. A comma-separated string is not split: `types: "a,
org.fiware.dataspace.participant.removed"` binds as one type, so the entry is no removal publisher, and
`keep: "did:a,did:b"` protects neither identifier. Such a string fails startup for an entry listing participant.removed
(also inside the string), naming the key; on other entries it is a WARN.
After a refresh the sweep logs these findings like the ones above.

Startup also fails, naming the key, for an override without `id` or `mode`, a `registry-url` that is not an absolute
http(s) URL, a value out of its range, a writing mode or writing override without `registry-url` (without it there is no
sweep, so a failed or dropped removal event is never completed), and an `adopt-unattributed` whose only obstacle is a missing
`registry-url`. After a refresh, a writing mode without `registry-url` acts as `report` (ERROR once) and out-of-range values
are clamped.

**Stale events.** An event may arrive late, after X was re-admitted. Before any write, the event path therefore asks P's
registry (`GET {registry-url}/v4/issuers/{did}`) whether X is listed again. If it is, nothing is written (`action=still_listed`,
`202`); a `404` lets the cleanup proceed; any other answer is a `502`, so the publisher retries. A registry that lags behind
the announcement also gives `still_listed`; for an entry that holds P's membership the next sweep completes the cleanup. An
unattributed-only entry is not completed by the sweep, see the adoption caveat below.

**No empty issuer entries.** An issuer that holds no credential after a `dataspace` or `granted` cleanup would still be listed
in the TIL's registry view, and a verifier would then accept credentials of that issuer typed only with base types such as
`VerifiableCredential`. So when a cleanup would leave the entry empty, its last write deletes the issuer instead. The entry is
read again just before; if anything else appeared meanwhile, only the scope is revoked. An entry found already empty in a
writing mode is deleted as well. A credential written between that read and the delete is lost (the TIL has no conditional
delete). The report then shows the mode's revoking action with `held: []`.

**Convergent cleanup.** The TIL has no transactions. All reads come first, so a failed read changes nothing. Writes follow
with P's membership grant **last**, so an interrupted cleanup still leaves P's membership in place and the next sweep (or a
retry of the publisher) finds the entry and completes it. Every write is idempotent. When a TIL call fails while an event is
handled, the listener answers `502 Bad Gateway` (outcome `downstream_failed`) and forgets the event, so the publisher retries
it.

**Bounded publisher retries; the sweep is the reliable path.** A publisher retries a `502` only for a while: a CM publisher
makes `delivery.max-attempts` attempts (by default 5, about 15 s) and then drops the event. A TIL outage longer than that loses
the event, and the sweep completes the cleanup, which is why writing modes require `registry-url`. One loss remains: with
adoption allowed, an unattributed-only entry that P delisted before the sweep adopted it is removed only by an event; if that
event is dropped or answered `still_listed`, the entry stays until a later event names it or the operator removes it.

**The sweep.** Every `reconcile.interval` and per publisher with a `registry-url`, CM lists the issuers holding P's membership
grant in the TIL and the participants in P's registry. Those holding P's membership although P no longer lists them are
handled with P's policy. The sweep never removes unattributed entries: an unattributed-only entry P does not list cannot be
told apart from an operator-managed one; only an event, which names the identifier, removes it. A registry listing that is
incomplete changes nothing.

**Breaker.** If a sweep finds more write candidates (identifiers whose effective mode writes) than `max-removals-per-run`, it
applies none of them and reports all of them: ERROR and `action=breaker` once when it trips or the candidates change, INFO
when it clears, and the health stays `DEGRADED` in between. `report`, `keep` and own-id candidates never trip it. Applying the
first N per run was rejected: a wrongly truncated registry would empty the TIL a few entries at a time. Events are not capped;
the trust anchor's watcher has its own breaker. `0` holds back every write for the operator.

**Adoption.** With `adopt-unattributed: true`, the sweep attributes entries of P's listed participants that hold nothing
but unattributed credentials to P's data space: it copies them into a membership grant of P and then removes the unattributed
originals. Once adopted, such an entry is a normal sweep candidate when P delists it. Adoption is allowed only when
`dataspace-id` is set, exactly one publisher announces participant.removed, it has `adopt-unattributed: true` and a
`registry-url`; otherwise it is refused (WARN, `action=adoption_refused`) and nothing is adopted. It assumes that this
participant belongs to P's data space alone: a participant in several data spaces must not enable it. The event filter does
not detect a second data space, because events without `dataspaceid` pass. At most `max-adoptions-per-run` entries are
adopted per sweep. `keep` and the own id are never adopted. The entry is read again before the originals are removed and
skipped if they changed; a credential added between that read and the removal is still lost (accepted race).

##### Writing membership grants

Contract Management itself writes membership grants only through adoption. Other components (e.g. onboarding, or entries
provisioned by the data-space-connector chart) that grant a participant membership of a data space must write them as
`scope=<publisher id>`, exactly the publisher's `id` in `dataspace.events.publishers` (the `source` of its events), with
`scopeType=dataspace-membership`, one grant per data space:

```shell
curl -X PUT "http://trusted-issuers-list:8080/issuer/did:web:consumer.org/credential?scope=did:web:trust-anchor.org&scopeType=dataspace-membership" \
  -H "Content-Type: application/json" \
  -d '[ { "credentialsType": "MembershipCredential", "claims": [] } ]'
```

An entry written with an untyped scope is treated as an order grant, one without a scope as unattributed: such entries are
only reported, never revoked in `dataspace`, and survive every mode but `full`.

**Reporting.** Every decision is visible in three ways:

* a log line `Participant <did> removed by <publisher> (mode <mode>, <trigger>): <action>; still held: ...`;
* the counter `dsc.dataspace.events.participant.removed` (Prometheus `dsc_dataspace_events_participant_removed_total`) with
  the tags `publisher` (a configured publisher id), `mode` (`report`, `dataspace`, `granted`, `full`, or `keep` for an
  identifier on `keep` or the own id) and `action` (`reported`, `kept`, `not_held`, `still_listed`, `revoked_membership`,
  `revoked_granted`, `deleted_issuer`, `adopted`, `adoption_refused`, `failed`, `breaker`). Events count every time; the sweep
  counts an identifier only when it is new for the publisher or its mode changed, so a standing drift is not added every
  interval. The gauge `dsc.dataspace.events.participant.removed.held{publisher}` (Prometheus
  `dsc_dataspace_events_participant_removed_held`) is that standing drift: identifiers still holding P's membership although
  P's registry no longer lists them;
* the read-only management endpoint `GET :9090/participants-removed`, per removal publisher its effective `mode`, the
  shared `til` state (`supported`, `pending`, `unverified`, `too-old`), `adoption` (`allowed`, `refused`, `off`), `breaker`
  (`tripped`, `clear`) and the latest outcome per participant, with what it still holds:

```json
[ {
  "publisher": "did:web:trust-anchor.org", "mode": "dataspace", "til": "supported", "adoption": "off", "breaker": "clear",
  "participants": [ {
    "id": "did:web:gone.org", "trigger": "event", "lastSeen": "2026-11-10T12:00:00Z", "mode": "dataspace",
    "action": "revoked_membership", "applied": true, "attributable": true, "held": [], "unattributedCount": 0
  } ]
} ]
```

`held`, `attributable` and `unattributedCount` are `null` when the entry was not read (e.g. a `too-old` TIL in `report`).
`attributable: true` with `held: []` means that nothing is held any more. The endpoint shows which issuers this participant
trusts under which scopes, so it is served on the cluster-internal management port only; disable it with
`endpoints.participants-removed.enabled: false`.

The health indicator `dataspace-participant-removed` reports `DEGRADED` while a required TIL capability is `unverified` or
`too-old` or a breaker is tripped. It never reports `DOWN`, so the liveness and readiness probes are not affected.

**Required trusted-issuers-list version.** The handling needs typed scopes (TIL-1, `scopeType` filter), the
`GET /issuer/{did}/scopes` endpoint (TIL-2) and, while adoption is allowed, unscoped addressing (TIL-3). The TIL has no version
endpoint, so CM probes each capability without side effects, each probe bounded by 5 s:

* **too old** for a configured writing mode, writing override or allowed adoption: startup fails, naming the capability, the
  configuration that needs it and the TIL ticket;
* **too old** with `report` only: ERROR once, health `DEGRADED`, events are reported without what the participant holds, and
  no sweep runs;
* **unreachable** or not decided (`401`, `403`, `5xx`, timeout): startup continues with a WARN, health `DEGRADED`, events are
  answered `502` (also in `report`, which reads what the participant holds) and sweeps are skipped until a re-probe (at most
  every 30 s) succeeds. Events dropped meanwhile are completed by the first sweep after that.

One case is not caught at startup: a TIL with TIL-1 but without TIL-2 that is **empty** at startup (a fresh installation)
offers no issuer to probe `/scopes` with. It counts as `pending`, which does not block, since an empty TIL holds nothing. When
the first issuer appears, the re-probe finds the TIL too old: ERROR once, health `DEGRADED`, writes blocked and events in
writing modes answered `502`. After the TIL is upgraded and CM restarted or refreshed, the sweep completes the cleanup. The
same holds after a refresh that switches to a writing mode while the TIL is too old.

**Upgrade impact.** Deployments whose publishers already list participant.removed in `types` activate the handling with this
release, in `report` mode: it only reads, degrades instead of failing on a too-old TIL, and turns the health `DEGRADED`, never
`DOWN`. Startup fails only for new configuration (a writing mode, an override or adoption that the TIL is too old for, or that
lacks `registry-url`), with two exceptions:

- Such a deployment that runs the management endpoints on the server port, or leaves `endpoints.all.port` unset, now fails
  startup in the management-port check, also in `report` mode, because `GET /participants-removed` would be public. Set a
  separate cluster-internal `endpoints.all.port`, or disable the report with `endpoints.participants-removed.enabled: false`
  (or all management endpoints with `endpoints.all.enabled: false`).
- Any deployment with the listener enabled now fails startup for an existing publisher entry that lists participant.removed in
  `types` (or carries `on-participant-removed`) but has a value that does not convert, e.g. a misspelt field value. Such an
  entry was already dropped before, so the publisher was silently unauthorized for all its event types; the error names
  `dataspace.events.publishers[<index>]`. Fix the value. An entry without participant.removed that fails the same way only
  logs a WARN.

#### Changing the configuration without a restart

All `dataspace.events.*` values except `listener.enabled`, `listener.watch.enabled`, `publisher.enabled`, `subscriber.enabled`,
`tir-watcher.enabled` (startup-time conditions) and `publisher.sink-connect-timeout`/`publisher.sink-read-timeout` (used to build the sink client) are
refreshable; these need a restart. All other `publisher.*` values, e.g. the caps, the lease and the delivery settings, apply to
the next request or delivery attempt (`delivery.queue-size` only to subscriptions that receive their first event afterwards).
All other `subscriber.*` values and the publishers' `subscriptions-url`, `client-id` and `scopes` are refreshable as well; see
[Subscribing to publishers](#subscribing-to-publishers) for how the subscriber reacts.
All other `tir-watcher.*` values apply from the next cycle on, `participant-removed.*` and the `on-participant-removed`
policies from the next event or sweep.
A refresh is triggered by:

* `POST :9090/refresh` reloads the configuration. The management port is not authenticated, keep port 9090 cluster-internal.
* With `listener.watch.enabled` and `listener.watch.path`, CM watches the configuration directory and refreshes on any change in it.

A `/refresh` call and a watch-triggered refresh never run at the same time; the later one waits for the running one.

When the configuration comes from a Kubernetes ConfigMap, mount it as a directory (e.g. `mountPath: /config` with
`MICRONAUT_CONFIG_FILES=/config/application.yaml` and `watch.path: /config`), **not** with `subPath`: kubelet never updates
`subPath` mounts, so neither `/refresh` nor the watch would ever see a change. Kubelet propagates an update with a delay of up to
about a minute.

### Development

In order to support the development, a local environment can be started via ```mvn clean install -Pdev```. 

## Supported Events

Contract Management supports events from different parts of the TMForum API.

### Catalog

In order to provide integration with the [IDSA Protocols Catalog API](https://docs.internationaldataspaces.org/ids-knowledgebase/dataspace-protocol), 
Catalog objects from TMForum are translated and pushed to Rainbow. 

#### Create

When receiving a "CatalogCreateEvent", it tries to translate a TMForum Catalog into an IDSA Catalog. The Catalog tilte ```dctColonTitle``` is taken from 
the ```name``` property of the TMForum object. 

#### StateChange

When receiving a "CatalogStateChangeEvent" the changes from the TMForum Catalog Object are updated within the IDSA Catalog Object. 

#### Delete

The Catalog with the same ```id``` as the contained Catalog-Object will be deleted.

### Product Offering

In order to provide integration with the [IDSA Protocols Catalog API](https://docs.internationaldataspaces.org/ids-knowledgebase/dataspace-protocol),
Product Offering objects from TMForum are translated to Data Services in Rainbow.

#### Create

The "ProductOfferingCreateEvent" will be translated to Data Services and pushed to the Rainbow API. Only Offerings that are connected to an existing Catalog 
will be pushed.
The offering requires a connected ProductSpecification, that contains ```productSpecCharacteristic``` if type:

```endpointUrl```: Will be used as "dcat:endpointURL" in the Data Service
```endpointDescription```: Will be used as "dcat:endpointDescription" in the Data Service

#### StateChange

When receiving a "CatalogStateChangeEvent" the changes from the TMForum ProductOffering Object are updated within the IDSA Data Service Object.

#### Delete

The Data Service with the same ```id``` as the contained ProductOffering-Object will be deleted.

### Quote

In order to support the IDSA Contract Negotiation the Contract Management integrates the TMForum Quote-API. See [Dataspace Connector DSP Integration](https://github.com/FIWARE/data-space-connector/blob/main/doc/DSP_INTEGRATION.md#contract-negotiation)
for more details.

### Product Order

The Product Order Object is used to integrate the TMForum with the authentication and authorization components of the Dataspace Connector.

#### Create / StateChange

A Product Order event will update the contract negotiation in TMForum when a Quote-Object is connected. Beside that, only Product Orders in state "completed" will be handled.

In case of a "completed" Product Order, the Product Specification linked in either the specification or the connected Quote will be taken and any Specification Characteristic
of type ```credentialsConfiguration``` will be inserted to the connected TrustedIssuers-List. Value can contain a list of Claim-Objects, as defined by the [Trusted Issuers List API](https://github.com/FIWARE/trusted-issuers-list/blob/main/api/trusted-issuers-list.yaml#L147).
An example specification would look like:
```json
{
    "brand": "M&P Operations",
    "version": "1.0.0",
    "lifecycleStatus": "ACTIVE",
    "name": "M&P K8S",
    "productSpecCharacteristic": [
      {
        "id": "credentialsConfig",
        "name": "Credentials Config",
        "valueType": "credentialsConfiguration",
        "productSpecCharacteristicValue": [
          {
            "isDefault": true,
            "value": {
              "credentialsType": "OperatorCredential",
              "claims": [
                {
                  "name": "roles",
                  "path": "$.roles[?(@.target==\\\"my-target-service\\\")].names[*]",
                  "allowedValues": [
                    "OPERATOR"
                  ]
                }
              ]
            }
          }
        ]
      }
    ]
  }
```

## License

Trusted-Issuers-List is licensed under the MIT License. See LICENSE for the full license text.

© 2023 FIWARE Foundation e.V.