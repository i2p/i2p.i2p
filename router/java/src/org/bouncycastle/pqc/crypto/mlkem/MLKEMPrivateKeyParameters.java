package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;

import org.bouncycastle.util.Util;

public class MLKEMPrivateKeyParameters
    extends MLKEMKeyParameters
{
    final byte[] s;
    final byte[] hpk;
    final byte[] nonce;
    final byte[] t;
    final byte[] rho;
    final byte[] seed;
    
    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] s, byte[] hpk, byte[] nonce, byte[] t, byte[] rho)
    {
        this(params, s, hpk, nonce, t, rho, null);
    }

    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] s, byte[] hpk, byte[] nonce, byte[] t, byte[] rho, byte[] seed)
    {
        super(true, params);

        this.s = Util.clone(s);
        this.hpk = Util.clone(hpk);
        this.nonce = Util.clone(nonce);
        this.t = Util.clone(t);
        this.rho = Util.clone(rho);
        this.seed = Util.clone(seed);
    }

    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        super(true, params);

        MLKEMEngine eng = params.getEngine();
        if (encoding.length == MLKEMEngine.KyberSymBytes * 2)
        {
            byte[][] keyData = eng.generateKemKeyPairInternal(
                Arrays.copyOfRange(encoding, 0, MLKEMEngine.KyberSymBytes),
                Arrays.copyOfRange(encoding, MLKEMEngine.KyberSymBytes, encoding.length));
            this.s = keyData[2];
            this.hpk = keyData[3];
            this.nonce = keyData[4];
            this.t = keyData[0];
            this.rho = keyData[1];
            this.seed = keyData[5];
        }
        else
        {
            int index = 0;
            this.s = Arrays.copyOfRange(encoding, 0, eng.getKyberIndCpaSecretKeyBytes());
            index += eng.getKyberIndCpaSecretKeyBytes();
            this.t = Arrays.copyOfRange(encoding, index, index + eng.getKyberIndCpaPublicKeyBytes() - MLKEMEngine.KyberSymBytes);
            index += eng.getKyberIndCpaPublicKeyBytes() - MLKEMEngine.KyberSymBytes;
            this.rho = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.hpk = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.nonce = Arrays.copyOfRange(encoding, index, index + MLKEMEngine.KyberSymBytes);
            this.seed = null;
        }
    }

    public byte[] getEncoded()
    {
        return Util.concatenate(new byte[][]{ s, t, rho, hpk, nonce });
    }

    public byte[] getHPK()
    {
        return Util.clone(hpk);
    }

    public byte[] getNonce()
    {
        return Util.clone(nonce);
    }

    public byte[] getPublicKey()
    {
        return MLKEMPublicKeyParameters.getEncoded(t, rho);
    }

    public MLKEMPublicKeyParameters getPublicKeyParameters()
    {
        return new MLKEMPublicKeyParameters(getParameters(), t, rho);
    }

    public byte[] getRho()
    {
        return Util.clone(rho);
    }

    public byte[] getS()
    {
        return Util.clone(s);
    }

    public byte[] getT()
    {
        return Util.clone(t);
    }

    public byte[] getSeed()
    {
        return Util.clone(seed);
    }
}
