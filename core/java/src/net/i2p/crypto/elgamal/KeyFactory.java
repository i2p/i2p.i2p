package net.i2p.crypto.elgamal;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.spec.DHParameterSpec;

import static net.i2p.crypto.CryptoConstants.I2P_ELGAMAL_2048_SPEC;
import net.i2p.crypto.elgamal.impl.ElGamalPrivateKeyImpl;
import net.i2p.crypto.elgamal.impl.ElGamalPrivateKeyImpl;
import net.i2p.crypto.elgamal.impl.ElGamalPublicKeyImpl;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPrivateKeySpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;

/**
 * Modified from eddsa
 *
 * @since 0.9.25
 */
public final class KeyFactory extends KeyFactorySpi {

    /**
     *  Supports PKCS8EncodedKeySpec
     */
    protected PrivateKey engineGeneratePrivate(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof ElGamalPrivateKeySpec) {
            return new ElGamalPrivateKeyImpl((ElGamalPrivateKeySpec) keySpec);
        }
        if (keySpec instanceof PKCS8EncodedKeySpec) {
            return new ElGamalPrivateKeyImpl((PKCS8EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised");
    }

    /**
     *  Supports X509EncodedKeySpec
     */
    protected PublicKey engineGeneratePublic(KeySpec keySpec)
            throws InvalidKeySpecException {
        if (keySpec instanceof ElGamalPublicKeySpec) {
            return new ElGamalPublicKeyImpl((ElGamalPublicKeySpec) keySpec);
        }
        if (keySpec instanceof X509EncodedKeySpec) {
            return new ElGamalPublicKeyImpl((X509EncodedKeySpec) keySpec);
        }
        throw new InvalidKeySpecException("key spec not recognised");
    }

    @SuppressWarnings("unchecked")
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpec)
            throws InvalidKeySpecException {
        if (keySpec.isAssignableFrom(ElGamalPublicKeySpec.class) && key instanceof ElGamalPublicKey) {
            ElGamalPublicKey k = (ElGamalPublicKey) key;
            ElGamalParameterSpec egp = k.getParameters();
            if (egp != null) {
                return (T) new ElGamalPrivateKeySpec(k.getY(), egp);
            }
        } else if (keySpec.isAssignableFrom(ElGamalPrivateKeySpec.class) && key instanceof ElGamalPrivateKey) {
            ElGamalPrivateKey k = (ElGamalPrivateKey) key;
            ElGamalParameterSpec egp = k.getParameters();
            if (egp != null) {
                return (T) new ElGamalPrivateKeySpec(k.getX(), egp);
            }
        }
        throw new InvalidKeySpecException("not implemented yet " + key + " " + keySpec);
    }

    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        throw new InvalidKeyException("No other ElGamal key providers known");
    }
}
