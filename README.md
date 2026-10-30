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
| `dataspace.events.listener.verifier.jwks-url` | `DATASPACE_EVENTS_LISTENER_VERIFIER_JWKS_URL` | JWKS of the verifier issuing the bearer tokens, required with the listener | - |
| `dataspace.events.listener.verifier.issuer` | `DATASPACE_EVENTS_LISTENER_VERIFIER_ISSUER` | Expected token issuer, not checked if unset | - |
| `dataspace.events.listener.verifier.audience` | `DATASPACE_EVENTS_LISTENER_VERIFIER_AUDIENCE` | Expected token audience, required with the listener | - |
| `dataspace.events.listener.verifier.participant-claim` | `DATASPACE_EVENTS_LISTENER_VERIFIER_PARTICIPANT_CLAIM` | Top-level token claim carrying the caller's participant id | participantId |
| `dataspace.events.listener.verifier.algorithms` | `DATASPACE_EVENTS_LISTENER_VERIFIER_ALGORITHMS` | Accepted JWS signature algorithms (RSA or EC family, exact names, e.g. `ES256`, `ES384`, `PS256`; EdDSA, ES256K and anything else fail startup) | ES256,RS256 |
| `dataspace.events.listener.dedup.max-entries` | `DATASPACE_EVENTS_LISTENER_DEDUP_MAX_ENTRIES` | Number of remembered `(source, id)` pairs | 10000 |
| `dataspace.events.listener.dedup.ttl` | `DATASPACE_EVENTS_LISTENER_DEDUP_TTL` | How long a `(source, id)` pair is remembered | PT1H |
| `dataspace.events.listener.watch.enabled` | `DATASPACE_EVENTS_LISTENER_WATCH_ENABLED` | Refresh when the config directory changes (startup only, needs a restart) | false |
| `dataspace.events.listener.watch.path` | `DATASPACE_EVENTS_LISTENER_WATCH_PATH` | Watched configuration directory (e.g. the ConfigMap mount `/config`, not the file) | - |
| `dataspace.events.publishers` | - | List of `id` (participant id), `origin` (webhook origin) and `types` (allowed event types) | [] |
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

All `dataspace.events.*` values except the three `enabled` flags and the two `sink-*-timeout`s are reloaded on a refresh without
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

1. The bearer token must be a valid JWT of the verifier (`jwks-url`, `audience`, optional `issuer`), else `401`.
2. The token must carry the participant id claim (`participant-claim`), else `401`.
3. The event `source` must equal that participant id, else `403`.
4. If both the event and `dataspace-id` name a data space, they must match, else `403`.
5. A type no handler supports is answered with `202` and ignored.
6. The `(source, type)` pair must be configured in `dataspace.events.publishers`, else `403`.
7. A `(source, id)` seen before is answered with `202` and not dispatched again; while the first delivery is still being processed,
   the retry gets `503` with `Retry-After`.
8. An event whose `subject` is this participant (`own-participant-id`) is answered with `202` and not dispatched.
9. Otherwise the event is dispatched and answered with `202`.

The counter `dataspace.events.received` (tags `type`, `outcome`) counts every request; `type` is `other` for types without a handler.

The verifier has to issue tokens with the CM audience and the participant id as a top-level `participantId` claim (or the claim
configured in `participant-claim`).

#### Publishing events

With `dataspace.events.publisher.enabled`, other participants subscribe a webhook (`sink`) to event types, and CM delivers
matching events to it. Events are published through the internal `POST :9090/events`. `publisher.origin` (CM's own DNS name) is
required, and so is `own-participant-id` or `general.did`, which becomes the `source` of every published event.
Subscribers authenticate with a bearer token of the verifier; the `dataspace.events.listener.verifier.*` configuration is
shared with the listener and also applies when only the publisher is enabled.

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

1. The bearer token must be a valid JWT of the verifier carrying the participant id claim, else `401`.
2. `subscriberId` is required (`400`) and must equal that participant id, else `403`.
3. `sink` must be an absolute `https` URI (`http` too with `allow-insecure-sinks`), `types` must not be empty, `protocol` must
   be `HTTP` and `protocolsettings.clientId` is required, else `400` with a `reason`.
4. Without a configured `publisher.origin`, the request gets `503`.
5. Neither `max-subscriptions-per-subscriber` nor `max-subscriptions` may be reached, else `409`. Expired leases are evicted
   first, and a renewal does not count twice.
6. The sink must pass the webhook handshake, else `400` with the reason `handshake failed`.
7. Otherwise the subscription is activated and answered with `201`.

If the verifier's JWKS cannot be fetched, the request gets `503` with `Retry-After`. `DELETE /subscriptions/{id}` removes a
subscription of the caller (`204`); an unknown id or one owned by another participant gets `404`.

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
* Handshake and delivery never follow redirects, so a sink cannot redirect CM to an internal address.
* A failed handshake is answered only with `handshake failed`; the sink's status or the connection error is logged, never
  returned, so the API cannot be used to probe hosts reachable from CM.

#### Changing the configuration without a restart

All `dataspace.events.*` values except `listener.enabled`, `listener.watch.enabled`, `publisher.enabled` (startup-time
conditions) and `publisher.sink-connect-timeout`/`publisher.sink-read-timeout` (used to build the sink client) are refreshable;
these need a restart. All other `publisher.*` values, e.g. the caps, the lease and the delivery settings, apply to the next
request or delivery attempt (`delivery.queue-size` only to subscriptions that receive their first event afterwards).
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