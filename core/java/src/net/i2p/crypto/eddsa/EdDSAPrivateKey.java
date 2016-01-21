package net.i2p.crypto.eddsa;

import java.security.PrivateKey;

import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

/**
 * An EdDSA private key.
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAPrivateKey implements EdDSAKey, PrivateKey {
    private static final long serialVersionUID = 23495873459878957L;
    private final byte[] seed;
    private final byte[] h;
    private final byte[] a;
    private final GroupElement A;
    private final byte[] Abyte;
    private final EdDSAParameterSpec edDsaSpec;

    public EdDSAPrivateKey(EdDSAPrivateKeySpec spec) {
        this.seed = spec.getSeed();
        this.h = spec.getH();
        this.a = spec.geta();
        this.A = spec.getA();
        this.Abyte = this.A.toByteArray();
        this.edDsaSpec = spec.getParams();
    }

    public String getAlgorithm() {
        return "EdDSA";
    }

    public String getFormat() {
        return "PKCS#8";
    }

    /**
     *  This follows the spec at
     *  https://tools.ietf.org/html/draft-josefsson-pkix-eddsa-04
     *  NOT the docs from
     *  java.security.spec.PKCS8EncodedKeySpec
     *  quote:
     *<pre>
     *  The PrivateKeyInfo syntax is defined in the PKCS#8 standard as follows:
     *  PrivateKeyInfo ::= SEQUENCE {
     *    version Version,
     *    privateKeyAlgorithm PrivateKeyAlgorithmIdentifier,
     *    privateKey PrivateKey,
     *    attributes [0] IMPLICIT Attributes OPTIONAL }
     *  Version ::= INTEGER
     *  PrivateKeyAlgorithmIdentifier ::= AlgorithmIdentifier
     *  PrivateKey ::= OCTET STRING
     *  Attributes ::= SET OF Attribute
     *</pre>
     *
     *<pre>
     *  AlgorithmIdentifier ::= SEQUENCE
     *  {
     *    algorithm           OBJECT IDENTIFIER,
     *    parameters          ANY OPTIONAL
     *  }
     *</pre>
     *
     *  @return 39 bytes for Ed25519, null for other curves
     *  @since implemented in 0.9.25
     */
    public byte[] getEncoded() {
        // TODO no equals() implemented in spec, but it's essentially a singleton
        if (!edDsaSpec.equals(EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.CURVE_ED25519_SHA512)))
            return null;
        int totlen = 7 + seed.length;
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence
        rv[idx++] = 0x30;
        rv[idx++] = (byte) (5 + seed.length);

        // version
        // not in the Josefsson example
        //rv[idx++] = 0x02;
        //rv[idx++] = 1;
        //rv[idx++] = 0;

        // Algorithm Identifier
        // sequence
        // not in the Josefsson example
        //rv[idx++] = 0x30;
        //rv[idx++] = (byte) (10 + seed.length);
        // OID 1.3.101.100
        // https://msdn.microsoft.com/en-us/library/windows/desktop/bb540809%28v=vs.85%29.aspx
        // not in the Josefsson example
        //rv[idx++] = 0x06;
        //rv[idx++] = 3;
        //rv[idx++] = (1 * 40) + 3;
        //rv[idx++] = 101;
        //rv[idx++] = 100;
        // params
        rv[idx++] = 0x0a;
        rv[idx++] = 1;
        rv[idx++] = 1; // Ed25519
        // the key
        rv[idx++] = 0x04;  // octet string
        rv[idx++] = (byte) seed.length;
        System.arraycopy(seed, 0, rv, idx, seed.length);
        return rv;
    }

    public EdDSAParameterSpec getParams() {
        return edDsaSpec;
    }

    public byte[] getSeed() {
        return seed;
    }

    public byte[] getH() {
        return h;
    }

    public byte[] geta() {
        return a;
    }

    public GroupElement getA() {
        return A;
    }

    public byte[] getAbyte() {
        return Abyte;
    }
}
