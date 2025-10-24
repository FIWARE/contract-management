package org.fiware.iam.til;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.exceptions.HttpException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.exception.TMForumException;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import reactor.core.publisher.Mono;

import java.util.*;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class TrustedIssuersListAdapter {

    private final IssuerApiClient apiClient;

    public Mono<Boolean> allowIssuer(String issuerDid, List<CredentialsConfigResolver.CredentialConfig> credentialsConfig) {

        List<CredentialsVO> credentialsVOS = filterLocalCredentialsVO(credentialsConfig);
        if (credentialsVOS.isEmpty()) {
            // nothing to do, if no local cm is configured
            return Mono.just(true);
        }

        return getIssuer(issuerDid)
                .onErrorResume(e -> {
                    if (e instanceof HttpClientResponseException hcr && hcr.getStatus() == HttpStatus.NOT_FOUND) {
                        log.debug("Requested issuer {} does not exist.", issuerDid);
                        return Mono.just(Optional.empty());
                    }
                    throw new TrustedIssuersException("Client error on issuer retrieval.", e);
                })
                .flatMap(optionalIssuer -> {
                    if (optionalIssuer.isPresent()) {
                        TrustedIssuerVO trustedIssuerVO = optionalIssuer.get();
                        Set<CredentialsVO> credentialsVOSet = new HashSet<>(trustedIssuerVO.getCredentials());
                        credentialsVOSet.addAll(credentialsVOS);
                        trustedIssuerVO.setCredentials(new ArrayList<>(credentialsVOSet));
                        try {
                            log.debug("Updating existing issuer with {}", new ObjectMapper().writeValueAsString(trustedIssuerVO));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                        return apiClient.updateIssuer(issuerDid, trustedIssuerVO)
                                .map(response -> {
                                    if (response.getStatus() == HttpStatus.OK) {
                                        return true;
                                    }
                                    return false;
                                });
                    } else {
                        //remove duplicates
                        Set<CredentialsVO> credentialsVOSet = new HashSet<>(credentialsVOS);
                        TrustedIssuerVO newIssuer = new TrustedIssuerVO().did(issuerDid).credentials(new ArrayList<>(credentialsVOSet));
                        log.debug("Adding new issuer with {}", newIssuer);
                        return apiClient.createTrustedIssuer(newIssuer).map(response -> {
                            if (response.getStatus() == HttpStatus.OK) {
                                return true;
                            }
                            return false;
                        });
                    }
                })
                .onErrorMap(e -> {
                    log.warn("Failed to allow.", e);
                    throw new TrustedIssuersException("Was not able to allow the issuer.", e);
                });
    }

    public Mono<HttpResponse<?>> denyIssuer(String issuerDid, List<CredentialsConfigResolver.CredentialConfig> credentialsConfig) {

        List<CredentialsVO> credentialsVOS = filterLocalCredentialsVO(credentialsConfig);
        if (credentialsVOS.isEmpty()) {
            // nothing to do, if no local cm is configured
            return Mono.just(HttpResponse.noContent());
        }
        return getIssuer(issuerDid)
                .onErrorResume(e -> {
                    log.info("Was not able to get the issuer.", e);
                    return Mono.just(Optional.empty());
                })
                .flatMap(optionalIssuer -> {
                    if (optionalIssuer.isPresent()) {
                        TrustedIssuerVO updatedIssuer = optionalIssuer.get();
                        credentialsVOS.forEach(updatedIssuer::removeCredentialsItem);
                        try {
                            log.debug("Updating existing issuer with {}", new ObjectMapper().writeValueAsString(updatedIssuer));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                        return apiClient.updateIssuer(issuerDid, updatedIssuer);
                    }
                    return Mono.just(HttpResponse.noContent());
                })
                .onErrorMap(e -> {
                    throw new TrustedIssuersException("Was not able to deny the issuer.", e);
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

    // only return credentials intended for local
    private List<CredentialsVO> filterLocalCredentialsVO(List<CredentialsConfigResolver.CredentialConfig> credentialsConfig) {
        return credentialsConfig.stream()
                .filter(credentialConfig -> credentialConfig.contractManagement().isLocal())
                .map(CredentialsConfigResolver.CredentialConfig::credentialsVOS)
                .flatMap(List::stream)
                .toList();
    }
}
