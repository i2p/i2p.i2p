package org.bouncycastle.pqc.crypto.mlkem;

public class MLKEMExtractor
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
        return engine.kemDecrypt(privateKey.getEncoded(), encapsulation);
    }

    public int getEncapsulationLength()
    {
        return engine.getCryptoCipherTextBytes();
    }
}
