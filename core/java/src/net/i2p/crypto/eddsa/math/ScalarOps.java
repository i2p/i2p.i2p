package net.i2p.crypto.eddsa.math;

/**
 *
 * @since 0.9.15
 *
 */
public interface ScalarOps {
    /**
     * Reduce the given scalar mod l.
     * <p>
     * From the Ed25519 paper:<br>
     * Here we interpret 2b-bit strings in little-endian form as integers in
     * {0, 1,..., 2^(2b)-1}.
     * @param s
     * @return s mod l
     */
    public byte[] reduce(byte[] s);

    /**
     * r = (a * b + c) mod l
     * @param a
     * @param b
     * @param c
     * @return (a*b + c) mod l
     */
    public byte[] multiplyAndAdd(byte[] a, byte[] b, byte[] c);
}
