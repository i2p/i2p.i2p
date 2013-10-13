package net.i2p.crypto;

/**
 * Base signature algorithm type
 *
 * @since 0.9.9
 */
public enum SigAlgo {

    DSA("DSA"),
    EC("EC"),
    RSA("RSA")
    ;

    private final String name;

    SigAlgo(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
