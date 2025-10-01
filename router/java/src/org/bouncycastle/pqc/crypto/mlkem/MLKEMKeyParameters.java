package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

public class MLKEMKeyParameters
    extends AsymmetricKeyParameter
{
    private MLKEMParameters params;

    public MLKEMKeyParameters(
        boolean isPrivate,
        MLKEMParameters params)
    {
        super(isPrivate);
        this.params = params;
    }

    public MLKEMParameters getParameters()
    {
        return params;
    }

}
