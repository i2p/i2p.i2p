package net.i2p.crypto.provider;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.Provider;

/**
 *  @since 0.9.15
 */
public final class I2PProvider extends Provider {
    public static final String PROVIDER_NAME = "I2P";
    private static final String INFO = "I2P Security Provider v0.1, implementing" +
            "several algorithms used by I2P.";

    /**
     * Construct a new provider.  This should only be required when
     * using runtime registration of the provider using the
     * <code>Security.addProvider()</code> mechanism.
     */
    public I2PProvider() {
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
        //put("Cipher.AES", "net.i2p.crypto.provider.CipherSpi$aesCBC");
        //put("Cipher.ElGamal", "net.i2p.crypto.provider.CipherSpi$elGamal");
        //put("Mac.HmacMD5-I2P", "net.i2p.crypto.provider.MacSpi");
        put("MessageDigest.SHA-1", "net.i2p.crypto.SHA1");
        //put("Signature.SHA1withDSA", "net.i2p.crypto.provider.SignatureSpi");

        // EdDSA
        put("KeyFactory.EdDSA", "net.i2p.crypto.eddsa.KeyFactory");
        put("KeyPairGenerator.EdDSA", "net.i2p.crypto.eddsa.KeyPairGenerator");
        put("Signature.SHA512withEdDSA", "net.i2p.crypto.eddsa.EdDSAEngine");
        // Didn't find much documentation on these at all,
        // see http://docs.oracle.com/javase/7/docs/technotes/guides/security/crypto/HowToImplAProvider.html
        // section "Mapping from OID to name"
        // Without these, keytool fails with:
        // keytool error: java.security.NoSuchAlgorithmException: unrecognized algorithm name: SHA512withEdDSA
        put("Alg.Alias.KeyPairGenerator.1.3.101.100", "EdDSA");
        put("Alg.Alias.KeyPairGenerator.OID.1.3.101.100", "EdDSA");
        put("Alg.Alias.Signature.1.3.101.100", "SHA512withEdDSA");
        put("Alg.Alias.Signature.OID.1.3.101.100", "SHA512withEdDSA");
    }
}
