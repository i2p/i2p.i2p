package net.i2p.crypto.eddsa.spec;

import java.security.spec.AlgorithmParameterSpec;

/**
 * Implementation of AlgorithmParameterSpec that holds the name of a named
 * EdDSA curve specification.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAGenParameterSpec implements AlgorithmParameterSpec {
    private final String name;

    public EdDSAGenParameterSpec(String stdName) {
        name = stdName;
    }

    public String getName() {
        return name;
    }
}
