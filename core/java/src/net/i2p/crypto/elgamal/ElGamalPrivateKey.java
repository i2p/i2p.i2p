package net.i2p.crypto.elgamal;

import java.math.BigInteger;

import javax.crypto.interfaces.DHPrivateKey;

public interface ElGamalPrivateKey
    extends ElGamalKey, DHPrivateKey
{
    public BigInteger getX();
}
