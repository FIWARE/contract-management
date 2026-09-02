package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.domain.ContractManagement;
import org.fiware.iam.exception.TrustedIssuersException;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.CredentialsVOTestExample;
import org.fiware.iam.tmforum.CredentialsConfigResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a grant and its revocation are attributed to the order that made them, so that one
 * order's revocation cannot remove what another order still requires.
 */
@MicronautTest(packages = { "org.fiware.iam" })
class TrustedIssuersListAdapterTest {

    private static final String ISSUER_DID = "testDID";
    private static final String ORDER_ID = "urn:ngsi-ld:product-order:first";
    private static final String REMOTE_ADDRESS = "https://remote.org";
    private static final String REMOTE_CLIENT_ID = "remote-client";

    private IssuerApiClient apiClient = mock(IssuerApiClient.class);

    @MockBean(IssuerApiClient.class)
    public IssuerApiClient apiClient() {
        return apiClient;
    }

    @Inject
    private TrustedIssuersListAdapter classUnderTest;

    @Test
    void allowIssuer_grantsUnderTheOrdersScope() {
        CredentialsVO testCVO = CredentialsVOTestExample.build();
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any()))
                .thenReturn(Mono.just(HttpResponse.ok()));

        Boolean success = classUnderTest.allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(testCVO))).block();

        Assertions.assertEquals(true, success, "The grant should have succeeded.");
        verify(apiClient).replaceCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID), eq(List.of(testCVO)));
    }

    @Test
    void allowIssuer_withoutLocalCredentialsGrantsNothing() {
        Boolean success = classUnderTest
                .allowIssuer(ISSUER_DID, ORDER_ID, List.of(remoteConfig(CredentialsVOTestExample.build())))
                .block();

        Assertions.assertEquals(true, success, "An order with nothing to grant locally is not a failure.");
        verify(apiClient, never()).replaceCredentialsByScope(anyString(), anyString(), any());
    }

    @Test
    void allowIssuer_deduplicatesIdenticalCredentials() {
        CredentialsVO testCVO = CredentialsVOTestExample.build();
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any()))
                .thenReturn(Mono.just(HttpResponse.ok()));

        // the same credential configured by two parts of one composed product
        classUnderTest.allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(testCVO), localConfig(testCVO))).block();

        verify(apiClient).replaceCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID), eq(List.of(testCVO)));
    }

    @Test
    void allowIssuer_reportsAFailedGrant() {
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any()))
                .thenReturn(Mono.just(HttpResponse.status(HttpStatus.BAD_GATEWAY)));

        Boolean success = classUnderTest
                .allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(CredentialsVOTestExample.build())))
                .block();

        Assertions.assertEquals(false, success, "A rejected grant should be reported as such.");
    }

    @Test
    void allowIssuer_fault() {
        when(apiClient.replaceCredentialsByScope(anyString(), anyString(), any()))
                .thenThrow(new HttpClientException("test"));

        Assertions.assertThrows(TrustedIssuersException.class,
                () -> classUnderTest
                        .allowIssuer(ISSUER_DID, ORDER_ID, List.of(localConfig(CredentialsVOTestExample.build())))
                        .block());
    }

    @Test
    void denyIssuer_revokesTheOrdersScopeWithoutResolvingItsConfiguration() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString()))
                .thenReturn(Mono.just(HttpResponse.noContent()));

        HttpResponse<?> response = classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block();

        Assertions.assertEquals(HttpStatus.NO_CONTENT, response.getStatus(), "The revocation should succeed.");
        verify(apiClient).deleteCredentialsByScope(eq(ISSUER_DID), eq(ORDER_ID));
    }

    @Test
    void denyIssuer_unknownIssuerIsNotAFailure() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString()))
                .thenReturn(Mono.error(new HttpClientResponseException("not found", HttpResponse.notFound())));

        HttpResponse<?> response = classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block();

        Assertions.assertEquals(HttpStatus.NO_CONTENT, response.getStatus(),
                "Revoking from an issuer that was never granted anything is a no-op, not an error.");
    }

    @Test
    void denyIssuer_fault() {
        when(apiClient.deleteCredentialsByScope(anyString(), anyString()))
                .thenReturn(Mono.error(new HttpClientException("test")));

        Assertions.assertThrows(TrustedIssuersException.class,
                () -> classUnderTest.denyIssuer(ISSUER_DID, ORDER_ID).block());
    }

    private static CredentialsConfigResolver.CredentialConfig localConfig(CredentialsVO credentialsVO) {
        return new CredentialsConfigResolver.CredentialConfig(new ContractManagement(true), List.of(credentialsVO));
    }

    private static CredentialsConfigResolver.CredentialConfig remoteConfig(CredentialsVO credentialsVO) {
        return new CredentialsConfigResolver.CredentialConfig(
                new ContractManagement(false, REMOTE_ADDRESS, REMOTE_CLIENT_ID, Set.of("scope")),
                List.of(credentialsVO));
    }
}
