package net.i2p.crypto;

/**
 * PRELIMINARY - unused - subject to change
 *
 * Base encryption algorithm type
 *
 * @since 0.9.18
 */
public enum EncAlgo {

    ELGAMAL("ElGamal"),
    EC("EC");

    private final String name;

    EncAlgo(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
