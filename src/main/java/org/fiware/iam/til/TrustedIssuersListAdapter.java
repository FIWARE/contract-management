package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.exceptions.HttpException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

	private final IssuerApiClient apiClient;
	private final TrustedIssuerConfigProvider trustedIssuerConfigProvider;

	public Mono<?> allowIssuer(String issuerDid) {
		CredentialsVO credentialToBeAdded = trustedIssuerConfigProvider.createCredentialConfigForTargetService();

		return getIssuer(issuerDid)
				.onErrorResume(e -> {
					if (e instanceof HttpClientResponseException hcr && hcr.getStatus() == HttpStatus.NOT_FOUND) {
						log.debug("Requested issuer {} does not exist.", issuerDid);
						return Mono.just(Optional.empty());
					}
					throw new TMForumException("Client error on issuer retrieval.", e);
				})
				.flatMap(optionalIssuer -> {
					if (optionalIssuer.isPresent()) {
						TrustedIssuerVO updatedIssuer = optionalIssuer.get().addCredentialsItem(credentialToBeAdded);
						log.debug("Updating existing issuer with {}", updatedIssuer);
						return apiClient.updateIssuer(issuerDid, updatedIssuer);
					} else {
						TrustedIssuerVO newIssuer = new TrustedIssuerVO().did(issuerDid).addCredentialsItem(credentialToBeAdded);
						log.debug("Adding new issuer with {}", newIssuer);
						return apiClient.createTrustedIssuer(newIssuer);
					}
				})
				.onErrorMap(e -> {
					throw new TMForumException("Was not able to allow the issuer.", e);
				});

	}

	public Mono<?> denyIssuer(String issuerDid) {
		CredentialsVO credentialToBeAdded = trustedIssuerConfigProvider.createCredentialConfigForTargetService();

		return getIssuer(issuerDid)
				.onErrorResume(e -> {
					log.info("Was not able to get the issuer.", e);
					return Mono.just(Optional.empty());
				})
				.flatMap(optionalIssuer -> {
					if (optionalIssuer.isPresent()) {
						TrustedIssuerVO updatedIssuer = optionalIssuer.get().removeCredentialsItem(credentialToBeAdded);
						log.debug("Updating existing issuer with {}", updatedIssuer);
						return apiClient.updateIssuer(issuerDid, updatedIssuer);
					}
					return Mono.just(HttpResponse.noContent());
				})
				.onErrorMap(e -> {
					throw new TMForumException("Was not able to allow the issuer.", e);
				});

	}

	private Mono<Optional<TrustedIssuerVO>> getIssuer(String issuerDid) {
		return apiClient.getIssuer(issuerDid)
				.map(trustedIssuerVOHttpResponse -> {
					if (trustedIssuerVOHttpResponse.code() != HttpStatus.OK.getCode()) {
						log.debug("Could not find issuer {} in Trusted Issuers List. Status {}", issuerDid, trustedIssuerVOHttpResponse.code());
						return Optional.empty();
					}
					return Optional.ofNullable(trustedIssuerVOHttpResponse.body());
				});

	}
}
