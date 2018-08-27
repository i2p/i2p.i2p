package net.i2p.crypto.provider;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.Provider;
import java.security.Security;

/**
 *  @since 0.9.15
 */
public final class I2PProvider extends Provider {
    public static final String PROVIDER_NAME = "I2P";
    private static final String INFO = "I2P Security Provider v0.1, implementing" +
            "several algorithms used by I2P.";
    private static boolean _installed;

    /**
     * Construct a new provider.  This should only be required when
     * using runtime registration of the provider using the
     * <code>Security.addProvider()</code> mechanism.
     */
    public I2PProvider() {
        // following constructor deprecated in Java 9,
        // replaced by (String,String,String) added in Java 9
        super(PROVIDER_NAME, 0.1, INFO);

        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                setup();
                return null;
            }
        });
    }

    private void setup() {
        // TODO: Implement SPIs for existing code
        // However -
        // quote
        // http://docs.oracle.com/javase/7/docs/technotes/guides/security/crypto/HowToImplAProvider.html
        //
        // If your provider is supplying encryption algorithms through the
        // Cipher, KeyAgreement, KeyGenerator, Mac, or SecretKeyFactory classes,
        // you will need to sign your JAR file so that the JCA can authenticate the code at runtime.
        // If you are NOT providing an implementation of this type you can skip this step.
        //
        //put("Cipher.AES", "net.i2p.crypto.provider.CipherSpi$aesCBC");
        //put("Cipher.ElGamal", "net.i2p.crypto.provider.CipherSpi$elGamal");
        //put("Mac.HmacMD5-I2P", "net.i2p.crypto.provider.MacSpi");

        put("MessageDigest.SHA-1", "net.i2p.crypto.SHA1");
        //put("Signature.SHA1withDSA", "net.i2p.crypto.provider.SignatureSpi");

        // EdDSA
        // Key OID: 1.3.101.100; Sig OID: 1.3.101.101
        put("KeyFactory.EdDSA", "net.i2p.crypto.eddsa.KeyFactory");
        put("KeyPairGenerator.EdDSA", "net.i2p.crypto.eddsa.KeyPairGenerator");
        put("Signature.SHA512withEdDSA", "net.i2p.crypto.eddsa.EdDSAEngine");
        // Didn't find much documentation on these at all,
        // see http://docs.oracle.com/javase/7/docs/technotes/guides/security/crypto/HowToImplAProvider.html
        // section "Mapping from OID to name"
        // without these, Certificate.verify() fails
        put("Alg.Alias.KeyFactory.1.3.101.100", "EdDSA");
        put("Alg.Alias.KeyFactory.OID.1.3.101.100", "EdDSA");
        // Without these, keytool fails with:
        // keytool error: java.security.NoSuchAlgorithmException: unrecognized algorithm name: SHA512withEdDSA
        put("Alg.Alias.KeyPairGenerator.1.3.101.100", "EdDSA");
        put("Alg.Alias.KeyPairGenerator.OID.1.3.101.100", "EdDSA");
        // with this setting, keytool keygen doesn't work
        // java.security.cert.CertificateException: Signature algorithm mismatch
        // it must match the key setting (1.3.101.100) to work
        // but this works fine with programmatic cert generation
        put("Alg.Alias.Signature.1.3.101.101", "SHA512withEdDSA");
        put("Alg.Alias.Signature.OID.1.3.101.101", "SHA512withEdDSA");
        // TODO Ed25519ph
        // OID: 1.3.101.101

        // ElGamal
        // OID: 1.3.14.7.2.1.1
        put("KeyFactory.DH", "net.i2p.crypto.elgamal.KeyFactory");
        put("KeyFactory.DiffieHellman", "net.i2p.crypto.elgamal.KeyFactory");
        put("KeyFactory.ElGamal", "net.i2p.crypto.elgamal.KeyFactory");
        put("KeyPairGenerator.DH", "net.i2p.crypto.elgamal.KeyPairGenerator");
        put("KeyPairGenerator.DiffieHellman", "net.i2p.crypto.elgamal.KeyPairGenerator");
        put("KeyPairGenerator.ElGamal", "net.i2p.crypto.elgamal.KeyPairGenerator");
        put("Signature.SHA256withElGamal", "net.i2p.crypto.elgamal.ElGamalSigEngine");
        put("Alg.Alias.KeyFactory.1.3.14.7.2.1.1", "ElGamal");
        put("Alg.Alias.KeyFactory.OID.1.3.14.7.2.1.1", "ElGamal");
        put("Alg.Alias.KeyPairGenerator.1.3.14.7.2.1.1", "ElGamal");
        put("Alg.Alias.KeyPairGenerator.OID.1.3.14.7.2.1.1", "ElGamal");
        put("Alg.Alias.Signature.1.3.14.7.2.1.1", "SHA256withElGamal");
        put("Alg.Alias.Signature.OID.1.3.14.7.2.1.1", "SHA256withElGamal");
    }

    /**
     *  Install the I2PProvider.
     *  Harmless to call multiple times.
     *  @since 0.9.25
     */
    public static void addProvider() {
        synchronized(I2PProvider.class) {
            if (!_installed) {
                try {
                    Provider us = new I2PProvider();
                    // put ours ahead of BC, if installed, because our ElGamal
                    // implementation may not be fully compatible with BC
                    Provider[] provs = Security.getProviders();
                    for (int i = 0; i < provs.length; i++) {
                        if (provs[i].getName().equals("BC")) {
                            Security.insertProviderAt(us, i);
                            _installed = true;
                            return;
                        }
                    }
                    Security.addProvider(us);
                    _installed = true;
                } catch (SecurityException se) {
                    System.out.println("WARN: Could not install I2P provider: " + se);
                }
            }
        }
    }
}
