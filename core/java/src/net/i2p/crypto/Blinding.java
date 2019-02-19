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
    private static final SigType TYPER = SigType.RedDSA_SHA512_Ed25519;

    private Blinding() {}

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws UnsupportedOperationException unless supported SigTypes
     *  @throws IllegalArgumentException on bad inputs
     */
    public static SigningPublicKey blind(SigningPublicKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPE || alpha.getType() != TYPER)
            throw new UnsupportedOperationException();
        try {
            EdDSAPublicKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPublicKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPER);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws UnsupportedOperationException unless supported SigTypes
     *  @throws IllegalArgumentException on bad inputs
     */
    public static SigningPrivateKey blind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPE || alpha.getType() != TYPER)
            throw new UnsupportedOperationException();
        try {
            EdDSAPrivateKey jk = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey ajk = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey bjk = EdDSABlinding.blind(jk, ajk);
            return SigUtil.fromJavaKey(bjk, TYPER);
        } catch (GeneralSecurityException gse) {
            throw new IllegalArgumentException(gse);
        }
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType RedDSA_SHA512_Ed25519
     *  @param alpha must be SigType RedDSA_SHA512_Ed25519
     *  @return SigType EdDSA_SHA512_Ed25519
     *  @throws UnsupportedOperationException unless supported SigTypes
     *  @throws IllegalArgumentException on bad inputs
     */
    public static SigningPrivateKey unblind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPER || alpha.getType() != TYPER)
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
        net.i2p.data.SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(TYPE);
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        byte[] b = new byte[64];
        net.i2p.I2PAppContext.getGlobalContext().random().nextBytes(b);
        b = EdDSABlinding.reduce(b);
        SigningPrivateKey alpha = new SigningPrivateKey(TYPER, b);
        SigningPublicKey bpub = null;
        try {
            bpub = blind(pub, alpha);
        } catch (Exception e) {
            System.out.println("Blinding pubkey test failed");
            e.printStackTrace();
        }
        SigningPrivateKey bpriv = null;
        try {
            bpriv = blind(priv, alpha);
        } catch (Exception e) {
            System.out.println("Blinding privkey test failed");
            e.printStackTrace();
        }
        if (bpub != null && bpriv != null) {
            SigningPublicKey bpub2 = bpriv.toPublic();
            boolean ok = bpub2.equals(bpub);
            System.out.println("Blinding test passed?   " + ok);
            // unimplemented
            //SigningPrivateKey priv2 = unblind(bpriv, alpha);
            //ok = priv2.equals(priv);
            //System.out.println("Unblinding test passed? " + ok);
        }
    }
******/
}
