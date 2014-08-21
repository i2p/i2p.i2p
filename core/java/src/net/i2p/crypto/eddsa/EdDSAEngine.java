package net.i2p.crypto.eddsa;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;

/**
 * @author str4d
 *
 */
public class EdDSAEngine extends Signature {
    private MessageDigest digest;
    private final ByteArrayOutputStream baos;
    private EdDSAKey key;

    /**
     * No specific hash requested, allows any EdDSA key.
     */
    public EdDSAEngine() {
        super("EdDSA");
        baos = new ByteArrayOutputStream(256);
    }

    /**
     * Specific hash requested, only matching keys will be allowed.
     * @param digest the hash algorithm that keys must have to sign or verify.
     */
    public EdDSAEngine(MessageDigest digest) {
        this();
        this.digest = digest;
    }

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        if (digest != null)
            digest.reset();
        baos.reset();

        if (privateKey instanceof EdDSAPrivateKey) {
            EdDSAPrivateKey privKey = (EdDSAPrivateKey) privateKey;
            key = privKey;

            if (digest == null) {
                // Instantiate the digest from the key parameters
                try {
                    digest = MessageDigest.getInstance(key.getParams().getHashAlgorithm());
                } catch (NoSuchAlgorithmException e) {
                    throw new InvalidKeyException("cannot get required digest " + key.getParams().getHashAlgorithm() + " for private key.");
                }
            } else if (!key.getParams().getHashAlgorithm().equals(digest.getAlgorithm()))
                throw new InvalidKeyException("Key hash algorithm does not match chosen digest");

            // Preparing for hash
            // r = H(h_b,...,h_2b-1,M)
            int b = privKey.getParams().getCurve().getField().getb();
            digest.update(privKey.getH(), b/8, b/4 - b/8);
        } else
            throw new InvalidKeyException("cannot identify EdDSA private key.");
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        if (digest != null)
            digest.reset();
        baos.reset();

        if (publicKey instanceof EdDSAPublicKey) {
            key = (EdDSAPublicKey) publicKey;

            if (digest == null) {
                // Instantiate the digest from the key parameters
                try {
                    digest = MessageDigest.getInstance(key.getParams().getHashAlgorithm());
                } catch (NoSuchAlgorithmException e) {
                    throw new InvalidKeyException("cannot get required digest " + key.getParams().getHashAlgorithm() + " for private key.");
                }
            } else if (!key.getParams().getHashAlgorithm().equals(digest.getAlgorithm()))
                throw new InvalidKeyException("Key hash algorithm does not match chosen digest");
        } else
            throw new InvalidKeyException("cannot identify EdDSA public key.");
    }

    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        // We need to store the message because it is used in several hashes
        // XXX Can this be done more efficiently?
        baos.write(b);
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len)
            throws SignatureException {
        // We need to store the message because it is used in several hashes
        // XXX Can this be done more efficiently?
        baos.write(b, off, len);
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
        Curve curve = key.getParams().getCurve();
        ScalarOps sc = key.getParams().getScalarOps();
        byte[] a = ((EdDSAPrivateKey) key).geta();

        byte[] message = baos.toByteArray();
        // r = H(h_b,...,h_2b-1,M)
        byte[] r = digest.digest(message);

        // r mod l
        // Reduces r from 64 bytes to 32 bytes
        r = sc.reduce(r);

        // R = rB
        GroupElement R = key.getParams().getB().scalarMultiply(r);
        byte[] Rbyte = R.toByteArray();

        // S = (r + H(Rbar,Abar,M)*a) mod l
        digest.update(Rbyte);
        digest.update(((EdDSAPrivateKey) key).getAbyte());
        byte[] h = digest.digest(message);
        h = sc.reduce(h);
        byte[] S = sc.multiplyAndAdd(h, a, r);

        // R+S
        int b = curve.getField().getb();
        ByteBuffer out = ByteBuffer.allocate(b/4);
        out.put(Rbyte).put(S);
        return out.array();
    }

    @Override
    protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
        Curve curve = key.getParams().getCurve();
        int b = curve.getField().getb();
        if (sigBytes.length != b/4)
            throw new SignatureException("signature length is wrong");

        // R is first b/8 bytes of sigBytes, S is second b/8 bytes
        digest.update(sigBytes, 0, b/8);
        digest.update(((EdDSAPublicKey) key).getAbyte());
        // h = H(Rbar,Abar,M)
        byte[] message = baos.toByteArray();
        byte[] h = digest.digest(message);

        // h mod l
        h = key.getParams().getScalarOps().reduce(h);

        byte[] Sbyte = Arrays.copyOfRange(sigBytes, b/8, b/4);
        // R = SB - H(Rbar,Abar,M)A
        GroupElement R = key.getParams().getB().doubleScalarMultiplyVariableTime(
                ((EdDSAPublicKey) key).getNegativeA(), h, Sbyte);

        // Variable time. This should be okay, because there are no secret
        // values used anywhere in verification.
        byte[] Rcalc = R.toByteArray();
        for (int i = 0; i < Rcalc.length; i++) {
            if (Rcalc[i] != sigBytes[i])
                return false;
        }
        return true;
    }

    /**
     * @deprecated replaced with <a href="#engineSetParameter(java.security.spec.AlgorithmParameterSpec)">
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
