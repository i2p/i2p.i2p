package net.i2p.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the properties for various signature types
 * that I2P supports or may someday support.
 *
 * All Signatures, SigningPublicKeys, and SigningPrivateKeys have a type.
 * Note that a SigType specifies both an algorithm and parameters, so that
 * we may change primes or curves for a given algorithm.
 *
 * @since 0.9.8
 */
public enum SigType {
    /**
     *  DSA_SHA1 is the default.
     *  Pubkey 128 bytes; privkey 20 bytes; hash 20 bytes; sig 40 bytes
     *  @since 0.9.8
     */
    DSA_SHA1(0, 128, 20, 20, 40, "SHA-1", "SHA1withDSA", null),
    /**  Pubkey 48 bytes; privkey 24 bytes; hash 20 bytes; sig 48 bytes */
    ECDSA_SHA1_P192(1, 48, 24, 20, 48, "SHA-1", "SHA1withECDSA", null),
    /**  Pubkey 64 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes */
    ECDSA_SHA256_P256(2, 64, 32, 32, 64, "SHA-256", "SHA256withECDSA", null),
    /**  Pubkey 96 bytes; privkey 48 bytes; hash 48 bytes; sig 96 bytes */
    ECDSA_SHA384_P384(3, 96, 48, 48, 96, "SHA-384", "SHA384withECDSA", null),
    /**  Pubkey 132 bytes; privkey 66 bytes; hash 64 bytes; sig 132 bytes */
    ECDSA_SHA512_P521(4, 132, 66, 64, 132, "SHA-512", "SHA512withECDSA", null),

    //MD5
    //ELGAMAL_SHA256
    //RSA_SHA1
    //RSA_SHA256
    //RSA_SHA384
    //RSA_SHA512
    //DSA_2048_224(2, 256, 28, 32, 56, "SHA-256"),
    // Nonstandard, used by Syndie.
    // Pubkey 128 bytes; privkey 20 bytes; hash 32 bytes; sig 40 bytes
    //DSA_1024_160_SHA256(1, 128, 20, 32, 40, "SHA-256", "?"),
    // Pubkey 256 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes
    //DSA_2048_256(2, 256, 32, 32, 64, "SHA-256", "?"),
    // Pubkey 384 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes
    //DSA_3072_256(3, 384, 32, 32, 64, "SHA-256", "?"),
    ;   

    private final int code, pubkeyLen, privkeyLen, hashLen, sigLen;
    private final String digestName, algoName;
    private final AlgorithmParameterSpec params;

    SigType(int cod, int pubLen, int privLen, int hLen, int sLen,
            String mdName, String aName, AlgorithmParameterSpec pSpec) {
        code = cod;
        pubkeyLen = pubLen;
        privkeyLen = privLen;
        hashLen = hLen;
        sigLen = sLen;
        digestName = mdName;
        algoName = aName;
        params = pSpec;
    }

    /** the unique identifier for this type */
    public int getCode() { return code; }
    /** the length of the public key, in bytes */
    public int getPubkeyLen() { return pubkeyLen; }
    /** the length of the private key, in bytes */
    public int getPrivkeyLen() { return privkeyLen; }
    /** the length of the hash, in bytes */
    public int getHashLen() { return hashLen; }
    /** the length of the signature, in bytes */
    public int getSigLen() { return sigLen; }
    /** the standard name used for the Java crypto factories */
    public String getAlgorithmName() { return algoName; }
    /**
     *  The elliptic curve ECParameterSpec for ECDSA; DSAParameterSpec for DSA
     *  @throws InvalidParameterSpecException if the algorithm is not available on this JVM.
     */
    public AlgorithmParameterSpec getParams() throws InvalidParameterSpecException {
        if (params == null)
            throw new InvalidParameterSpecException(toString() + " is not available in this JVM");
        return params;
    }

    /** @throws UnsupportedOperationException if not supported */
    public MessageDigest getDigestInstance() {
        if (digestName.equals("SHA-1"))
            return SHA1.getInstance();
        if (digestName.equals("SHA-256"))
            return SHA256Generator.getDigestInstance();
        try {
            return MessageDigest.getInstance(digestName);
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    private static final Map<Integer, SigType> BY_CODE = new HashMap<Integer, SigType>();

    static {
        for (SigType type : SigType.values()) {
            BY_CODE.put(Integer.valueOf(type.getCode()), type);
        }
    }

    /** @return null if not supported */
    public static SigType getByCode(int code) {
        return BY_CODE.get(Integer.valueOf(code));
    }
}
