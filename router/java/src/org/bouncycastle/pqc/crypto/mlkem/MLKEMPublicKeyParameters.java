package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;

import org.bouncycastle.util.Util;

/**
 * @deprecated use org.bouncycastle.crypto.params.MLKEMKeyPublicKeyParameters
 */
@Deprecated
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

        MLKEMEngine engine = params.getEngine();

        if (t.length != engine.getPolyVecBytes())
        {
            throw new IllegalArgumentException("'t' has invalid length");
        }
        if (rho.length != MLKEMEngine.SymBytes)
        {
            throw new IllegalArgumentException("'rho' has invalid length");
        }

        this.t = Util.clone(t);
        this.rho = Util.clone(rho);

        if (!engine.checkModulus(this.t))
        {
            throw new IllegalArgumentException("Modulus check failed for ML-KEM public key");
        }
    }

    public MLKEMPublicKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        super(false, params);

        MLKEMEngine engine = params.getEngine();

        if (encoding.length != engine.getIndCpaPublicKeyBytes())
        {
            throw new IllegalArgumentException("'encoding' has invalid length");
        }

        this.t = Arrays.copyOfRange(encoding, 0, encoding.length - MLKEMEngine.SymBytes);
        this.rho = Arrays.copyOfRange(encoding, encoding.length - MLKEMEngine.SymBytes, encoding.length);

        if (!engine.checkModulus(this.t))
        {
            throw new IllegalArgumentException("Modulus check failed for ML-KEM public key");
        }
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
