package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;

import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.bouncycastle.crypto.EncapsulatedSecretGenerator;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.pqc.crypto.util.SecretWithEncapsulationImpl;

/**
 * @deprecated use org.bouncycastle.crypto.kems.MLKEMGenerator
 */
@Deprecated
public class MLKEMGenerator
    implements EncapsulatedSecretGenerator
{
    private final SecureRandom random;

    public MLKEMGenerator(SecureRandom random)
    {
        this.random = CryptoServicesRegistrar.getSecureRandom(random);
    }

    public SecretWithEncapsulation generateEncapsulated(AsymmetricKeyParameter recipientKey)
    {
        byte[] randBytes = new byte[MLKEMEngine.SymBytes];
        random.nextBytes(randBytes);

        return internalGenerateEncapsulated((MLKEMPublicKeyParameters)recipientKey, randBytes);
    }

    /** @deprecated Use {@link #internalGenerateEncapsulated(MLKEMPublicKeyParameters, byte[])} instead. */
    public SecretWithEncapsulation internalGenerateEncapsulated(AsymmetricKeyParameter recipientKey, byte[] randBytes)
    {
        return internalGenerateEncapsulated((MLKEMPublicKeyParameters)recipientKey, randBytes);
    }

    public static SecretWithEncapsulation internalGenerateEncapsulated(MLKEMPublicKeyParameters recipientKey,
        byte[] randBytes)
    {
        if (randBytes.length != MLKEMEngine.SymBytes)
        {
            throw new IllegalArgumentException("'randBytes' has invalid length");
        }

        MLKEMEngine engine = recipientKey.getParameters().getEngine();
        byte[][] kemEncrypt = engine.kemEncrypt(recipientKey, randBytes);
        return new SecretWithEncapsulationImpl(kemEncrypt[0], kemEncrypt[1]);
    }
}
