package com.company.loopengine.node.enrollment;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.springframework.stereotype.Component;

@Component
public class DeviceCertificateAuthority {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PrivateKey caPrivateKey;
    private final X509Certificate caCertificate;
    private final Clock clock;
    private final boolean failing;

    public DeviceCertificateAuthority() {
        this(loadFromEnv(), Clock.systemUTC(), false);
    }

    private DeviceCertificateAuthority(LoadedCa loaded, Clock clock, boolean failing) {
        this.caPrivateKey = loaded == null ? null : loaded.privateKey();
        this.caCertificate = loaded == null ? null : loaded.certificate();
        this.clock = clock;
        this.failing = failing;
    }

    public static DeviceCertificateAuthority ephemeralForTests(Clock clock) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", "BC");
            KeyPair keyPair = generator.generateKeyPair();
            X509Certificate certificate = selfSignCa(keyPair, clock);
            return new DeviceCertificateAuthority(
                new LoadedCa(keyPair.getPrivate(), certificate), clock, false);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create ephemeral device CA", ex);
        }
    }

    public static DeviceCertificateAuthority failingForTests() {
        return new DeviceCertificateAuthority(null, Clock.systemUTC(), true);
    }

    public SignedDeviceCertificate signDeviceCsr(UUIDSubject subject, String csrPem) {
        if (failing) {
            throw new CertificateSigningException("device certificate signing failed");
        }
        Objects.requireNonNull(caPrivateKey, "device CA private key is not configured");
        try {
            PKCS10CertificationRequest csr = parseCsr(csrPem);
            JcaPKCS10CertificationRequest jcaCsr = new JcaPKCS10CertificationRequest(csr);
            PublicKey devicePublicKey = jcaCsr.getPublicKey();
            if (!"EdDSA".equalsIgnoreCase(devicePublicKey.getAlgorithm())
                && !"Ed25519".equalsIgnoreCase(devicePublicKey.getAlgorithm())) {
                throw new IllegalArgumentException("CSR public key must be Ed25519");
            }

            Instant notBefore = clock.instant();
            Instant notAfter = notBefore.plus(90, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());
            X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
            X500Name x500 = new X500Name("CN=repair-node:" + subject.nodeId());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                serial,
                Date.from(notBefore),
                Date.from(notAfter),
                x500,
                devicePublicKey);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                    org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));

            ContentSigner signer = new JcaContentSignerBuilder("Ed25519").setProvider("BC")
                .build(caPrivateKey);
            X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
            return new SignedDeviceCertificate(
                toPem(certificate),
                toPem(caCertificate),
                serial.toString(16),
                devicePublicKey,
                notAfter);
        } catch (CertificateSigningException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CertificateSigningException("device certificate signing failed", ex);
        }
    }

    public String caPem() {
        Objects.requireNonNull(caCertificate, "device CA is not configured");
        try {
            return toPem(caCertificate);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to encode CA certificate", ex);
        }
    }

    private static PKCS10CertificationRequest parseCsr(String csrPem) throws IOException {
        try (PEMParser parser = new PEMParser(new java.io.StringReader(csrPem))) {
            Object parsed = parser.readObject();
            if (parsed instanceof PKCS10CertificationRequest csr) {
                return csr;
            }
            throw new IllegalArgumentException("PEM is not a PKCS#10 certification request");
        }
    }

    private static LoadedCa loadFromEnv() {
        String pathValue = System.getenv("LOOP_DEVICE_CA_KEY_PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        return loadFromPath(Path.of(pathValue));
    }

    private static LoadedCa loadFromPath(Path path) {
        try {
            String pem = Files.readString(path);
            PrivateKey privateKey = null;
            X509Certificate certificate = null;
            try (PEMParser parser = new PEMParser(new java.io.StringReader(pem))) {
                Object object;
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                while ((object = parser.readObject()) != null) {
                    if (object instanceof PEMKeyPair keyPair) {
                        privateKey = converter.getKeyPair(keyPair).getPrivate();
                    } else if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) {
                        privateKey = converter.getPrivateKey(info);
                    } else if (object instanceof X509CertificateHolder holder) {
                        certificate = new JcaX509CertificateConverter()
                            .setProvider("BC")
                            .getCertificate(holder);
                    }
                }
            }
            if (privateKey == null) {
                throw new IllegalStateException(
                    "LOOP_DEVICE_CA_KEY_PATH does not contain a private key: " + path);
            }
            if (certificate == null) {
                throw new IllegalStateException(
                    "LOOP_DEVICE_CA_KEY_PATH must also contain the CA certificate PEM");
            }
            return new LoadedCa(privateKey, certificate);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load device CA from " + path, ex);
        }
    }

    private static X509Certificate selfSignCa(KeyPair keyPair, Clock clock) throws Exception {
        Instant notBefore = clock.instant().minus(1, ChronoUnit.DAYS);
        Instant notAfter = clock.instant().plus(3650, ChronoUnit.DAYS);
        X500Name name = new X500Name("CN=Loop Engine Device CA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date.from(notBefore),
            Date.from(notAfter),
            name,
            keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(
            Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").setProvider("BC")
            .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(builder.build(signer));
    }

    private static String toPem(Object object) throws IOException {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(object);
        }
        return writer.toString();
    }

    public record UUIDSubject(java.util.UUID nodeId) {}

    public record SignedDeviceCertificate(
        String certificatePem,
        String caPem,
        String serialHex,
        PublicKey publicKey,
        Instant expiresAt) {}

    private record LoadedCa(PrivateKey privateKey, X509Certificate certificate) {}
}

final class CertificateSigningException extends RuntimeException {
    CertificateSigningException(String message) {
        super(message);
    }

    CertificateSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
