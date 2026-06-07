package org.bouncycastle.pqc.crypto.mlkem;

import java.util.Arrays;

import org.bouncycastle.util.Util;

/**
 * @deprecated use org.bouncycastle.crypto.params.MLKEMKeyPrivateKeyParameters
 */
@Deprecated
public class MLKEMPrivateKeyParameters
    extends MLKEMKeyParameters
{
    public static final int BOTH = 0;
    public static final int SEED_ONLY = 1;
    public static final int EXPANDED_KEY = 2;

    final byte[] s;
    final byte[] hpk;
    final byte[] nonce;
    final byte[] t;
    final byte[] rho;
    final byte[] seed;

    private final int prefFormat;

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
        this.prefFormat = BOTH;

        validate();
    }

    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] encoding)
    {
        this(params, encoding, null);
    }

    public MLKEMPrivateKeyParameters(MLKEMParameters params, byte[] encoding, MLKEMPublicKeyParameters pubKey)
    {
        super(true, params);

        MLKEMEngine eng = params.getEngine();
        if (encoding.length == MLKEMEngine.SeedBytes)
        {
            byte[][] keyData = eng.generateKemKeyPairInternal(
                Arrays.copyOfRange(encoding, 0, MLKEMEngine.SymBytes),
                Arrays.copyOfRange(encoding, MLKEMEngine.SymBytes, encoding.length));
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
            this.s = Arrays.copyOfRange(encoding, 0, eng.getIndCpaSecretKeyBytes());
            index += eng.getIndCpaSecretKeyBytes();
            this.t = Arrays.copyOfRange(encoding, index, index + eng.getIndCpaPublicKeyBytes() - MLKEMEngine.SymBytes);
            index += eng.getIndCpaPublicKeyBytes() - MLKEMEngine.SymBytes;
            this.rho = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.hpk = Arrays.copyOfRange(encoding, index, index + 32);
            index += 32;
            this.nonce = Arrays.copyOfRange(encoding, index, index + MLKEMEngine.SymBytes);
            this.seed = null;
        }

        validate();

        if (pubKey != null)
        {
            if (!Util.constantTimeAreEqual(this.t, pubKey.t) || !Util.constantTimeAreEqual(this.rho, pubKey.rho))
            {
                throw new IllegalArgumentException("passed in public key does not match private values");
            }
        }

        this.prefFormat = (seed == null) ? EXPANDED_KEY : BOTH;
    }

    private MLKEMPrivateKeyParameters(MLKEMPrivateKeyParameters params, int preferredFormat)
    {
        super(true, params.getParameters());

        this.s = params.s;
        this.t = params.t;
        this.rho = params.rho;
        this.hpk = params.hpk;
        this.nonce = params.nonce;
        this.seed = params.seed;
        this.prefFormat = preferredFormat;

        validate();
    }

    private void validate()
    {
        MLKEMEngine engine = getParameters().getEngine();
        if (!engine.checkPrivateKey(getEncoded()))
        {
            throw new IllegalArgumentException("'encoding' fails hash check");
        }
    }

    /** @deprecated Use {@link #withPreferredFormat(int)} instead. */
    public MLKEMPrivateKeyParameters getParametersWithFormat(int format)
    {
        return withPreferredFormat(format); 
    }

    public MLKEMPrivateKeyParameters withPreferredFormat(int format)
    {
        if (this.prefFormat == format)
        {
            return this;
        }

        switch (format)
        {
        case BOTH:
        case SEED_ONLY:
        {
            if (this.seed == null)
            {
                throw new IllegalStateException("no seed available");
            }
            break;
        }
        case EXPANDED_KEY:
            break;
        default:
            throw new IllegalArgumentException("unknown format");
        }

        return new MLKEMPrivateKeyParameters(this, format);
    }

    public int getPreferredFormat()
    {
        return prefFormat;
    }

    public byte[] getEncoded()
    {
        return Util.concatenate(new byte[][]{s, t, rho, hpk, nonce});
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
