package org.fiware.iam.cert;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import jakarta.inject.Singleton;
import java.util.List;

@Singleton
public class CertReader {

    public PrivateKey loadPrivateKey(String filename) {
        try (InputStream is = openInputStream(filename)) {
            if (is == null) {
                throw new IllegalArgumentException("Private key not found: " + filename);
            }
            PEMParser parser = new PEMParser(new InputStreamReader(is, StandardCharsets.UTF_8));
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair pemKeyPair) {
                return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo privateKeyInfo) {
                return converter.getPrivateKey(privateKeyInfo);
            }
            throw new IllegalArgumentException("Unsupported key format in: " + filename);
        } catch (IOException e) {
            throw new IllegalArgumentException("Was not able to load the private key from " + filename, e);
        }
    }

    public List<X509Certificate> loadCertificates(String resource) {
        try (InputStream is = openInputStream(resource)) {
            if (is == null) {
                throw new IllegalArgumentException("Certificate file not found: " + resource);
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return cf.generateCertificates(is).stream()
                    .map(X509Certificate.class::cast)
                    .toList();
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException(String.format("Was not able to load the certificates from %s", resource), e);
        }
    }

    private InputStream openInputStream(String filepath) throws IOException {
        Path path = Paths.get(filepath);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return Files.newInputStream(path);
    }

}
