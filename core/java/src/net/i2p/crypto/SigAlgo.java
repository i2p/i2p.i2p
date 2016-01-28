package net.i2p.crypto;

/**
 * Base signature algorithm type
 *
 * @since 0.9.9
 */
public enum SigAlgo {

    DSA("DSA"),
    EC("EC"),
    EdDSA("EdDSA"),
    /**
     *  For local use only, not for use in the network.
     */
    RSA("RSA"),
    /**
     *  For local use only, not for use in the network.
     *  @since 0.9.25
     */
    ElGamal("ElGamal")
    ;

    private final String name;

    SigAlgo(String name) {
        this.name = name;
    }

    public String getName() { return name; }
}
