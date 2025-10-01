package org.bouncycastle.pqc.crypto.mlkem;

import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.util.Util;

class MLKEMEngine
{
    private SecureRandom random;
    private MLKEMIndCpa indCpa;

    // constant parameters
    public final static int KyberN = 256;
    public final static int KyberQ = 3329;
    public final static int KyberQinv = 62209;

    public final static int KyberSymBytes = 32; // Number of bytes for Hashes and Seeds
    private final static int KyberSharedSecretBytes = 32; // Number of Bytes for Shared Secret

    public final static int KyberPolyBytes = 384;

    private final static int KyberEta2 = 2;

    private final static int KyberIndCpaMsgBytes = KyberSymBytes;


    // parameters for Kyber{k}
    private final int KyberK;
    private final int KyberPolyVecBytes;
    private final int KyberPolyCompressedBytes;
    private final int KyberPolyVecCompressedBytes;
    private final int KyberEta1;
    private final int KyberIndCpaPublicKeyBytes;
    private final int KyberIndCpaSecretKeyBytes;
    private final int KyberIndCpaBytes;
    private final int KyberPublicKeyBytes;
    private final int KyberSecretKeyBytes;
    private final int KyberCipherTextBytes;

    // Crypto
    private final int CryptoBytes;
    private final int CryptoSecretKeyBytes;
    private final int CryptoPublicKeyBytes;
    private final int CryptoCipherTextBytes;

    private final int sessionKeyLength;
    private final Symmetric symmetric;

    public Symmetric getSymmetric()
    {
        return symmetric;
    }
    public static int getKyberEta2()
    {
        return KyberEta2;
    }

    public static int getKyberIndCpaMsgBytes()
    {
        return KyberIndCpaMsgBytes;
    }

    public int getCryptoCipherTextBytes()
    {
        return CryptoCipherTextBytes;
    }

    public int getCryptoPublicKeyBytes()
    {
        return CryptoPublicKeyBytes;
    }

    public int getCryptoSecretKeyBytes()
    {
        return CryptoSecretKeyBytes;
    }

    public int getCryptoBytes()
    {
        return CryptoBytes;
    }

    public int getKyberCipherTextBytes()
    {
        return KyberCipherTextBytes;
    }

    public int getKyberSecretKeyBytes()
    {
        return KyberSecretKeyBytes;
    }

    public int getKyberIndCpaPublicKeyBytes()
    {
        return KyberIndCpaPublicKeyBytes;
    }


    public int getKyberIndCpaSecretKeyBytes()
    {
        return KyberIndCpaSecretKeyBytes;
    }

    public int getKyberIndCpaBytes()
    {
        return KyberIndCpaBytes;
    }

    public int getKyberPublicKeyBytes()
    {
        return KyberPublicKeyBytes;
    }

    public int getKyberPolyCompressedBytes()
    {
        return KyberPolyCompressedBytes;
    }

    public int getKyberK()
    {
        return KyberK;
    }

    public int getKyberPolyVecBytes()
    {
        return KyberPolyVecBytes;
    }

    public int getKyberPolyVecCompressedBytes()
    {
        return KyberPolyVecCompressedBytes;
    }

    public int getKyberEta1()
    {
        return KyberEta1;
    }

    public MLKEMEngine(int k)
    {
        this.KyberK = k;
        switch (k)
        {
        case 2:
            KyberEta1 = 3;
            KyberPolyCompressedBytes = 128;
            KyberPolyVecCompressedBytes = k * 320;
            sessionKeyLength = 32;
            break;
        case 3:
            KyberEta1 = 2;
            KyberPolyCompressedBytes = 128;
            KyberPolyVecCompressedBytes = k * 320;
            sessionKeyLength = 32;
            break;
        case 4:
            KyberEta1 = 2;
            KyberPolyCompressedBytes = 160;
            KyberPolyVecCompressedBytes = k * 352;
            sessionKeyLength = 32;
            break;
        default:
            throw new IllegalArgumentException("K: " + k + " is not supported for Crystals Kyber");
        }

        this.KyberPolyVecBytes = k * KyberPolyBytes;
        this.KyberIndCpaPublicKeyBytes = KyberPolyVecBytes + KyberSymBytes;
        this.KyberIndCpaSecretKeyBytes = KyberPolyVecBytes;
        this.KyberIndCpaBytes = KyberPolyVecCompressedBytes + KyberPolyCompressedBytes;
        this.KyberPublicKeyBytes = KyberIndCpaPublicKeyBytes;
        this.KyberSecretKeyBytes = KyberIndCpaSecretKeyBytes + KyberIndCpaPublicKeyBytes + 2 * KyberSymBytes;
        this.KyberCipherTextBytes = KyberIndCpaBytes;

        // Define Crypto Params
        this.CryptoBytes = KyberSharedSecretBytes;
        this.CryptoSecretKeyBytes = KyberSecretKeyBytes;
        this.CryptoPublicKeyBytes = KyberPublicKeyBytes;
        this.CryptoCipherTextBytes = KyberCipherTextBytes;

        this.symmetric = new Symmetric.ShakeSymmetric();

        this.indCpa = new MLKEMIndCpa(this);
    }

    public void init(SecureRandom random)
    {
        this.random = random;
    }

    public byte[][] generateKemKeyPair()
    {
        byte[] d = new byte[KyberSymBytes];
        byte[] z = new byte[KyberSymBytes];
        random.nextBytes(d);
        random.nextBytes(z);

        return generateKemKeyPairInternal(d, z);
    }

