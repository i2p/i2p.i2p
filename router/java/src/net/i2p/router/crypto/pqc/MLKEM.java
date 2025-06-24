package net.i2p.router.crypto.pqc;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncAlgo;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.RandomSource;

/**
 * Wrapper around bouncycastle
 *
 * @since 0.9.69
 */
public final class MLKEM {

    /** all non-threaded for now */
    public static final KeyFactory MLKEM512KeyFactory = new MLKEMFactory(EncType.MLKEM512_X25519_INT);
    public static final KeyFactory MLKEM768KeyFactory = new MLKEMFactory(EncType.MLKEM768_X25519_INT);
    public static final KeyFactory MLKEM1024KeyFactory = new MLKEMFactory(EncType.MLKEM1024_X25519_INT);

    private static class MLKEMFactory implements KeyFactory {
        private final EncType t;
        public MLKEMFactory(EncType type) { t = type; }
        public KeyPair getKeys() {
            try {
                return MLKEM.getKeys(t);
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
        }
    }

    /**
     *  Alice side
     *  @param type must be one of the internal types MLKEM*_INT
     *  @return encapkey decapkey
     */
    public static KeyPair getKeys(EncType type) throws GeneralSecurityException {
        byte[][] keys = generateKeys(type);
        PublicKey pub = new PublicKey(type, keys[0]);
        PrivateKey priv = new PrivateKey(type, keys[1]);
        return new KeyPair(pub, priv);
    }

    /**
     *  Alice side
     *  @param type must be one of the internal types MLKEM*_INT
     *  @return encapkey decapkey
     */
    public static byte[][] generateKeys(EncType type) throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMKeyPairGenerator kpg = new MLKEMKeyPairGenerator();
        kpg.init(new MLKEMKeyGenerationParameters(RandomSource.getInstance(), param));
        AsymmetricCipherKeyPair pair = kpg.generateKeyPair();
        MLKEMPublicKeyParameters pubkey = (MLKEMPublicKeyParameters) pair.getPublic();
        MLKEMPrivateKeyParameters privkey = (MLKEMPrivateKeyParameters) pair.getPrivate();
        byte[][] keys = new byte[2][];
        keys[0] = pubkey.getEncoded();
        keys[1] = privkey.getEncoded();
        return keys;
    }

    /**
     *  Bob side
     *  @return ciphertext sharedkey, non-null
     */
    public static byte[][] encaps(EncType type, byte[] pub)
                        throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMGenerator gen = new MLKEMGenerator(I2PAppContext.getGlobalContext().random());
        MLKEMPublicKeyParameters ppub = new MLKEMPublicKeyParameters(param, pub);
        SecretWithEncapsulation swe;
        try {
            swe = gen.generateEncapsulated(ppub);
        } catch (IllegalArgumentException iae) {
            throw new GeneralSecurityException(iae);
        }
        byte[][] keys = new byte[2][];
        keys[0] = swe.getEncapsulation();
        keys[1] = swe.getSecret();
        return keys;
    }

    /**
     *  Alice side
     *  Note that this will not fail???
     *
     *  @return sharedkey, 32 bytes, non-null
     */
    public static byte[] decaps(EncType type, byte[] ciphertext, byte[] decapkey)
                        throws GeneralSecurityException {
        MLKEMParameters param = getParam(type);
        MLKEMPrivateKeyParameters priv = new MLKEMPrivateKeyParameters(param, decapkey);
        MLKEMExtractor ext = new MLKEMExtractor(priv);
        // todo check for "implicit rejection" ?
        return ext.extractSecret(ciphertext);
    }

    /**
     *  EncType to params
     */
    private static MLKEMParameters getParam(EncType type) throws GeneralSecurityException {
        switch(type) {
            case MLKEM512_X25519_INT:
            case MLKEM512_X25519_CT:
                return MLKEMParameters.ml_kem_512;

            case MLKEM768_X25519_INT:
            case MLKEM768_X25519_CT:
                return MLKEMParameters.ml_kem_768;

            case MLKEM1024_X25519_INT:
            case MLKEM1024_X25519_CT:
                return MLKEMParameters.ml_kem_1024;

            default:
                throw new InvalidKeyException("unsupported type: " + type);
        }
    }

    /**
     *  Usage: MLKEM [enctype...]
     */
/*
    public static void main(String args[]) {
        try {
             main2(args);
        } catch (RuntimeException e) {
             e.printStackTrace();
        }
    }
*/

    /**
     *  Usage: MLKEM [enctype...]
     */
/*
    private static void main2(String args[]) {
        RandomSource.getInstance().nextBoolean();
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        int runs = 200; // warmup
        java.util.Collection<EncType> toTest;
        if (args.length > 0) {
            toTest = new java.util.ArrayList<EncType>();
            for (int i = 0; i < args.length; i++) {
                EncType type = EncType.parseEncType(args[i]);
                if (type != null)
                    toTest.add(type);
                else
                    System.out.println("Unknown type: " + args[i]);
            }
            if (toTest.isEmpty()) {
                System.out.println("No types to test");
                return;
            }
        } else {
            toTest = java.util.Arrays.asList(EncType.values());
        }
        for (int j = 0; j < 2; j++) {
            for (EncType type : toTest) {
                if (type.getBaseAlgorithm() != EncAlgo.ECIES_MLKEM)
                    continue;
                if (!type.isAvailable()) {
                    System.out.println("Skipping unavailable: " + type);
                    continue;
                }
                try {
                    System.out.println("Testing " + type);
                    testEnc(type, runs);
                } catch (GeneralSecurityException e) {
                    System.out.println("error testing " + type);
                    e.printStackTrace();
                }
            }
            runs = 1000;
        }
    }

    private static void testEnc(EncType type, int runs) throws GeneralSecurityException {
        double gtime = 0;
        long stime = 0;
        long vtime = 0;
        byte[][] keys = null;
        long st = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            keys = generateKeys(type);
        }
        long en = System.nanoTime();
        gtime = ((en - st) / (1000*1000d)) / runs;
        System.out.println(type + " key gen " + runs + " times: " + gtime + " ms each");
        byte[] pubkey = keys[0];
        byte[] privkey = keys[1];

        //System.out.println("privkey " + keys[1]);
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            byte[][] bob = encaps(type, pubkey);
            byte[] ct = bob[0];
            byte[] s1 = bob[1];
            long mid = System.nanoTime();
            byte[] s2 = decaps(type, ct, privkey);
            boolean ok = DataHelper.eq(s1, s2);
            long end = System.nanoTime();
            stime += mid - start;
            vtime += end - mid;
            if (!ok)
                throw new GeneralSecurityException(type + " shared key fail on run " + i);
        }
        stime /= 1000*1000;
        vtime /= 1000*1000;
        // we do two of each per run above
        int runs2 = runs * 2;
        System.out.println(type + " encap/decap " + runs + " times: " + (vtime+stime) + " ms = " +
                           (((double) stime) / runs2) + " each encap, " +
                           (((double) vtime) / runs2) + " each decap, " +
                           (((double) (stime + vtime)) / runs2) + " d+e");
    }
*/
}
