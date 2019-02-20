package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import net.i2p.I2PAppContext;
import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
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
    private static final String INFO = "i2pblinding1";

    // following copied from RouterKeyGenerator
    private static final String FORMAT = "yyyyMMdd";
    private static final int LENGTH = FORMAT.length();
    private static final SimpleDateFormat _fmt = new SimpleDateFormat(FORMAT, Locale.US);
    static {
        _fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

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

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param dest spk must be SigType EdDSA_SHA512_Ed25519
     *  @param secret may be null or zero-length
     *  @return SigType RedDSA_SHA512_Ed25519
     *  @throws UnsupportedOperationException unless supported SigTypes
     *  @throws IllegalArgumentException on bad inputs
     *  @since 0.9.39
     */
    public static SigningPrivateKey generateAlpha(I2PAppContext ctx, Destination dest, String secret) {
        long now = ctx.clock().now();
        String modVal;
        synchronized(_fmt) {
            modVal = _fmt.format(now);
        }
        if (modVal.length() != LENGTH)
            throw new IllegalStateException();
        byte[] mod = DataHelper.getASCII(modVal);
        byte[] data;
        if (secret != null && secret.length() > 0) {
            data = new byte[LENGTH + secret.length()];
            System.arraycopy(mod, 0, data, 0, LENGTH);
            System.arraycopy(DataHelper.getASCII(secret), 0, data, LENGTH, secret.length());
        } else {
            data = mod;
        }
        HKDF hkdf = new HKDF(ctx);
        byte[] out = new byte[64];
        hkdf.calculate(dest.getHash().getData(), data, INFO, out, out, 32);
        byte[] b = EdDSABlinding.reduce(out);
        return new SigningPrivateKey(TYPER, b);
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
