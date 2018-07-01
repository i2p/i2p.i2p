package net.i2p.crypto.eddsa.spec;

import java.util.HashMap;
import java.util.Locale;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.ed25519.Ed25519LittleEndianEncoding;
import net.i2p.crypto.eddsa.math.ed25519.Ed25519ScalarOps;

/**
 * The named EdDSA curves.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSANamedCurveTable {
    /** RFC 8032 */
    public static final String ED_25519 = "Ed25519";
    /** old name */
    public static final String CURVE_ED25519_SHA512 = "ed25519-sha-512";

    private static final Field ed25519field = new Field(
                    256, // b
                    Utils.hexToBytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"), // q
                    new Ed25519LittleEndianEncoding());

    private static final Curve ed25519curve = new Curve(ed25519field,
            Utils.hexToBytes("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352"), // d
            ed25519field.fromByteArray(Utils.hexToBytes("b0a00e4a271beec478e42fad0618432fa7d7fb3d99004d2b0bdfc14f8024832b"))); // I

    public static final EdDSANamedCurveSpec ED_25519_CURVE_SPEC = new EdDSANamedCurveSpec(
            ED_25519,
            ed25519curve,
            "SHA-512", // H
            new Ed25519ScalarOps(), // l
            ed25519curve.createPoint( // B
                    Utils.hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"),
                    true)); // Precompute tables for B

    private static volatile HashMap<String, EdDSANamedCurveSpec> curves = new HashMap<String, EdDSANamedCurveSpec>();

    private static synchronized void putCurve(String name, EdDSANamedCurveSpec curve) {
        HashMap<String, EdDSANamedCurveSpec> newCurves = new HashMap<String, EdDSANamedCurveSpec>(curves);
        newCurves.put(name, curve);
        curves = newCurves;
    }

    public static void defineCurve(EdDSANamedCurveSpec curve) {
        putCurve(curve.getName().toLowerCase(Locale.ENGLISH), curve);
    }

    static void defineCurveAlias(String name, String alias) {
        EdDSANamedCurveSpec curve = curves.get(name.toLowerCase(Locale.ENGLISH));
        if (curve == null) {
            throw new IllegalStateException();
        }
        putCurve(alias.toLowerCase(Locale.ENGLISH), curve);
    }

    static {
        // RFC 8032
        defineCurve(ED_25519_CURVE_SPEC);
        // old name
        defineCurveAlias(ED_25519, CURVE_ED25519_SHA512);
    }

    public static EdDSANamedCurveSpec getByName(String name) {
        return curves.get(name.toLowerCase(Locale.ENGLISH));
    }
}
