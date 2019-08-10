/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa;

import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

/**
 * An EdDSA private key.
 * <p>
 * For compatibility with older releases, decoding supports both RFC 8410 and an
 * older draft specifications.
 *
 * @since 0.9.15
 * @author str4d
 * @see <a href="https://tools.ietf.org/html/rfc8410">RFC 8410</a>
 * @see <a href=
 *      "https://tools.ietf.org/html/draft-josefsson-pkix-eddsa-04">Older draft
 *      specification</a>
 */
public class EdDSAPrivateKey implements EdDSAKey, PrivateKey {
    private static final long serialVersionUID = 23495873459878957L;
    private final byte[] seed;
    private final byte[] h;
    private final byte[] a;
    private final GroupElement A;
    private final byte[] Abyte;
    private final EdDSAParameterSpec edDsaSpec;

    // OID 1.3.101.xxx
    private static final int OID_OLD = 100;
    private static final int OID_ED25519 = 112;
    private static final int OID_BYTE = 11;
    private static final int IDLEN_BYTE = 6;

    public EdDSAPrivateKey(EdDSAPrivateKeySpec spec) {
        this.seed = spec.getSeed();
        this.h = spec.getH();
        this.a = spec.geta();
        this.A = spec.getA();
        this.Abyte = this.A.toByteArray();
        this.edDsaSpec = spec.getParams();
    }

    /**
     *  @since 0.9.25
     */
    public EdDSAPrivateKey(PKCS8EncodedKeySpec spec) throws InvalidKeySpecException {
        this(new EdDSAPrivateKeySpec(decode(spec.getEncoded()),
                                     EdDSANamedCurveTable.ED_25519_CURVE_SPEC));
    }

    @Override
    public String getAlgorithm() {
        return KEY_ALGORITHM;
    }

    @Override
    public String getFormat() {
        return "PKCS#8";
    }

    /**
     * Returns the private key in its canonical encoding.
     * <p>
     * This implements the following specs:
     * <ul>
     * <li>General encoding: https://tools.ietf.org/html/rfc8410</li>
     * <li>Key encoding: https://tools.ietf.org/html/rfc8032</li>
     * </ul>
     * <p>
     * This encodes the seed. It will return null if constructed from a spec which
     * was directly constructed from H, in which case seed is null.
     * <p>
     * For keys in older formats, decoding and then re-encoding is sufficient to
     * migrate them to the canonical encoding.
     * <p>
     * Relevant spec quotes:
     *
     * <pre>
     *  OneAsymmetricKey ::= SEQUENCE {
     *    version Version,
     *    privateKeyAlgorithm PrivateKeyAlgorithmIdentifier,
     *    privateKey PrivateKey,
     *    attributes [0] IMPLICIT Attributes OPTIONAL,
     *    ...,
     *    [[2: publicKey [1] IMPLICIT PublicKey OPTIONAL ]],
     *    ...
     *  }
     *
     *  Version ::= INTEGER
     *  PrivateKeyAlgorithmIdentifier ::= AlgorithmIdentifier
     *  PrivateKey ::= OCTET STRING
     *  PublicKey ::= BIT STRING
     *  Attributes ::= SET OF Attribute
     * </pre>
     *
     * <pre>
     *  ... when encoding a OneAsymmetricKey object, the private key is wrapped
     *  in a CurvePrivateKey object and wrapped by the OCTET STRING of the
     *  "privateKey" field.
     *
     *  CurvePrivateKey ::= OCTET STRING
     * </pre>
     *
     * <pre>
     *  AlgorithmIdentifier  ::=  SEQUENCE  {
     *    algorithm   OBJECT IDENTIFIER,
     *    parameters  ANY DEFINED BY algorithm OPTIONAL
     *  }
     *
     *  For all of the OIDs, the parameters MUST be absent.
     * </pre>
     *
     * <pre>
     *  id-Ed25519   OBJECT IDENTIFIER ::= { 1 3 101 112 }
     * </pre>
     *
     * @return 48 bytes for Ed25519, null for other curves
     * @since implemented in 0.9.25
     */
    @Override
    public byte[] getEncoded() {
        if (!edDsaSpec.equals(EdDSANamedCurveTable.ED_25519_CURVE_SPEC))
            return null;
        if (seed == null)
            return null;
        int totlen = 16 + seed.length;
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence
        rv[idx++] = 0x30;
        rv[idx++] = (byte) (totlen - 2);
        // version
        rv[idx++] = 0x02;
        rv[idx++] = 1;
        // v1 - no public key included
        rv[idx++] = 0;
        // Algorithm Identifier
        // sequence
        rv[idx++] = 0x30;
        rv[idx++] = 5;
        // OID
        // https://msdn.microsoft.com/en-us/library/windows/desktop/bb540809%28v=vs.85%29.aspx
        rv[idx++] = 0x06;
        rv[idx++] = 3;
        rv[idx++] = (1 * 40) + 3;
        rv[idx++] = 101;
        rv[idx++] = (byte) OID_ED25519;
        // params - absent
        // PrivateKey
        rv[idx++] = 0x04;  // octet string
        rv[idx++] = (byte) (2 + seed.length);
        // CurvePrivateKey
        rv[idx++] = 0x04;  // octet string
        rv[idx++] = (byte) seed.length;
        // the key
        System.arraycopy(seed, 0, rv, idx, seed.length);
        return rv;
    }

