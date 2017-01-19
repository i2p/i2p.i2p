package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.SystemVersion;

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
    DSA_SHA1(0, 128, 20, 20, 40, SigAlgo.DSA, "SHA-1", "SHA1withDSA", CryptoConstants.DSA_SHA1_SPEC, "1.2.840.10040.4.3", "0"),
    /**  Pubkey 64 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes */
    ECDSA_SHA256_P256(1, 64, 32, 32, 64, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.P256_SPEC, "1.2.840.10045.4.3.2", "0.9.12"),
    /**  Pubkey 96 bytes; privkey 48 bytes; hash 48 bytes; sig 96 bytes */
    ECDSA_SHA384_P384(2, 96, 48, 48, 96, SigAlgo.EC, "SHA-384", "SHA384withECDSA", ECConstants.P384_SPEC, "1.2.840.10045.4.3.3", "0.9.12"),
    /**  Pubkey 132 bytes; privkey 66 bytes; hash 64 bytes; sig 132 bytes */
    ECDSA_SHA512_P521(3, 132, 66, 64, 132, SigAlgo.EC, "SHA-512", "SHA512withECDSA", ECConstants.P521_SPEC, "1.2.840.10045.4.3.4", "0.9.12"),

    /**  Pubkey 256 bytes; privkey 512 bytes; hash 32 bytes; sig 256 bytes */
    RSA_SHA256_2048(4, 256, 512, 32, 256, SigAlgo.RSA, "SHA-256", "SHA256withRSA", RSAConstants.F4_2048_SPEC, "1.2.840.113549.1.1.11", "0.9.12"),
    /**  Pubkey 384 bytes; privkey 768 bytes; hash 48 bytes; sig 384 bytes */
    RSA_SHA384_3072(5, 384, 768, 48, 384, SigAlgo.RSA, "SHA-384", "SHA384withRSA", RSAConstants.F4_3072_SPEC, "1.2.840.113549.1.1.12", "0.9.12"),
    /**  Pubkey 512 bytes; privkey 1024 bytes; hash 64 bytes; sig 512 bytes */
    RSA_SHA512_4096(6, 512, 1024, 64, 512, SigAlgo.RSA, "SHA-512", "SHA512withRSA", RSAConstants.F4_4096_SPEC, "1.2.840.113549.1.1.13", "0.9.12"),

    /**
     *  Pubkey 32 bytes; privkey 32 bytes; hash 64 bytes; sig 64 bytes
     *
     *  Due to bugs in previous versions, minimum version is 0.9.17.
     *
     *  @since 0.9.15
     */
    EdDSA_SHA512_Ed25519(7, 32, 32, 64, 64, SigAlgo.EdDSA, "SHA-512", "SHA512withEdDSA",
                         EdDSANamedCurveTable.getByName("ed25519-sha-512"), "1.3.101.101", "0.9.17"),

    /**
     *  Prehash version (double hashing, for offline use such as su3, not for use on the network)
     *  Pubkey 32 bytes; privkey 32 bytes; hash 64 bytes; sig 64 bytes
     *  @since 0.9.25
     */
    EdDSA_SHA512_Ed25519ph(8, 32, 32, 64, 64, SigAlgo.EdDSA, "SHA-512", "NonewithEdDSA",
                           EdDSANamedCurveTable.getByName("ed25519-sha-512"), "1.3.101.101", "0.9.25"),

    ;

    // TESTING....................


    // others..........

    // EC mix and match
    //ECDSA_SHA256_P192(5, 48, 24, 32, 48, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.P192_SPEC),
    //ECDSA_SHA256_P384(6, 96, 48, 32, 96, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.P384_SPEC),
    //ECDSA_SHA256_P521(7, 132, 66, 32, 132, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.P521_SPEC),
    //ECDSA_SHA384_P256(8, 64, 32, 48, 64, SigAlgo.EC, "SHA-384", "SHA384withECDSA", ECConstants.P256_SPEC),
    //ECDSA_SHA384_P521(9, 132, 66, 48, 132, SigAlgo.EC, "SHA-384", "SHA384withECDSA", ECConstants.P521_SPEC),
    //ECDSA_SHA512_P256(10, 64, 32, 64, 64, SigAlgo.EC, "SHA-512", "SHA512withECDSA", ECConstants.P256_SPEC),
    //ECDSA_SHA512_P384(11, 96, 48, 64, 96, SigAlgo.EC, "SHA-512", "SHA512withECDSA", ECConstants.P384_SPEC),

    // Koblitz
    //ECDSA_SHA256_K163(12, 42, 21, 32, 42, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.K163_SPEC),
    //ECDSA_SHA256_K233(13, 60, 30, 32, 60, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.K233_SPEC),
    //ECDSA_SHA256_K283(14, 72, 36, 32, 72, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.K283_SPEC),
    //ECDSA_SHA256_K409(15, 104, 52, 32, 104, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.K409_SPEC),
    //ECDSA_SHA256_K571(16, 144, 72, 32, 144, SigAlgo.EC, "SHA-256", "SHA256withECDSA", ECConstants.K571_SPEC),

    // too short..............
    /**  Pubkey 48 bytes; privkey 24 bytes; hash 20 bytes; sig 48 bytes */
    //ECDSA_SHA1_P192(1, 48, 24, 20, 48, SigAlgo.EC, "SHA-1", "SHA1withECDSA", ECConstants.P192_SPEC),
    //RSA_SHA1(17, 128, 256, 20, 128, SigAlgo.RSA, "SHA-1", "SHA1withRSA", RSAConstants.F4_1024_SPEC),
    //MD5
    //RSA_SHA1

    //ELGAMAL_SHA256
    //DSA_2048_224(2, 256, 28, 32, 56, "SHA-256"),
    // Nonstandard, used by Syndie.
    // Pubkey 128 bytes; privkey 20 bytes; hash 32 bytes; sig 40 bytes
    //DSA_1024_160_SHA256(1, 128, 20, 32, 40, "SHA-256", "?"),
    // Pubkey 256 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes
    //DSA_2048_256(2, 256, 32, 32, 64, "SHA-256", "?"),
    // Pubkey 384 bytes; privkey 32 bytes; hash 32 bytes; sig 64 bytes
    //DSA_3072_256(3, 384, 32, 32, 64, "SHA-256", "?"),


    private final int code, pubkeyLen, privkeyLen, hashLen, sigLen;
    private final SigAlgo base;
    private final String digestName, algoName, oid, since;
    private final AlgorithmParameterSpec params;
    private final boolean isAvail;

    SigType(int cod, int pubLen, int privLen, int hLen, int sLen, SigAlgo baseAlgo,
            String mdName, String aName, AlgorithmParameterSpec pSpec, String oid, String supportedSince) {
        code = cod;
        pubkeyLen = pubLen;
        privkeyLen = privLen;
        hashLen = hLen;
        sigLen = sLen;
        base = baseAlgo;
        digestName = mdName;
        algoName = aName;
        params = pSpec;
        this.oid = oid;
        since = supportedSince;
        isAvail = x_isAvailable();
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
    /** the standard base algorithm name used for the Java crypto factories */
    public SigAlgo getBaseAlgorithm() { return base; }
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

    /**
     *  @since 0.9.9
     *  @throws UnsupportedOperationException if not supported
     */
    public SimpleDataStructure getHashInstance() {
        switch (getHashLen()) {
            case 20:
                return new SHA1Hash();
            case 32:
                return new Hash();
            case 48:
                return new Hash384();
            case 64:
                return new Hash512();
            default:
                throw new UnsupportedOperationException("Unsupported hash length: " + getHashLen());
        }
    }

    /**
     *  The router version in which this type was first supported.
     *
     *  @since 0.9.15
     */
    public String getSupportedSince() {
        return since;
    }

    /**
     *  The OID for the signature.
     *
     *  @since 0.9.25
     */
    public String getOID() {
        return oid;
    }

    /**
     *  @since 0.9.12
     *  @return true if supported in this JVM
     */
    public boolean isAvailable() {
        return isAvail;
    }

    private boolean x_isAvailable() {
        if (DSA_SHA1 == this)
            return true;
        try {
            getParams();
            if (getBaseAlgorithm() != SigAlgo.EdDSA) {
                Signature jsig = Signature.getInstance(getAlgorithmName());
                if (getBaseAlgorithm() == SigAlgo.EC && SystemVersion.isGentoo() ) {
                    // Do a full keygen/sign test on Gentoo, because it lies. Keygen works but sigs fail.
                    // https://bugs.gentoo.org/show_bug.cgi?id=528338
                    // http://icedtea.classpath.org/bugzilla/show_bug.cgi?id=2497
                    // http://zzz.i2p/topics/1931
                    // Be sure nothing in the code paths below calls isAvailable()
                    // get an I2P keypair
                    SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(this);
                    SigningPrivateKey privKey = (SigningPrivateKey) keys[1];
                    // convert privkey back to Java key and sign
                    jsig.initSign(SigUtil.toJavaECKey(privKey));
                    // use the pubkey as random data
                    jsig.update(keys[0].getData());
                    jsig.sign();
                }
            }
            getDigestInstance();
            getHashInstance();
        } catch (GeneralSecurityException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    /**
     *  @return true if supported in this JVM
     *  @since 0.9.15
     */
    public static boolean isAvailable(int code) {
        SigType type = getByCode(code);
        if (type == null)
            return false;
        return type.isAvailable();
    }

    /**
     *  @param stype number or name
     *  @return true if supported in this JVM
     *  @since 0.9.15
     */
    public static boolean isAvailable(String stype) {
        SigType type = parseSigType(stype);
        if (type == null)
            return false;
        return type.isAvailable();
    }

    private static final Map<Integer, SigType> BY_CODE = new HashMap<Integer, SigType>();

    static {
        for (SigType type : SigType.values()) {
            if (BY_CODE.put(Integer.valueOf(type.getCode()), type) != null)
                throw new IllegalStateException("Duplicate SigType code");
        }
    }

    /** @return null if not supported */
    public static SigType getByCode(int code) {
        return BY_CODE.get(Integer.valueOf(code));
    }

    /**
     *  Convenience for user apps
     *
     *  @param stype number or name
     *  @return null if not found
     *  @since 0.9.9 moved from SU3File in 0.9.12
     */
    public static SigType parseSigType(String stype) {
        try {
            String uc = stype.toUpperCase(Locale.US);
            // handle mixed-case enum
            if (uc.equals("EDDSA_SHA512_ED25519"))
                return EdDSA_SHA512_Ed25519;
            if (uc.equals("EDDSA_SHA512_ED25519PH"))
                return EdDSA_SHA512_Ed25519ph;
            return valueOf(uc);
        } catch (IllegalArgumentException iae) {
            try {
                int code = Integer.parseInt(stype);
                return getByCode(code);
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
    }
}
