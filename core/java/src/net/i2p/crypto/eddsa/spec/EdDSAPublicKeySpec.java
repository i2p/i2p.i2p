package net.i2p.crypto.eddsa.spec;

import java.security.spec.KeySpec;

import net.i2p.crypto.eddsa.math.GroupElement;

/**
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAPublicKeySpec implements KeySpec {
    private final GroupElement A;
    private GroupElement Aneg;
    private final EdDSAParameterSpec spec;

    /**
     *  @param pk the public key
     *  @param spec the parameter specification for this key
     *  @throws IllegalArgumentException if key length is wrong
     */
    public EdDSAPublicKeySpec(byte[] pk, EdDSAParameterSpec spec) {
        if (pk.length != spec.getCurve().getField().getb()/8)
            throw new IllegalArgumentException("public-key length is wrong");

        this.A = new GroupElement(spec.getCurve(), pk);
        this.spec = spec;
    }

    public EdDSAPublicKeySpec(GroupElement A, EdDSAParameterSpec spec) {
        this.A = A;
        this.spec = spec;
    }

    public GroupElement getA() {
        return A;
    }

    public GroupElement getNegativeA() {
        // Only read Aneg once, otherwise read re-ordering might occur between here and return. Requires all GroupElement's fields to be final.
        GroupElement ourAneg = Aneg;
        if(ourAneg == null) {
            ourAneg = A.negate();
            Aneg = ourAneg;
        }
        return ourAneg;
    }

    public EdDSAParameterSpec getParams() {
        return spec;
    }
}
