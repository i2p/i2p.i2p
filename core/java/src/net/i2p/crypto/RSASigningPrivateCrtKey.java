package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAKeyGenParameterSpec;

import net.i2p.data.SigningPrivateKey;

/**
 * A SigningPrivateKey that retains the Chinese Remainder Theorem
 * parameters, so it can be converted back to a Java CRT key.
 *
 * By preserving the CRT parameters across a Java to I2P to Java
 * conversion, we speed up signing by about 3.3x, and
 * the parameters are correctly serialized in PEM format
 * when stored to a keystore.
 *
 * The CRT parameters are NOT retained when this object is
 * serialized via getData().
 *
 * @since 0.9.31
 */
final class RSASigningPrivateCrtKey extends SigningPrivateKey {

    private final RSAPrivateCrtKey _crt;

    /**
     * @throws IllegalArgumentException if data is not correct length
     */
    public static RSASigningPrivateCrtKey fromJavaKey(RSAPrivateCrtKey pk) throws GeneralSecurityException {
        int sz = pk.getModulus().bitLength();
        SigType type;
        if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA256_2048.getParams()).getKeysize())
            type = SigType.RSA_SHA256_2048;
        else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA384_3072.getParams()).getKeysize())
            type = SigType.RSA_SHA384_3072;
        else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA512_4096.getParams()).getKeysize())
            type = SigType.RSA_SHA512_4096;
        else
            throw new GeneralSecurityException("Unknown RSA type");
        // private key is modulus (pubkey) + exponent
        BigInteger n = pk.getModulus();
        BigInteger d = pk.getPrivateExponent();
        byte[] b = SigUtil.combine(n, d, type.getPrivkeyLen());
        return new RSASigningPrivateCrtKey(pk, type, b);
    }

    private RSASigningPrivateCrtKey(RSAPrivateCrtKey pk, SigType type, byte[] data) {
        super(type, data);
        _crt = pk;
    }

    public RSAPrivateCrtKey toJavaKey() {
        return _crt;
    }
}
