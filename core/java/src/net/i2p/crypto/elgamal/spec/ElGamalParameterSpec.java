package net.i2p.crypto.elgamal.spec;

import java.math.BigInteger;
import java.security.spec.AlgorithmParameterSpec;

/**
 *  Copied from org.bouncycastle.jce.spec
 *  This can't actually be passed to the BC provider, we would have to
 *  use reflection to create a "real" org.bouncycasle.jce.spec.ElGamalParameterSpec.
 *
 *  @since 0.9.18
 */
public class ElGamalParameterSpec implements AlgorithmParameterSpec {
    private final BigInteger p;
    private final BigInteger g;

    /**
     * Constructs a parameter set for Diffie-Hellman, using a prime modulus
     * <code>p</code> and a base generator <code>g</code>.
     * 
     * @param p the prime modulus
     * @param g the base generator
     */
    public ElGamalParameterSpec(BigInteger p, BigInteger g) {
        this.p = p;
        this.g = g;
    }

    /**
     * Returns the prime modulus <code>p</code>.
     *
     * @return the prime modulus <code>p</code>
     */
    public BigInteger getP() {
        return p;
    }

    /**
     * Returns the base generator <code>g</code>.
     *
     * @return the base generator <code>g</code>
     */
    public BigInteger getG() {
        return g;
    }
}
