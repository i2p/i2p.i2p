package net.i2p.crypto.eddsa;

import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;

/**
 * Common interface for all EdDSA keys.
 * @author str4d
 *
 */
public interface EdDSAKey {
    /**
     * return a parameter specification representing the EdDSA domain
     * parameters for the key.
     */
    public EdDSAParameterSpec getParams();
}