    /**
     * Extracts the private key bytes from the provided encoding.
     * <p>
     * This will decode data conforming to RFC 8410 or as inferred from the older
     * draft spec at https://tools.ietf.org/html/draft-josefsson-pkix-eddsa-04.
     * <p>
     * Per RFC 8410 section 3, this function WILL accept a parameter value of
     * NULL, as it is required for interoperability with the default Java keystore.
     * Other implementations MUST NOT copy this behaviour from here unless they also
     * need to read keys from the default Java keystore.
     * <p>
     * This is really dumb for now. It does not use a general-purpose ASN.1 decoder.
     * See also getEncoded().
     *
     * @return 32 bytes for Ed25519, throws for other curves
     * @since 0.9.25
     */
    private static byte[] decode(byte[] d) throws InvalidKeySpecException {
        try {
            //
            // Setup and OID check
            //
            int totlen = 48;
            int idlen = 5;
            int doid = d[OID_BYTE];
            if (doid == OID_OLD) {
                totlen = 49;
                idlen = 8;
            } else if (doid == OID_ED25519) {
                // Detect parameter value of NULL
                if (d[IDLEN_BYTE] == 7) {
                    totlen = 50;
                    idlen = 7;
                }
            } else {
                throw new InvalidKeySpecException("unsupported key spec");
            }

            //
            // Pre-decoding check
            //
            if (d.length != totlen) {
                throw new InvalidKeySpecException("invalid key spec length");
            }

            //
            // Decoding
            //
            int idx = 0;
            if (d[idx++] != 0x30 ||
                d[idx++] != (totlen - 2) ||
                d[idx++] != 0x02 ||
                d[idx++] != 1 ||
                d[idx++] != 0 ||
                d[idx++] != 0x30 ||
                d[idx++] != idlen ||
                d[idx++] != 0x06 ||
                d[idx++] != 3 ||
                d[idx++] != (1 * 40) + 3 ||
                d[idx++] != 101) {
                throw new InvalidKeySpecException("unsupported key spec");
            }
            idx++; // OID, checked above
            // parameters only with old OID
            if (doid == OID_OLD) {
                if (d[idx++] != 0x0a ||
                    d[idx++] != 1 ||
                    d[idx++] != 1) {
                    throw new InvalidKeySpecException("unsupported key spec");
                }
            } else {
                // Handle parameter value of NULL
                //
                // Quoting RFC 8410 section 3:
                // > For all of the OIDs, the parameters MUST be absent.
                // >
                // > It is possible to find systems that require the parameters to be
                // > present. This can be due to either a defect in the original 1997
                // > syntax or a programming error where developers never got input where
                // > this was not true. The optimal solution is to fix these systems;
                // > where this is not possible, the problem needs to be restricted to
                // > that subsystem and not propagated to the Internet.
                //
                // Java's default keystore puts it in (when decoding as PKCS8 and then
                // re-encoding to pass on), so we must accept it.
                if (idlen == 7) {
                    if (d[idx++] != 0x05 ||
                        d[idx++] != 0) {
                        throw new InvalidKeySpecException("unsupported key spec");
                    }
                }
                // PrivateKey wrapping the CurvePrivateKey
                if (d[idx++] != 0x04 ||
                    d[idx++] != 34) {
                    throw new InvalidKeySpecException("unsupported key spec");
                }
            }
            if (d[idx++] != 0x04 ||
                d[idx++] != 32) {
                throw new InvalidKeySpecException("unsupported key spec");
            }
            byte[] rv = new byte[32];
            System.arraycopy(d, idx, rv, 0, 32);
            return rv;
        } catch (IndexOutOfBoundsException ioobe) {
            throw new InvalidKeySpecException(ioobe);
        }
    }

    @Override
    public EdDSAParameterSpec getParams() {
        return edDsaSpec;
    }

    /**
     * @return will be null if constructed from a spec which was directly
     *         constructed from H
     */
    public byte[] getSeed() {
        return seed;
    }

    /**
     * @return the hash of the seed
     */
    public byte[] getH() {
        return h;
    }

    /**
     * @return the private key
     */
    public byte[] geta() {
        return a;
    }

    /**
     * @return the public key
     */
    public GroupElement getA() {
        return A;
    }

    /**
     * @return the public key
     */
    public byte[] getAbyte() {
        return Abyte;
    }

    /**
     *  @since 0.9.25
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(seed);
    }

    /**
     *  @since 0.9.25
     */
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof EdDSAPrivateKey))
            return false;
        EdDSAPrivateKey pk = (EdDSAPrivateKey) o;
        return Arrays.equals(seed, pk.getSeed()) &&
               edDsaSpec.equals(pk.getParams());
    }
}
