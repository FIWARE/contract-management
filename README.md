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