package net.i2p.crypto.eddsa.spec;

import java.util.Hashtable;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.ed25519.Ed25519LittleEndianEncoding;
import net.i2p.crypto.eddsa.math.ed25519.Ed25519ScalarOps;

/**
 * The named EdDSA curves.
 * @author str4d
 *
 */
public class EdDSANamedCurveTable {
    public static final String CURVE_ED25519_SHA512 = "ed25519-sha-512";

    private static final Field ed25519field = new Field(
                    256, // b
                    Utils.hexToBytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"), // q
                    new Ed25519LittleEndianEncoding());

    private static final Curve ed25519curve = new Curve(ed25519field,
            Utils.hexToBytes("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352"), // d
            ed25519field.fromByteArray(Utils.hexToBytes("b0a00e4a271beec478e42fad0618432fa7d7fb3d99004d2b0bdfc14f8024832b"))); // I

    private static final EdDSANamedCurveSpec ed25519sha512 = new EdDSANamedCurveSpec(
            CURVE_ED25519_SHA512,
            ed25519curve,
            "SHA-512", // H
            new Ed25519ScalarOps(), // l
            ed25519curve.createPoint( // B
                    Utils.hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"),
                    true)); // Precompute tables for B

    private static final Hashtable<String, EdDSANamedCurveSpec> curves = new Hashtable<String, EdDSANamedCurveSpec>();

    public static void defineCurve(String name, EdDSANamedCurveSpec curve) {
        curves.put(name, curve);
    }

    static {
        defineCurve(CURVE_ED25519_SHA512, ed25519sha512);
    }

    public static EdDSANamedCurveSpec getByName(String name) {
        return curves.get(name);
    }
}
