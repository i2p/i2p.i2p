package net.i2p.crypto;

import java.security.GeneralSecurityException;

import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;


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
     *  @param alpha the secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPublicKey blind(SigningPublicKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPE && alpha.getType() != TYPE)
            throw new UnsupportedOperationException();
        try {
            EdDSAPublicKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPublicKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha the secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPrivateKey blind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPE && alpha.getType() != TYPE)
            throw new UnsupportedOperationException();
        try {
            EdDSAPrivateKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPE);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha the secret data
     *  @throws UnsupportedOperationException unless supported
     */
    public static SigningPrivateKey unblind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPE && alpha.getType() != TYPE)
            throw new UnsupportedOperationException();
        try {
            EdDSAPrivateKey bjk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey jk = EdDSABlinding.unblind(bjk, ajk);
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
