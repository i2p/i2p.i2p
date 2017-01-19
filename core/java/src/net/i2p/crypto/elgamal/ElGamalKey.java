package net.i2p.crypto.elgamal;

import javax.crypto.interfaces.DHKey;

import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;

public interface ElGamalKey
    extends DHKey
{
    public ElGamalParameterSpec getParameters();
}
