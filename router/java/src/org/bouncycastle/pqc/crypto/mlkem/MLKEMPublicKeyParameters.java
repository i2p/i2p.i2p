package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;

import org.bouncycastle.util.Util;

public class MLKEMPublicKeyParameters
    extends MLKEMKeyParameters
{
    static byte[] getEncoded(byte[] t, byte[] rho)
    {
        return Util.concatenate(t, rho);
    }

    final byte[] t;
    final byte[] rho;

    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] t, byte[] rho)
    {
        super(false, params);
        this.t = Util.clone(t);
        this.rho = Util.clone(rho);
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
        return Util.clone(rho);
    }

    public byte[] getT()
    {
        return Util.clone(t);
    }
}
