package net.i2p.crypto.elgamal;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigUtil;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;

/**
 * ElG signatures with SHA-256
 *
 * ref: https://en.wikipedia.org/wiki/ElGamal_signature_scheme
 *
 * @since 0.9.25
 */
public final class ElGamalSigEngine extends Signature {
    private final MessageDigest digest;
    private ElGamalKey key;

    /**
     * No specific hash requested, allows any ElGamal key.
     */
    public ElGamalSigEngine() {
        this(SHA256Generator.getDigestInstance());
    }

    /**
     * Specific hash requested, only matching keys will be allowed.
     * @param digest the hash algorithm that keys must have to sign or verify.
     */
    public ElGamalSigEngine(MessageDigest digest) {
        super("ElGamal");
        this.digest = digest;
    }

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        digest.reset();
        if (privateKey instanceof ElGamalPrivateKey) {
            ElGamalPrivateKey privKey = (ElGamalPrivateKey) privateKey;
            key = privKey;
        } else {
            throw new InvalidKeyException("cannot identify ElGamal private key: " + privateKey.getClass());
        }
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        digest.reset();
        if (publicKey instanceof ElGamalPublicKey) {
            key = (ElGamalPublicKey) publicKey;
        } else {
            throw new InvalidKeyException("cannot identify ElGamal public key: " + publicKey.getClass());
        }
    }

    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        digest.update(b);
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len)
            throws SignatureException {
        digest.update(b, off, len);
    }

    /**
     *  @return ASN.1 R,S
     */
    @Override
    protected byte[] engineSign() throws SignatureException {
        BigInteger elgp = key.getParams().getP();
        BigInteger pm1 = elgp.subtract(BigInteger.ONE);
        BigInteger elgg = key.getParams().getG();
        BigInteger x = ((ElGamalPrivateKey) key).getX();
        if (!(x instanceof NativeBigInteger))
            x = new NativeBigInteger(x);
        byte[] data = digest.digest();

        BigInteger k;
        boolean ok;
        do {
            k = new BigInteger(2048, RandomSource.getInstance());
            ok = k.compareTo(pm1) == -1;
            ok = ok && k.compareTo(BigInteger.ONE) == 1;
            ok = ok && k.gcd(pm1).equals(BigInteger.ONE);
        } while (!ok);

        BigInteger r = elgg.modPow(k, elgp);
        BigInteger kinv = k.modInverse(pm1);
        BigInteger h = new NativeBigInteger(1, data);
        BigInteger s = (kinv.multiply(h.subtract(x.multiply(r)))).mod(pm1);
        // todo if s == 0 go around again

        byte[] rv;
        try {
            rv = SigUtil.sigBytesToASN1(r, s);
        } catch (IllegalArgumentException iae) {
            throw new SignatureException("ASN1", iae);
        }
        return rv;
    }

    /**
     *  @param sigBytes ASN.1 R,S
     */
    @Override
    protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
        BigInteger elgp = key.getParams().getP();
        BigInteger pm1 = elgp.subtract(BigInteger.ONE);
        BigInteger elgg = key.getParams().getG();
        BigInteger y = ((ElGamalPublicKey) key).getY();
        if (!(y instanceof NativeBigInteger))
            y = new NativeBigInteger(y);
        byte[] data = digest.digest();

        try {
            BigInteger[] rs = SigUtil.aSN1ToBigInteger(sigBytes, 256);
            BigInteger r = rs[0];
            BigInteger s = rs[1];
            if (r.signum() != 1 || s.signum() != 1 ||
                r.compareTo(elgp) != -1 || s.compareTo(pm1) != -1)
                return false;
            NativeBigInteger h = new NativeBigInteger(1, data);
            BigInteger modvalr = r.modPow(s, elgp);
            BigInteger modvaly = y.modPow(r, elgp);
            BigInteger modmulval = modvalr.multiply(modvaly).mod(elgp);
            BigInteger v = elgg.modPow(h, elgp);

            boolean ok = v.compareTo(modmulval) == 0;
            return ok;
        } catch (RuntimeException e) {
            throw new SignatureException("verify", e);
        }
    }

    /**
     * @deprecated replaced with <a href="#engineSetParameter(java.security.spec.AlgorithmParameterSpec)">this</a>
     */
    @Override
    protected void engineSetParameter(String param, Object value) {
        throw new UnsupportedOperationException("engineSetParameter unsupported");
    }

    /**
     * @deprecated
     */
    @Override
    protected Object engineGetParameter(String param) {
        throw new UnsupportedOperationException("engineSetParameter unsupported");
    }
}
