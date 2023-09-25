package org.fiware.iam.til;

import io.micronaut.http.HttpResponse;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.iam.til.api.IssuerApiClient;
import org.fiware.iam.til.model.ClaimVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.fiware.iam.til.model.TrustedIssuerVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@MicronautTest(packages = {"org.fiware.iam.til"})
class TrustedIssuersListAdapterTest {

    private IssuerApiClient apiClient = mock(IssuerApiClient.class);

    @MockBean(IssuerApiClient.class)
    public IssuerApiClient apiClient() {
        return apiClient;
    }

    @Inject
    private TrustedIssuersListAdapter classUnderTest;

    @Test
    void allowIssuer_create() {
        when(apiClient.getIssuer(anyString())).thenReturn(HttpResponse.notFound());
        classUnderTest.allowIssuer("testDID");
        verify(apiClient).createTrustedIssuer(any());
    }
    @Test
    void allowIssuer_update() {
        when(apiClient.getIssuer(anyString()))
                .thenReturn(HttpResponse.ok(new TrustedIssuerVO()
                        .did("testDID")
                        .addCredentialsItem(new CredentialsVO()
                                .credentialsType("existingCredentialType")
                                .addClaimsItem(new ClaimVO().name("target1").addAllowedValuesItem("Role1")))));
        classUnderTest.allowIssuer("testDID");
        verify(apiClient).updateIssuer(eq("testDID"),any());
    }
}