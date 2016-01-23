package net.i2p.crypto.elgamal;

import java.math.BigInteger;

import javax.crypto.interfaces.DHPublicKey;

public interface ElGamalPublicKey
    extends ElGamalKey, DHPublicKey
{
    public BigInteger getY();
}
