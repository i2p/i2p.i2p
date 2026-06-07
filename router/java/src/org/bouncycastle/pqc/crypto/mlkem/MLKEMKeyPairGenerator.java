package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.AsymmetricCipherKeyPairGenerator;
import org.bouncycastle.crypto.KeyGenerationParameters;

/**
 * @deprecated use org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
 */
@Deprecated
public class MLKEMKeyPairGenerator
    implements AsymmetricCipherKeyPairGenerator
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

        byte[][] keyPair = engine.generateKemKeyPair(random);

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
}
