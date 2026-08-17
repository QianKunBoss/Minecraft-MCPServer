package org.du.mcpserver.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

public class SSLCertificateGenerator {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");
    private static final String KEYSTORE_PASSWORD = "mcpserver";
    private static final String ALIAS = "mcpserver";
    private static final int KEY_SIZE = 2048;
    private static final int VALIDITY_DAYS = 3650;

    public static boolean generateSelfSignedCertificate(Path keystorePath) {
        try {
            File keystoreFile = keystorePath.toFile();
            if (keystoreFile.exists()) {
                LOGGER.info("SSL keystore already exists: {}", keystorePath);
                return true;
            }

            keystoreFile.getParentFile().mkdirs();

            LOGGER.info("Generating self-signed SSL certificate...");

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(KEY_SIZE);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            PrivateKey privateKey = keyPair.getPrivate();
            PublicKey publicKey = keyPair.getPublic();

            X509Certificate cert = generateCertificate(privateKey, publicKey);

            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            keyStore.setKeyEntry(ALIAS, privateKey, KEYSTORE_PASSWORD.toCharArray(),
                    new Certificate[]{cert});

            try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
                keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
            }

            LOGGER.info("SSL certificate generated successfully: {}", keystorePath);
            LOGGER.info("  Subject: CN=localhost, OU=MCPServer, O=MCPServer");
            LOGGER.info("  Validity: {} days (until {})", VALIDITY_DAYS, cert.getNotAfter());
            LOGGER.info("  Keystore password: {}", KEYSTORE_PASSWORD);

            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to generate SSL certificate: {}", e.getMessage());
            LOGGER.debug("Certificate generation error:", e);
            return false;
        }
    }

    private static X509Certificate generateCertificate(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        X500Principal subject = new X500Principal("CN=localhost, OU=MCPServer, O=MCPServer, L=Local, ST=Local, C=CN");
        Date startDate = new Date();
        Date endDate = new Date(startDate.getTime() + (long) VALIDITY_DAYS * 24 * 60 * 60 * 1000);
        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());

        return generateCertificateUsingBouncyCastle(subject, privateKey, publicKey, startDate, endDate, serialNumber);
    }

    private static X509Certificate generateCertificateUsingBouncyCastle(X500Principal subject,
                                                                        PrivateKey privateKey,
                                                                        PublicKey publicKey,
                                                                        Date startDate, Date endDate,
                                                                        BigInteger serialNumber) throws Exception {
        LOGGER.debug("Using BouncyCastle to generate certificate...");

        Class<?> x500NameClass = Class.forName("org.bouncycastle.asn1.x500.X500Name");
        Object x500Name = x500NameClass.getConstructor(String.class).newInstance(subject.getName());

        Class<?> subjectPublicKeyInfoClass = Class.forName("org.bouncycastle.asn1.x509.SubjectPublicKeyInfo");
        java.lang.reflect.Method getInstanceMethod = subjectPublicKeyInfoClass.getMethod("getInstance", byte[].class);
        Object subjectPublicKeyInfo = getInstanceMethod.invoke(null, publicKey.getEncoded());

        Class<?> builderClass = Class.forName("org.bouncycastle.cert.X509v3CertificateBuilder");
        java.lang.reflect.Constructor<?> builderConstructor = builderClass.getConstructor(
                x500NameClass, BigInteger.class, Date.class, Date.class,
                x500NameClass, subjectPublicKeyInfoClass
        );
        Object builder = builderConstructor.newInstance(x500Name, serialNumber, startDate, endDate, x500Name, subjectPublicKeyInfo);

        Class<?> jcaContentSignerBuilderClass = Class.forName("org.bouncycastle.operator.jcajce.JcaContentSignerBuilder");
        Object signerBuilder = jcaContentSignerBuilderClass.getConstructor(String.class).newInstance("SHA256WithRSAEncryption");
        java.lang.reflect.Method buildSignerMethod = signerBuilder.getClass().getMethod("build", PrivateKey.class);
        Object contentSigner = buildSignerMethod.invoke(signerBuilder, privateKey);

        java.lang.reflect.Method buildMethod = builderClass.getMethod("build", Class.forName("org.bouncycastle.operator.ContentSigner"));
        Object certHolder = buildMethod.invoke(builder, contentSigner);

        Class<?> jcaConverterClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509CertificateConverter");
        Object converter = jcaConverterClass.newInstance();
        java.lang.reflect.Method getCertificateMethod = converter.getClass().getMethod("getCertificate", Class.forName("org.bouncycastle.cert.X509CertificateHolder"));

        return (X509Certificate) getCertificateMethod.invoke(converter, certHolder);
    }

    public static String getKeystorePassword() {
        return KEYSTORE_PASSWORD;
    }

    public static String getAlias() {
        return ALIAS;
    }
}