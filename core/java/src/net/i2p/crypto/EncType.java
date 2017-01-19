package net.i2p.crypto;

import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;

/**
 * PRELIMINARY - unused - subject to change
 *
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

    /**  Pubkey 64 bytes; privkey 32 bytes; */
    EC_P256(1, 64, 32, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P256_SPEC, "0.9.20"),

    /**  Pubkey 96 bytes; privkey 48 bytes; */
    EC_P384(2, 96, 48, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P384_SPEC, "0.9.20"),

    /**  Pubkey 132 bytes; privkey 66 bytes; */
    EC_P521(3, 132, 66, EncAlgo.EC, "EC/None/NoPadding", ECConstants.P521_SPEC, "0.9.20");




    private final int code, pubkeyLen, privkeyLen;
    private final EncAlgo base;
    private final String algoName, since;
    private final AlgorithmParameterSpec params;
    private final boolean isAvail;

    /**
     *
     *  @param transformation algorithm/mode/padding
     *
     */
    EncType(int cod, int pubLen, int privLen, EncAlgo baseAlgo,
            String transformation, AlgorithmParameterSpec pSpec, String supportedSince) {
        code = cod;
        pubkeyLen = pubLen;
        privkeyLen = privLen;
        base = baseAlgo;
        algoName = transformation;
        params = pSpec;
        since = supportedSince;
        isAvail = x_isAvailable();
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
        if (ELGAMAL_2048 == this)
            return true;
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

    private static final Map<Integer, EncType> BY_CODE = new HashMap<Integer, EncType>();

    static {
        for (EncType type : EncType.values()) {
            if (BY_CODE.put(Integer.valueOf(type.getCode()), type) != null)
                throw new IllegalStateException("Duplicate EncType code");
        }
    }

    /** @return null if not supported */
    public static EncType getByCode(int code) {
        return BY_CODE.get(Integer.valueOf(code));
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
