package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.util.Arrays;

public class MLKEMPublicKeyParameters
    extends MLKEMKeyParameters
{
    static byte[] getEncoded(byte[] t, byte[] rho)
    {
        return Arrays.concatenate(t, rho);
    }

    final byte[] t;
    final byte[] rho;

    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] t, byte[] rho)
    {
        super(false, params);
        this.t = Arrays.clone(t);
        this.rho = Arrays.clone(rho);
    }

    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        super(false, params);
        this.t = Arrays.copyOfRange(encoding, 0, encoding.length - MLKEMEngine.KyberSymBytes);
        this.rho = Arrays.copyOfRange(encoding, encoding.length - MLKEMEngine.KyberSymBytes, encoding.length);
    }

    public byte[] getEncoded()
    {
        return getEncoded(t, rho);
    }

    public byte[] getRho()
    {
        return Arrays.clone(rho);
    }

    public byte[] getT()
    {
        return Arrays.clone(t);
    }
}