    //Internal functions are deterministic. No randomness is sampled inside them
    public byte[][] generateKemKeyPairInternal(byte[] d, byte[] z)
    {
        byte[][] indCpaKeyPair = indCpa.generateKeyPair(d);

        byte[] s = new byte[KyberIndCpaSecretKeyBytes];

        System.arraycopy(indCpaKeyPair[1], 0, s, 0, KyberIndCpaSecretKeyBytes);

        byte[] hashedPublicKey = new byte[32];

        symmetric.hash_h(hashedPublicKey, indCpaKeyPair[0], 0);

        byte[] outputPublicKey = new byte[KyberIndCpaPublicKeyBytes];
        System.arraycopy(indCpaKeyPair[0], 0, outputPublicKey, 0, KyberIndCpaPublicKeyBytes);
        return new byte[][]
        {
            Arrays.copyOfRange(outputPublicKey, 0, outputPublicKey.length - 32),
            Arrays.copyOfRange(outputPublicKey, outputPublicKey.length - 32, outputPublicKey.length),
            s,
            hashedPublicKey,
            z,
            Util.concatenate(d, z)
        };
    }

    public byte[][] kemEncryptInternal(byte[] publicKeyInput, byte[] randBytes)
    {
        byte[] outputCipherText;

        byte[] buf = new byte[2 * KyberSymBytes];
        byte[] kr = new byte[2 * KyberSymBytes];

        System.arraycopy(randBytes, 0, buf, 0, KyberSymBytes);

        // SHA3-256 Public Key
        symmetric.hash_h(buf, publicKeyInput, KyberSymBytes);

        // SHA3-512( SHA3-256(RandBytes) || SHA3-256(PublicKey) )
        symmetric.hash_g(kr, buf);

        // IndCpa Encryption
        outputCipherText = indCpa.encrypt(publicKeyInput, Arrays.copyOfRange(buf, 0, KyberSymBytes), Arrays.copyOfRange(kr, 32, kr.length));

        byte[] outputSharedSecret = new byte[sessionKeyLength];

        System.arraycopy(kr, 0, outputSharedSecret, 0, outputSharedSecret.length);

        byte[][] outBuf = new byte[2][];
        outBuf[0] = outputSharedSecret;
        outBuf[1] = outputCipherText;
        return outBuf;
    }

    public byte[] kemDecryptInternal(byte[] secretKey, byte[] cipherText)
    {
        byte[] buf = new byte[2 * KyberSymBytes],
                kr = new byte[2 * KyberSymBytes];

        byte[] publicKey = Arrays.copyOfRange(secretKey, KyberIndCpaSecretKeyBytes, secretKey.length);

        System.arraycopy(indCpa.decrypt(secretKey, cipherText), 0, buf, 0, KyberSymBytes);

        System.arraycopy(secretKey, KyberSecretKeyBytes - 2 * KyberSymBytes, buf, KyberSymBytes, KyberSymBytes);

        symmetric.hash_g(kr, buf);

        byte[] implicit_rejection = new byte[KyberSymBytes + KyberCipherTextBytes];

        System.arraycopy(secretKey, KyberSecretKeyBytes - KyberSymBytes, implicit_rejection, 0, KyberSymBytes);

        System.arraycopy(cipherText, 0, implicit_rejection, KyberSymBytes, KyberCipherTextBytes);

        symmetric.kdf(implicit_rejection, implicit_rejection ); // J(z||c)

        byte[] cmp = indCpa.encrypt(publicKey, Arrays.copyOfRange(buf, 0, KyberSymBytes), Arrays.copyOfRange(kr, KyberSymBytes, kr.length));

        boolean fail = !(Util.constantTimeAreEqual(cipherText, cmp));

        cmov(kr, implicit_rejection, KyberSymBytes, fail);

        return Arrays.copyOfRange(kr, 0, sessionKeyLength);
    }

    public byte[][] kemEncrypt(byte[] publicKeyInput, byte[] randBytes)
    {
        //TODO: do input validation elsewhere?
        // Input validation (6.2 ML-KEM Encaps)
        // Type Check
        if (publicKeyInput.length != KyberIndCpaPublicKeyBytes)
        {
            throw new IllegalArgumentException("Input validation Error: Type check failed for ml-kem encapsulation");
        }
        // Modulus Check
        PolyVec polyVec = new PolyVec(this);
        byte[] seed = indCpa.unpackPublicKey(polyVec, publicKeyInput);
        byte[] ek = indCpa.packPublicKey(polyVec, seed);
        if (!Arrays.equals(ek, publicKeyInput))
        {
            throw new IllegalArgumentException("Input validation: Modulus check failed for ml-kem encapsulation");
        }

        return kemEncryptInternal(publicKeyInput, randBytes);
    }
    public byte[] kemDecrypt(byte[] secretKey, byte[] cipherText)
    {
        //TODO: do input validation
        return kemDecryptInternal(secretKey, cipherText);
    }

    private void cmov(byte[] r, byte[] x, int xlen, boolean b)
    {
        if (b)
        {
            System.arraycopy(x, 0, r, 0, xlen);
        }
        else
        {
            System.arraycopy(r, 0, r, 0, xlen);
        }
    }

    public void getRandomBytes(byte[] buf)
    {
        this.random.nextBytes(buf);
    }
}
