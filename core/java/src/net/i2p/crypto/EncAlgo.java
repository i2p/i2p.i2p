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
    ECIES("ECIES");

    private final String name;

    EncAlgo(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
