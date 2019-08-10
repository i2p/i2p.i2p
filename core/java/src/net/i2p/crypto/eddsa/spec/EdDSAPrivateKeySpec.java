/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa.spec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.KeySpec;
import java.util.Arrays;

import net.i2p.crypto.eddsa.math.GroupElement;

/**
 *
 * @since 0.9.15
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
     *  @param seed the private key
     *  @param spec the parameter specification for this key
     *  @throws IllegalArgumentException if seed length is wrong or hash algorithm is unsupported
     */
    public EdDSAPrivateKeySpec(byte[] seed, EdDSAParameterSpec spec) {
        int bd8 = spec.getCurve().getField().getb() / 8;
        if (seed.length != bd8)
            throw new IllegalArgumentException("seed length is wrong");

        this.spec = spec;
        this.seed = seed;

        try {
            MessageDigest hash = MessageDigest.getInstance(spec.getHashAlgorithm());

            // H(k)
            h = hash.digest(seed);

            /*a = BigInteger.valueOf(2).pow(b-2);
            for (int i=3;i<(b-2);i++) {
                a = a.add(BigInteger.valueOf(2).pow(i).multiply(BigInteger.valueOf(Utils.bit(h,i))));
            }*/
            // Saves ~0.4ms per key when running signing tests.
            // TODO: are these bitflips the same for any hash function?
            h[0] &= 248;
            h[bd8 - 1] &= 63;
            h[bd8 - 1] |= 64;
            a = Arrays.copyOfRange(h, 0, bd8);

            A = spec.getB().scalarMultiply(a);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm");
        }
    }

    /**
     *  Initialize directly from the hash.
     *  getSeed() will return null if this constructor is used.
     *
     *  @param spec the parameter specification for this key
     *  @param h the private key
     *  @throws IllegalArgumentException if hash length is wrong
     *  @since 0.9.27 (GitHub issue #17)
     */
    public EdDSAPrivateKeySpec(EdDSAParameterSpec spec, byte[] h) {
        int bd4 = spec.getCurve().getField().getb() / 4;
        if (h.length != bd4)
            throw new IllegalArgumentException("hash length is wrong");
        int bd8 = bd4 / 2;

	this.seed = null;
	this.h = h;
	this.spec = spec;

        h[0] &= 248;
        h[bd8 - 1] &= 63;
        h[bd8 - 1] |= 64;
        a = Arrays.copyOfRange(h, 0, bd8);

        A = spec.getB().scalarMultiply(a);
    }

    /**
     *  No validation of any parameters other than a.
     *  getSeed() and getH() will return null if this constructor is used.
     *
     *  @param a must be "clamped" (for Ed) or reduced mod l (for Red)
     *  @param A if null, will be derived from a.
     *  @throws IllegalArgumentException if a not clamped or reduced
     *  @since 0.9.39
     */
    public EdDSAPrivateKeySpec(byte[] a, GroupElement A, EdDSAParameterSpec spec) {
        this(null, null, a, A, spec);
    }

    /**
     *  No validation of any parameters other than a.
     *
     *  @param seed may be null
     *  @param h may be null
     *  @param a must be "clamped" (for Ed) or reduced mod l (for Red)
     *  @param A if null, will be derived from a.
     *  @throws IllegalArgumentException if a not clamped or reduced
     */
    public EdDSAPrivateKeySpec(byte[] seed, byte[] h, byte[] a, GroupElement A, EdDSAParameterSpec spec) {
/**
        // TODO if we move RedDSA to a different spec
        int bd8m1 = (spec.getCurve().getField().getb() / 8) - 1;
        if ((a[0] & 0x07) != 0 ||
            (a[bd8m1] & 0xc0) != 0x40)
            throw new IllegalArgumentException("a not clamped: a[0]=0x" + Integer.toString(a[0] & 0xff, 16) +
                                                             " a[31]=0x" + Integer.toString(a[31] & 0xff, 16));
**/
        this.seed = seed;
        this.h = h;
        this.a = a;
        this.A = (A != null) ? A : spec.getB().scalarMultiply(a);
        this.spec = spec;
    }

    /**
     *  @return will be null if constructed directly from the private key
     */
    public byte[] getSeed() {
        return seed;
    }

    /**
     *  @return the hash
     */
    public byte[] getH() {
        return h;
    }

    /**
     *  @return the private key
     */
    public byte[] geta() {
        return a;
    }

    /**
     *  @return the public key
     */
    public GroupElement getA() {
        return A;
    }

    public EdDSAParameterSpec getParams() {
        return spec;
    }
}
