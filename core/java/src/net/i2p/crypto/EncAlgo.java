package net.i2p.crypto;

/**
 * Base encryption algorithm type
 *
 * @since 0.9.18
 */
public enum EncAlgo {

    ELGAMAL("ElGamal"),
    EC("EC"),

    /** @since 0.9.38 */
    ECIES("ECIES"),

    /** @since 0.9.67 */
    ECIES_MLKEM("ECIES-MLKEM"),

    /** @since 0.9.67 */
    ECIES_MLKEM_INT("ECIES-MLKEM-Internal");


    private final String name;

    EncAlgo(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
