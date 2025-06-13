package net.i2p.crypto;

import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Locale;

import static net.i2p.crypto.x25519.spec.X25519Spec.X25519_SPEC;
import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;

/**
 * Defines the properties for various encryption types
 * that I2P supports or may someday support.
 *
 * All PublicKeys and PrivateKeys have a type.
 * Note that a EncType specifies both an algorithm and parameters, so that
 * we may change primes or curves for a given algorithm.
 *
 * @since 0.9.18
 */
public enum EncType {
    /**
     *  2048-bit MODP Group from RFC 3526.
     *  This is the default.
     *  Pubkey 256 bytes, privkey 256 bytes.
     */
    ELGAMAL_2048(0, 256, 256, EncAlgo.ELGAMAL, "ElGamal/None/NoPadding", CryptoConstants.I2P_ELGAMAL_2048_SPEC, "0"),

    /**
     *  Used by i2pd. Not yet supported by Java I2P.
     *  Pubkey 64 bytes; privkey 32 bytes.
     *  See proposal 145.
     */
    EC_P256(1, 64, 32, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P256_SPEC, "0.9.38"),

    /**
     *  Reserved, not used by anybody.
     *  Pubkey 96 bytes; privkey 48 bytes.
     *  See proposal 145.
     */
    EC_P384(2, 96, 48, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P384_SPEC, "0.9.38"),

    /**
     *  Reserved, not used by anybody.
     *  Pubkey 132 bytes; privkey 66 bytes.
     *  See proposal 145.
     */
    EC_P521(3, 132, 66, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P521_SPEC, "0.9.38"),

    /**
     *  Proposal 144.
     *  Pubkey 32 bytes; privkey 32 bytes
     *  @since 0.9.38
     */
    ECIES_X25519(4, 32, 32, EncAlgo.ECIES, "EC/None/NoPadding", X25519_SPEC, "0.9.38"),

    /**
     *  Proposal 169.
     *  Pubkey 32 bytes; privkey 32 bytes
     *  @since 0.9.67
     */
    MLKEM512_X25519(5, 32, 32, EncAlgo.ECIES_MLKEM, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  Proposal 169.
     *  Pubkey 32 bytes; privkey 32 bytes
     *  @since 0.9.67
     */
    MLKEM768_X25519(6, 32, 32, EncAlgo.ECIES_MLKEM, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  Proposal 169.
     *  Pubkey 32 bytes; privkey 32 bytes
     *  @since 0.9.67
     */
    MLKEM1024_X25519(7, 32, 32, EncAlgo.ECIES_MLKEM, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Alice side)
     *  Proposal 169.
     *  Pubkey 800 bytes; privkey 1632 bytes
     *  @since 0.9.67
     */
    MLKEM512_X25519_INT(100005, 800, 1632, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Alice side)
     *  Proposal 169.
     *  Pubkey 1184 bytes; privkey 2400 bytes
     *  @since 0.9.67
     */
    MLKEM768_X25519_INT(100006, 1184, 2400, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Alice side)
     *  Proposal 169.
     *  Pubkey 1568 bytes; privkey 3168 bytes
     *  @since 0.9.67
     */
    MLKEM1024_X25519_INT(100007, 1568, 3168, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Bob side ciphertext)
     *  Proposal 169.
     *  Pubkey 768 bytes; privkey 0
     *  @since 0.9.67
     */
    MLKEM512_X25519_CT(100008, 768, 0, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Bob side ciphertext)
     *  Proposal 169.
     *  Pubkey 1088 bytes; privkey 0
     *  @since 0.9.67
     */
    MLKEM768_X25519_CT(100009, 1088, 0, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67"),

    /**
     *  For internal use only (Bob side ciphertext)
     *  Proposal 169.
     *  Pubkey 1568 bytes; privkey 0
     *  @since 0.9.67
     */
    MLKEM1024_X25519_CT(100010, 1568, 0, EncAlgo.ECIES_MLKEM_INT, "EC/None/NoPadding", X25519_SPEC, "0.9.67");


    private final int code, pubkeyLen, privkeyLen;
    private final EncAlgo base;
    private final String algoName, since;
    private final AlgorithmParameterSpec params;
    private final boolean isAvail, isPQ;

    /**
     *
     *  @param transformation algorithm/mode/padding
     *
     */
    EncType(int cod, int pubLen, int privLen, EncAlgo baseAlgo,
            String transformation, AlgorithmParameterSpec pSpec, String supportedSince) {
        if (pubLen > 256 && baseAlgo != EncAlgo.ECIES_MLKEM_INT)
            throw new IllegalArgumentException("fixup PublicKey for longer keys");
        code = cod;
        pubkeyLen = pubLen;
        privkeyLen = privLen;
        base = baseAlgo;
        algoName = transformation;
        params = pSpec;
        since = supportedSince;
        isAvail = x_isAvailable();
        isPQ = base == EncAlgo.ECIES_MLKEM;
    }

    /** the unique identifier for this type */
    public int getCode() { return code; }

    /** the length of the public key, in bytes */
    public int getPubkeyLen() { return pubkeyLen; }

    /** the length of the private key, in bytes */
    public int getPrivkeyLen() { return privkeyLen; }

    /** the standard base algorithm name used for the Java crypto factories */
    public EncAlgo getBaseAlgorithm() { return base; }

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

    /**
     *  The router version in which this type was first supported.
     */
    public String getSupportedSince() {
        return since;
    }

    /**
     *  @return true if supported in this JVM
     */
    public boolean isAvailable() {
        return isAvail;
    }

    private boolean x_isAvailable() {
        switch (base) {
            case ELGAMAL:
                return true;

            // EC types are placeholders for now
            case EC:
            // internal types
            case ECIES_MLKEM_INT:
                return false;
        }
        try {
            getParams();
        } catch (InvalidParameterSpecException e) {
            return false;
        }
        return true;
    }

    /**
     *  @return true if supported in this JVM
     */
    public static boolean isAvailable(int code) {
        EncType type = getByCode(code);
        if (type == null)
            return false;
        return type.isAvailable();
    }

    /**
     *  @param stype number or name
     *  @return true if supported in this JVM
     */
    public static boolean isAvailable(String stype) {
        EncType type = parseEncType(stype);
        if (type == null)
            return false;
        return type.isAvailable();
    }

    /**
     *  @since 0.9.67
     *  @return true if this is a PQ type
     */
    public boolean isPQ() {
        return isPQ;
    }

    private static final EncType[] BY_CODE;

    static {
        EncType[] values = values();
        int max = values[values.length - 1].getCode();
        BY_CODE = new EncType[max + 1];
        for (EncType type : values) {
            int i = type.getCode();
            if (BY_CODE[i] != null)
                throw new IllegalStateException("Duplicate EncType code");
            BY_CODE[i] = type;
        }
    }

    /** @return null if not supported */
    public static EncType getByCode(int code) {
        if (code < 0 || code >= BY_CODE.length)
            return null;
        return BY_CODE[code];
    }

    /**
     *  Convenience for user apps
     *
     *  @param stype number or name
     *  @return null if not found
     */
    public static EncType parseEncType(String stype) {
        try {
            String uc = stype.toUpperCase(Locale.US);
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
