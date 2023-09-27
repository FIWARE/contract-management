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
| `general.til.credentialType`           | `GENERAL_TIL_CREDENTIALTYPE`           | Credential type for which the permissions/claims shall be added to | "MyCredential"                   |
| `general.til.claims`                   | `GENERAL_TIL_CLAIMS[]`                 | The claims that shall be added to the Trusted Issuers List         | ""                               |

## License

Trusted-Issuers-List is licensed under the MIT License. See LICENSE for the full license text.

© 2023 FIWARE Foundation e.V.