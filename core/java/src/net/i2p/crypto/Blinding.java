package net.i2p.crypto;

import java.security.GeneralSecurityException;

import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;


/**
 * Utilities for Blinding EdDSA keys.
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public final class Blinding {

    private static final SigType TYPE = SigType.EdDSA_SHA512_Ed25519;

    private Blinding() {}

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPublicKey blind(SigningPublicKey key, SimpleDataStructure h) {
        if (key.getType() != TYPE)
            throw new UnsupportedOperationException();
        if (h.length() != key.length())
            throw new IllegalArgumentException();
        try {
            EdDSAPublicKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPublicKey bjk = EdDSABlinding.blind(jk, h.getData());
            return SigUtil.fromJavaKey(bjk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPrivateKey blind(SigningPrivateKey key, SimpleDataStructure h) {
        if (key.getType() != TYPE)
            throw new UnsupportedOperationException();
        if (h.length() != key.length())
            throw new IllegalArgumentException();
        try {
            EdDSAPrivateKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey bjk = EdDSABlinding.blind(jk, h.getData());
            return SigUtil.fromJavaKey(bjk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPrivateKey unblind(SigningPrivateKey key, SimpleDataStructure h) {
        if (key.getType() != TYPE)
            throw new UnsupportedOperationException();
        if (h.length() != key.length())
            throw new IllegalArgumentException();
        try {
            EdDSAPrivateKey bjk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey jk = EdDSABlinding.unblind(bjk, h.getData());
            return SigUtil.fromJavaKey(jk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }
/******
    public static void main(String args[]) throws Exception {
        SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(TYPE);
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        byte[] b = new byte[32];
        net.i2p.I2PAppContext.getGlobalContext().random().nextBytes(b);
        Hash h = new Hash(b);
        SigningPublicKey bpub = blind(pub, h);
        SigningPrivateKey bpriv = blind(priv, h);
        SigningPublicKey bpub2 = bpriv.toPublic();
        boolean ok = bpub2.equals(bpub);
        System.out.println("Blinding test passed?   " + ok);
        SigningPrivateKey priv2 = unblind(bpriv, h);
        ok = priv2.equals(priv);
        System.out.println("Unblinding test passed? " + ok);
    }
******/
}
