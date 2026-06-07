package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.crypto.EncapsulatedSecretExtractor;

/**
 * @deprecated use org.bouncycastle.crypto.kems.MLKEMExtractor
 */
@Deprecated
public class MLKEMExtractor
    implements EncapsulatedSecretExtractor
{
    private final MLKEMPrivateKeyParameters privateKey;
    private final MLKEMEngine engine;

    public MLKEMExtractor(MLKEMPrivateKeyParameters privateKey)
    {
        if (privateKey == null)
        {
            throw new NullPointerException("'privateKey' cannot be null");
        }

        this.privateKey = privateKey;
        this.engine = privateKey.getParameters().getEngine();
    }

    public byte[] extractSecret(byte[] encapsulation)
    {
        if (encapsulation.length != this.getEncapsulationLength())
        {
            throw new IllegalArgumentException("encapsulation wrong length");
        }
        return engine.kemDecrypt(privateKey, encapsulation);
    }

    public int getEncapsulationLength()
    {
        return engine.getCipherTextBytes();
    }
}
