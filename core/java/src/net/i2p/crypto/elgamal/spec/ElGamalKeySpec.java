package net.i2p.crypto.elgamal.spec;

import java.security.spec.KeySpec;

public class ElGamalKeySpec
    implements KeySpec
{
    private final ElGamalParameterSpec  spec;

    public ElGamalKeySpec(
        ElGamalParameterSpec  spec)
    {
        this.spec = spec;
    }

    public ElGamalParameterSpec getParams()
    {
        return spec;
    }
}
