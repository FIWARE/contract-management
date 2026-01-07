package org.fiware.iam;

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
import io.micronaut.runtime.Micronaut;
import jakarta.inject.Singleton;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.fiware.iam.configuration.Oid4VpConfiguration;
import org.fiware.iam.exception.Oid4VpInitException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.stream.Collectors;

@Factory
public class Application {

    private static final String CACERTS_PATH = System.getProperty("javax.net.ssl.trustStore",
            System.getProperty("java.home") + "/lib/security/cacerts");
    private static final char[] DEFAULT_TRUSTSTORE_PASSWORD = System.getProperty(
            "javax.net.ssl.trustStorePassword", "changeit").toCharArray();

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }

    @Requires(bean = Oid4VpConfiguration.class)
    @Singleton
    public HttpClient httpClient(Oid4VpConfiguration.ProxyConfig proxyConfig) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        // required for the authorization flow to work
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
        // required for octect-key support
        Security.addProvider(new BouncyCastleProvider());

        // properly deserialize dcql
        ObjectMapper authObjectMapper = objectMapper.copy();
        authObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        SimpleModule deserializerModule = new SimpleModule();
        deserializerModule.addDeserializer(CredentialFormat.class, new CredentialFormatDeserializer());
        deserializerModule.addDeserializer(TrustedAuthorityType.class, new TrustedAuthorityTypeDeserializer());
        authObjectMapper.registerModule(deserializerModule);

        // initialize the holder
        PrivateKey privateKey = loadPrivateKey(oid4VpConfiguration.getHolder().keyType(), oid4VpConfiguration.getHolder().keyPath());
        HolderConfiguration holderConfiguration = new HolderConfiguration(
                oid4VpConfiguration.getHolder().holderId(),
                oid4VpConfiguration.getHolder().holderId().toString(),
                JWEAlgorithm.parse(oid4VpConfiguration.getHolder().signatureAlgorithm()),
                privateKey);
        SigningService signingService = new HolderSigningService(holderConfiguration, objectMapper);

        Set<TrustAnchor> trustAnchors = oid4VpConfiguration.getTrustAnchors()
                .stream()
                .map(Application::loadCertificates)
                .flatMap(List::stream)
                .map(c -> new TrustAnchor(c, null))
                .collect(Collectors.toSet());

        X509SanDnsClientResolver clientResolver = new X509SanDnsClientResolver();
        if (!trustAnchors.isEmpty()) {
            //if trust anchors are explicitly configured, use them.
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

    private static PrivateKey loadPrivateKey(String keyType, String filename) {
        try (InputStream is = Application.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + filename);
            }

            // Read PEM file content
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");

            // Base64 decode
            byte[] decoded = Base64.getDecoder().decode(pem);

            // Build key spec
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance(keyType); // or "EC"
            return keyFactory.generatePrivate(keySpec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new Oid4VpInitException(String.format("Was not able to load the private key with type %s from %s", keyType, filename), e);
        }
    }

    private static List<X509Certificate> loadCertificates(String resource) {

        try (InputStream is = Application.class.getClassLoader().getResourceAsStream(resource)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(is);

            List<X509Certificate> list = new ArrayList<>();
            for (Certificate cert : certs) {
                list.add((X509Certificate) cert);
            }
            return list;
        } catch (IOException | CertificateException e) {
            throw new Oid4VpInitException(String.format("Was not able to load the certificates from %s", resource), e);
        }
    }


}
