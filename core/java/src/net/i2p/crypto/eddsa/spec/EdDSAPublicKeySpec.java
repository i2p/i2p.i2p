package net.i2p.crypto.eddsa.spec;

import java.security.spec.KeySpec;

import net.i2p.crypto.eddsa.math.GroupElement;

/**
 * @author str4d
 *
 */
public class EdDSAPublicKeySpec implements KeySpec {
    private final GroupElement A;
    private final GroupElement Aneg;
    private final EdDSAParameterSpec spec;

    /**
     *  @throws IllegalArgumentException if key length is wrong
     */
    public EdDSAPublicKeySpec(byte[] pk, EdDSAParameterSpec spec) {
        if (pk.length != spec.getCurve().getField().getb()/8)
            throw new IllegalArgumentException("public-key length is wrong");

        this.A = new GroupElement(spec.getCurve(), pk);
        // Precompute -A for use in verification.
        this.Aneg = A.negate();
        Aneg.precompute(false);
        this.spec = spec;
    }

    public EdDSAPublicKeySpec(GroupElement A, EdDSAParameterSpec spec) {
        this.A = A;
        this.Aneg = A.negate();
        Aneg.precompute(false);
        this.spec = spec;
    }

    public GroupElement getA() {
        return A;
    }

    public GroupElement getNegativeA() {
        return Aneg;
    }

    public EdDSAParameterSpec getParams() {
        return spec;
    }
}
