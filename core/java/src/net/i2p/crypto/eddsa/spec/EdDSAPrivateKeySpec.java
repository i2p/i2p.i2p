package net.i2p.crypto.eddsa.spec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.KeySpec;
import java.util.Arrays;

import net.i2p.crypto.eddsa.math.GroupElement;

/**
 * @author str4d
 *
 */
public class EdDSAPrivateKeySpec implements KeySpec {
    private final byte[] seed;
    private final byte[] h;
    private final byte[] a;
    private final GroupElement A;
    private final EdDSAParameterSpec spec;

    /**
     *  @throws IllegalArgumentException if hash algorithm is unsupported
     */
    public EdDSAPrivateKeySpec(byte[] seed, EdDSAParameterSpec spec) {
        this.spec = spec;
        this.seed = seed;

        try {
            MessageDigest hash = MessageDigest.getInstance(spec.getHashAlgorithm());
            int b = spec.getCurve().getField().getb();

            // H(k)
            h = hash.digest(seed);

            /*a = BigInteger.valueOf(2).pow(b-2);
            for (int i=3;i<(b-2);i++) {
                a = a.add(BigInteger.valueOf(2).pow(i).multiply(BigInteger.valueOf(Utils.bit(h,i))));
            }*/
            // Saves ~0.4ms per key when running signing tests.
            // TODO: are these bitflips the same for any hash function?
            h[0] &= 248;
            h[(b/8)-1] &= 63;
            h[(b/8)-1] |= 64;
            a = Arrays.copyOfRange(h, 0, b/8);

            A = spec.getB().scalarMultiply(a);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm");
        }
    }

    public EdDSAPrivateKeySpec(byte[] seed, byte[] h, byte[] a, GroupElement A, EdDSAParameterSpec spec) {
        this.seed = seed;
        this.h = h;
        this.a = a;
        this.A = A;
        this.spec = spec;        
    }

    public byte[] getSeed() {
        return seed;
    }

    public byte[] getH() {
        return h;
    }

    public byte[] geta() {
        return a;
    }

    public GroupElement getA() {
        return A;
    }

    public EdDSAParameterSpec getParams() {
        return spec;
    }
}
