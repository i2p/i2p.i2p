package net.i2p.crypto.eddsa;

import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * An EdDSA public key.
 *<p>
 * Warning: Public key encoding is is based on the
 * current IETF draft, and is subject to change. See getEncoded().
 *</p><p>
 * Ref: https://tools.ietf.org/html/draft-josefsson-pkix-eddsa-04
 *</p>
 *
 * @since 0.9.15
 * @author str4d
 *
 */
public class EdDSAPublicKey implements EdDSAKey, PublicKey {
    private static final long serialVersionUID = 9837459837498475L;
    private final GroupElement A;
    private final GroupElement Aneg;
    private final byte[] Abyte;
    private final EdDSAParameterSpec edDsaSpec;

    public EdDSAPublicKey(EdDSAPublicKeySpec spec) {
        this.A = spec.getA();
        this.Aneg = spec.getNegativeA();
        this.Abyte = this.A.toByteArray();
        this.edDsaSpec = spec.getParams();
    }

    /**
     *  @since 0.9.25
     */
    public EdDSAPublicKey(X509EncodedKeySpec spec) throws InvalidKeySpecException {
        this(new EdDSAPublicKeySpec(decode(spec.getEncoded()),
                                    EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.CURVE_ED25519_SHA512)));
    }

    public String getAlgorithm() {
        return "EdDSA";
    }

    public String getFormat() {
        return "X.509";
    }

    /**
     *  This follows the spec at
     *  ref: https://tools.ietf.org/html/draft-josefsson-pkix-eddsa-04
     *  which matches the docs from
     *  java.security.spec.X509EncodedKeySpec
     *  quote:
     *<pre>
     * The SubjectPublicKeyInfo syntax is defined in the X.509 standard as follows:
     *  SubjectPublicKeyInfo ::= SEQUENCE {
     *    algorithm AlgorithmIdentifier,
     *    subjectPublicKey BIT STRING }
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
     *  @return 47 bytes for Ed25519, null for other curves
     *  @since implemented in 0.9.25
     */
    public byte[] getEncoded() {
        if (!edDsaSpec.equals(EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.CURVE_ED25519_SHA512)))
            return null;
        int totlen = 15 + Abyte.length;
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence
        rv[idx++] = 0x30;
        rv[idx++] = (byte) (13 + Abyte.length);
        // Algorithm Identifier
        // sequence
        rv[idx++] = 0x30;
        rv[idx++] = 8;
        // OID 1.3.101.100
        // https://msdn.microsoft.com/en-us/library/windows/desktop/bb540809%28v=vs.85%29.aspx
        rv[idx++] = 0x06;
        rv[idx++] = 3;
        rv[idx++] = (1 * 40) + 3;
        rv[idx++] = 101;
        rv[idx++] = 100;
        // params
        rv[idx++] = 0x0a;
        rv[idx++] = 1;
        rv[idx++] = 1; // Ed25519
        // the key
        rv[idx++] = 0x03; // bit string
        rv[idx++] = (byte) (1 + Abyte.length);
        rv[idx++] = 0; // number of trailing unused bits
        System.arraycopy(Abyte, 0, rv, idx, Abyte.length);
        return rv;
    }

    /**
     *  This is really dumb for now.
     *  See getEncoded().
     *
     *  @return 32 bytes for Ed25519, throws for other curves
     *  @since 0.9.25
     */
    private static byte[] decode(byte[] d) throws InvalidKeySpecException {
        try {
            int idx = 0;
            if (d[idx++] != 0x30 ||
                d[idx++] != 45 ||
                d[idx++] != 0x30 ||
                d[idx++] != 8 ||
                d[idx++] != 0x06 ||
                d[idx++] != 3 ||
                d[idx++] != (1 * 40) + 3 ||
                d[idx++] != 101 ||
                d[idx++] != 100 ||
                d[idx++] != 0x0a ||
                d[idx++] != 1 ||
                d[idx++] != 1 ||
                d[idx++] != 0x03 ||
                d[idx++] != 33 ||
                d[idx++] != 0) {
                throw new InvalidKeySpecException("unsupported key spec");
            }
            byte[] rv = new byte[32];
            System.arraycopy(d, idx, rv, 0, 32);
            return rv;
        } catch (IndexOutOfBoundsException ioobe) {
            throw new InvalidKeySpecException(ioobe);
        }
    }

    public EdDSAParameterSpec getParams() {
        return edDsaSpec;
    }

    public GroupElement getA() {
        return A;
    }

    public GroupElement getNegativeA() {
        return Aneg;
    }

    public byte[] getAbyte() {
        return Abyte;
    }

    /**
     *  @since 0.9.25
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(Abyte);
    }

    /**
     *  @since 0.9.25
     */
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof EdDSAPublicKey))
            return false;
        EdDSAPublicKey pk = (EdDSAPublicKey) o;
        return Arrays.equals(Abyte, pk.getAbyte()) &&
               edDsaSpec.equals(pk.getParams());
    }
}
