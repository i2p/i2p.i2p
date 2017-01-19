package net.i2p.crypto.elgamal.spec;

import java.math.BigInteger;

/**
 * This class specifies an ElGamal private key with its associated parameters.
 *
 * @see ElGamalPublicKeySpec
 */
public class ElGamalPrivateKeySpec
    extends ElGamalKeySpec
{
    private final BigInteger x;

    public ElGamalPrivateKeySpec(
        BigInteger              x,
        ElGamalParameterSpec    spec)
    {
        super(spec);

        this.x = x;
    }

    /**
     * Returns the private value <code>x</code>.
     *
     * @return the private value <code>x</code>
     */
    public BigInteger getX()
    {
        return x;
    }
}
