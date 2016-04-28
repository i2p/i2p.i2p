package net.i2p.crypto.elgamal;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import static net.i2p.crypto.CryptoConstants.I2P_ELGAMAL_2048_SPEC;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.elgamal.impl.ElGamalPrivateKeyImpl;
import net.i2p.crypto.elgamal.impl.ElGamalPublicKeyImpl;
import net.i2p.crypto.elgamal.spec.ElGamalGenParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPrivateKeySpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;

/**
 * Modified from eddsa
 * Only supported strength is 2048
 *
 * @since 0.9.25
 */
public final class KeyPairGenerator extends KeyPairGeneratorSpi {
    // always long, don't use short key
    private static final int DEFAULT_STRENGTH = 2048;
    private ElGamalParameterSpec elgParams;
    //private SecureRandom random;
    private boolean initialized;

    /**
     *  @param strength must be 2048
     *  @param random ignored
     */
    public void initialize(int strength, SecureRandom random) {
        if (strength != DEFAULT_STRENGTH)
            throw new InvalidParameterException("unknown key type.");
        elgParams = I2P_ELGAMAL_2048_SPEC;
        try {
            initialize(elgParams, random);
        } catch (InvalidAlgorithmParameterException e) {
            throw new InvalidParameterException("key type not configurable.");
        }
    }

    /**
     *  @param random ignored
     */
    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
        if (params instanceof ElGamalParameterSpec) {
            elgParams = (ElGamalParameterSpec) params;
            if (!elgParams.equals(I2P_ELGAMAL_2048_SPEC))
                throw new InvalidAlgorithmParameterException("unsupported ElGamalParameterSpec");
        } else if (params instanceof ElGamalGenParameterSpec) {
            ElGamalGenParameterSpec elgGPS = (ElGamalGenParameterSpec) params;
            if (elgGPS.getPrimeSize() != DEFAULT_STRENGTH)
                throw new InvalidAlgorithmParameterException("unsupported prime size");
            elgParams = I2P_ELGAMAL_2048_SPEC;
        } else {
            throw new InvalidAlgorithmParameterException("parameter object not a ElGamalParameterSpec");
        }
        //this.random = random;
        initialized = true;
    }

    public KeyPair generateKeyPair() {
        if (!initialized)
            initialize(DEFAULT_STRENGTH, RandomSource.getInstance());
        KeyGenerator kg = KeyGenerator.getInstance();
        SimpleDataStructure[] keys = kg.generatePKIKeys();
        PublicKey pubKey = (PublicKey) keys[0];
        PrivateKey privKey = (PrivateKey) keys[1];
        ElGamalPublicKey epubKey = new ElGamalPublicKeyImpl(new NativeBigInteger(1, pubKey.getData()), elgParams);
        ElGamalPrivateKey eprivKey = new ElGamalPrivateKeyImpl(new NativeBigInteger(1, privKey.getData()), elgParams);
        return new KeyPair(epubKey, eprivKey);
    }
}
