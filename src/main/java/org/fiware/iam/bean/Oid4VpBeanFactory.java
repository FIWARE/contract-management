package org.fiware.iam.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.nimbusds.jose.JWEAlgorithm;
import io.github.wistefan.dcql.DCQLEvaluator;
import io.github.wistefan.dcql.DcSdJwtCredentialEvaluator;
import io.github.wistefan.dcql.JwtCredentialEvaluator;
import io.github.wistefan.dcql.VcSdJwtCredentialEvaluator;
import io.github.wistefan.dcql.model.CredentialFormat;
import io.github.wistefan.dcql.model.TrustedAuthorityType;
import io.github.wistefan.oid4vp.HolderSigningService;
import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.SigningService;
import io.github.wistefan.oid4vp.client.X509SanDnsClientResolver;
import io.github.wistefan.oid4vp.config.HolderConfiguration;
import io.github.wistefan.oid4vp.credentials.CredentialsRepository;
import io.github.wistefan.oid4vp.credentials.FileSystemCredentialsRepository;
import io.github.wistefan.oid4vp.mapping.CredentialFormatDeserializer;
import io.github.wistefan.oid4vp.mapping.TrustedAuthorityTypeDeserializer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.fiware.iam.cert.CertReader;
import org.fiware.iam.configuration.Oid4VpConfiguration;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Factory
public class Oid4VpBeanFactory {

    private final CertReader certReader;

    public Oid4VpBeanFactory(CertReader certReader) {
        this.certReader = certReader;
    }

    @Requires(bean = Oid4VpConfiguration.class)
    @Singleton
    public HttpClient httpClient(Oid4VpConfiguration.ProxyConfig proxyConfig) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        httpClientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyConfig.useProxy()) {
            ProxySelector proxySelector = ProxySelector.of(new InetSocketAddress(proxyConfig.proxyHost(), proxyConfig.proxyPort()));
            httpClientBuilder.proxy(proxySelector);
        }
        return httpClientBuilder.build();
    }

    @Requires(bean = Oid4VpConfiguration.class)
    @Bean
    public CredentialsRepository credentialsRepository(Oid4VpConfiguration oid4VpConfiguration, ObjectMapper objectMapper) {
        return new FileSystemCredentialsRepository(oid4VpConfiguration.getCredentialsFolder(), objectMapper);
    }

    @Requires(bean = Oid4VpConfiguration.class)
    @Bean
    public OID4VPClient oid4VPClient(HttpClient httpClient, ObjectMapper objectMapper, Oid4VpConfiguration oid4VpConfiguration, CredentialsRepository credentialsRepository) {
        Security.addProvider(new BouncyCastleProvider());

        ObjectMapper authObjectMapper = objectMapper.copy();
        authObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        SimpleModule deserializerModule = new SimpleModule();
        deserializerModule.addDeserializer(CredentialFormat.class, new CredentialFormatDeserializer());
        deserializerModule.addDeserializer(TrustedAuthorityType.class, new TrustedAuthorityTypeDeserializer());
        authObjectMapper.registerModule(deserializerModule);

        PrivateKey privateKey = certReader.loadPrivateKey(oid4VpConfiguration.getHolder().keyPath());
        HolderConfiguration holderConfiguration = new HolderConfiguration(
                oid4VpConfiguration.getHolder().holderId(),
                oid4VpConfiguration.getHolder().holderId().toString(),
                JWEAlgorithm.parse(oid4VpConfiguration.getHolder().signatureAlgorithm()),
                privateKey);
        SigningService signingService = new HolderSigningService(holderConfiguration, objectMapper);

        Set<TrustAnchor> trustAnchors = oid4VpConfiguration.getTrustAnchors()
                .stream()
                .map(certReader::loadCertificates)
                .flatMap(List::stream)
                .map(c -> new TrustAnchor(c, null))
                .collect(Collectors.toSet());

        X509SanDnsClientResolver clientResolver = new X509SanDnsClientResolver();
        if (!trustAnchors.isEmpty()) {
            clientResolver = new X509SanDnsClientResolver(trustAnchors, false);
        }

        DCQLEvaluator dcqlEvaluator = new DCQLEvaluator(List.of(
                new JwtCredentialEvaluator(),
                new DcSdJwtCredentialEvaluator(),
                new VcSdJwtCredentialEvaluator()));

        return new OID4VPClient(
                httpClient,
                holderConfiguration,
                authObjectMapper,
                List.of(clientResolver),
                dcqlEvaluator,
                credentialsRepository,
                signingService);
    }
}