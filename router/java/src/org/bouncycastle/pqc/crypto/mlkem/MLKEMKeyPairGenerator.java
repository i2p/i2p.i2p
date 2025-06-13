package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.KeyGenerationParameters;

public class MLKEMKeyPairGenerator
{
    private MLKEMParameters mlkemParams;

    private SecureRandom random;

    private void initialize(
        KeyGenerationParameters param)
    {
        this.mlkemParams = ((MLKEMKeyGenerationParameters)param).getParameters();
        this.random = param.getRandom();

    }

    private AsymmetricCipherKeyPair genKeyPair()
    {
        MLKEMEngine engine = mlkemParams.getEngine();

        engine.init(random);

        byte[][] keyPair = engine.generateKemKeyPair();

        MLKEMPublicKeyParameters pubKey = new MLKEMPublicKeyParameters(mlkemParams, keyPair[0], keyPair[1]);
        MLKEMPrivateKeyParameters privKey = new MLKEMPrivateKeyParameters(mlkemParams,  keyPair[2], keyPair[3], keyPair[4], keyPair[0], keyPair[1], keyPair[5]);
        
        return new AsymmetricCipherKeyPair(pubKey, privKey);
    }

    public void init(KeyGenerationParameters param)
    {
        this.initialize(param);
    }

    public AsymmetricCipherKeyPair generateKeyPair()
    {
        return genKeyPair();
    }

    public AsymmetricCipherKeyPair internalGenerateKeyPair(byte[] d, byte[] z)
    {
        byte[][] keyPair = mlkemParams.getEngine().generateKemKeyPairInternal(d, z);

        MLKEMPublicKeyParameters pubKey = new MLKEMPublicKeyParameters(mlkemParams, keyPair[0], keyPair[1]);
        MLKEMPrivateKeyParameters privKey = new MLKEMPrivateKeyParameters(mlkemParams,  keyPair[2], keyPair[3], keyPair[4], keyPair[0], keyPair[1], keyPair[5]);

        return new AsymmetricCipherKeyPair(pubKey, privKey);
    }
}
