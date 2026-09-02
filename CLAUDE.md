# Contract Management

## Overview
A Micronaut-based service that manages contracts in a FIWARE IAM ecosystem. It integrates with TM Forum APIs (product ordering, catalog, agreements, quotes), supports OID4VP (OpenID for Verifiable Presentations) authentication, and connects to a DSP (Dataspace Protocol) Rainbow adapter.

## Tech Stack
- Language: Java 21
- Build: Maven (parent: Micronaut Platform 4.7.1)
- Framework: Micronaut 4.7.1
- Crypto: BouncyCastle 1.81 (`bcprov-jdk18on`, `bcpkix-jdk18on`), Nimbus JOSE JWT 10.5
- OID4VP: `io.github.wistefan:oid4vp-client-lib:0.0.5`
- Code Generation: Lombok 1.18.36, MapStruct 1.5.3, OpenAPI Generator
- Test: JUnit 5, Mockito, Awaitility, Unirest

## Project Structure
```
src/main/java/org/fiware/iam/
  Application.java                  # Micronaut entry point
  bean/
    Oid4VpBeanFactory.java          # OID4VP bean creation (OID4VPClient, credentials, signing)
    NotificationBeanFactory.java    # Notification subscription beans
  cert/
    CertReader.java                 # PEM private key and X.509 certificate loading
  configuration/
    Oid4VpConfiguration.java        # OID4VP config: Holder record, ProxyConfig, trust anchors
    GeneralProperties.java          # General app properties (DID, org roles)
    NotificationConfig.java         # Notification entity subscriptions
    NotificationProperties.java     # Notification host/timing config
    EventType.java                  # Notification event type enum
  domain/
    ContractManagement.java         # Core domain interface
  dsp/                              # DSP / Rainbow adapter integration
  http/
    Oid4VpAuthHandler.java          # OID4VP authentication filter
    AuthHandler.java                # General auth filter
  management/                       # Contract management adapter/controller
  pap/                              # Policy administration point
  til/                              # Trusted issuers list adapter
  tmforum/                          # TM Forum API integration (orders, catalog, agreements, quotes)
  exception/                        # Custom exception types
  handlers/                         # Event/notification handlers

src/main/resources/
  application.yaml                  # Main config (OID4VP disabled by default)

src/test/java/org/fiware/iam/
  ContractManagementIT.java         # Integration test base
  CentralMarketIT.java             # Central marketplace IT
  InContainerContractManagementIT.java  # Testcontainers-based IT
  InContainerCentralMarketIT.java   # Testcontainers central market IT
  LocalContractManagementIT.java    # Local IT
  TestConfiguration.java            # Test config overrides
  tmforum/TMForumAdapterTest.java   # Unit test for TMForum adapter
  dsp/RainbowAdapterTest.java      # Unit test for Rainbow adapter
  til/TrustedIssuersListAdapterTest.java  # Unit test for TIL adapter

src/test/resources/
  application.yaml                  # Test config (OID4VP enabled, proxy configured)
  oid4vp/credentials/               # Test JWT/SD-JWT credentials
```

## Build & Test
```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run unit tests only
mvn test

# Run all tests including integration
mvn verify

# Run a specific test class
mvn test -Dtest=TMForumAdapterTest
```

## Key Conventions
- Configuration classes use Micronaut `@ConfigurationProperties` with nested records
- Records use `@Introspected` for Micronaut bean introspection
- Nullable fields annotated with `@Nullable` from `io.micronaut.core.annotation`
- Factory pattern (`@Factory` + `@Bean`/`@Singleton`) for complex bean creation
- Conditional beans via `@Requires(bean = ...)` and custom `Condition` implementations
- Lombok `@Data` for mutable configuration classes, Java records for immutable config
- Unit tests use Mockito mocks; integration tests use Testcontainers with k3s

## Important Files
- `pom.xml` — Maven build config, all dependencies
- `src/main/java/org/fiware/iam/configuration/Oid4VpConfiguration.java` — OID4VP config with Holder record
- `src/main/java/org/fiware/iam/bean/Oid4VpBeanFactory.java` — Creates OID4VPClient, HolderConfiguration, SigningService
- `src/main/java/org/fiware/iam/cert/CertReader.java` — Loads PEM private keys and X.509 certs
- `src/main/java/org/fiware/iam/http/Oid4VpAuthHandler.java` — OID4VP authentication handler
- `src/main/resources/application.yaml` — Main application configuration
- `src/test/resources/application.yaml` — Test configuration (OID4VP enabled)
