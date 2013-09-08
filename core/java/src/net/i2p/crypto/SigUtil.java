package net.i2p.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;
import java.util.Map;

import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.LHMCache;


/**
 * Utilities for Signing keys and Signatures
 *
 * @since 0.9.9
 */
class SigUtil {

    private static final Map<SigningPublicKey, ECPublicKey> _pubkeyCache = new LHMCache(64);
    private static final Map<SigningPrivateKey, ECPrivateKey> _privkeyCache = new LHMCache(16);

    private SigUtil() {}

    /**
     *  @return JAVA key!
     */
    public static PublicKey toJavaKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        if (pk.getType() == SigType.DSA_SHA1)
            throw new UnsupportedOperationException();
        else
            return toJavaECKey(pk);
    }

    /**
     *  @return JAVA key!
     */
    public static PrivateKey toJavaKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        if (pk.getType() == SigType.DSA_SHA1)
            throw new UnsupportedOperationException();
        else
            return toJavaECKey(pk);
    }

    /**
     *  @param pk JAVA key!
     */
    public static SigningPublicKey fromJavaKey(PublicKey pk, SigType type)
                              throws GeneralSecurityException {
        if (type == SigType.DSA_SHA1)
            throw new UnsupportedOperationException();
        else
            return fromJavaKey((ECPublicKey) pk, type);
    }

    /**
     *  @param pk JAVA key!
     */
    public static SigningPrivateKey fromJavaKey(PrivateKey pk, SigType type)
                              throws GeneralSecurityException {
        if (type == SigType.DSA_SHA1)
            throw new UnsupportedOperationException();
        else
            return fromJavaKey((ECPrivateKey) pk, type);
    }

    /**
     *  @return JAVA key!
     */
    public static ECPublicKey toJavaECKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        ECPublicKey rv;
        synchronized (_pubkeyCache) {
            rv = _pubkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaECKey(pk);
        synchronized (_pubkeyCache) {
            _pubkeyCache.put(pk, rv);
        }
        return rv;
    }

    /**
     *  @return JAVA key!
     */
    public static ECPrivateKey toJavaECKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        ECPrivateKey rv;
        synchronized (_privkeyCache) {
            rv = _privkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaECKey(pk);
        synchronized (_privkeyCache) {
            _privkeyCache.put(pk, rv);
        }
        return rv;
    }

    private static ECPublicKey cvtToJavaECKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        SigType type = pk.getType();
        int len = type.getPubkeyLen();
        int sublen = len / 2;
        byte[] b = pk.getData();
        byte[] bx = new byte[sublen];
        byte[] by = new byte[sublen];
        System.arraycopy(b, 0, bx, 0, sublen);
        System.arraycopy(b, sublen, by, 0, sublen);
        BigInteger x = new BigInteger(1, bx);
        BigInteger y = new BigInteger(1, by);
        ECPoint w = new ECPoint(x, y);
        // see ECConstants re: casting
        ECPublicKeySpec ks = new ECPublicKeySpec(w, (ECParameterSpec) type.getParams());
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(ks);
    }

    public static ECPrivateKey cvtToJavaECKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        SigType type = pk.getType();
        int len = type.getPubkeyLen();
        int sublen = len / 2;
        byte[] b = pk.getData();
        BigInteger s = new BigInteger(1, b);
        // see ECConstants re: casting
        ECPrivateKeySpec ks = new ECPrivateKeySpec(s, (ECParameterSpec) type.getParams());
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(ks);
    }

    public static SigningPublicKey fromJavaKey(ECPublicKey pk, SigType type)
                              throws GeneralSecurityException {
        ECPoint w = pk.getW();
        BigInteger x = w.getAffineX();
        BigInteger y = w.getAffineY();
        int len = type.getPubkeyLen();
        int sublen = len / 2;
        byte[] b = new byte[len];
        byte[] bx = rectify(x, sublen);
        byte[] by = rectify(y, sublen);
        System.arraycopy(bx, 0, b, 0, sublen);
        System.arraycopy(by, 0, b, sublen, sublen);
        return new SigningPublicKey(type, b);
    }

    public static SigningPrivateKey fromJavaKey(ECPrivateKey pk, SigType type)
                              throws GeneralSecurityException {
        BigInteger s = pk.getS();
        int len = type.getPrivkeyLen();
        byte[] bs = rectify(s, len);
        return new SigningPrivateKey(type, bs);
    }

    /**
     *  @return ASN.1 representation
     */
    public static byte[] toJavaSig(Signature sig) {
        return sigBytesToASN1(sig.getData());
    }

    /**
     *  @param asn ASN.1 representation
     *  @return a Signature with SigType type
     */
    public static Signature fromJavaSig(byte[] asn, SigType type)
                              throws SignatureException {
        return new Signature(type, aSN1ToSigBytes(asn, type.getSigLen()));
    }

    /**
     *  @param bi non-negative
     *  @return array of exactly len bytes
     */
    public static byte[] rectify(BigInteger bi, int len)
                              throws InvalidKeyException {
        byte[] b = bi.toByteArray();
        if (b.length == len) {
            // just right
            return b;
        }
        if (b.length > len + 1)
            throw new InvalidKeyException("key too big (" + b.length + ") max is " + (len + 1));
        byte[] rv = new byte[len];
        if (b.length == 0)
            return rv;
        if ((b[0] & 0x80) != 0)
            throw new InvalidKeyException("negative");
        if (b.length > len) {
            // leading 0 byte
            if (b[0] != 0)
                throw new InvalidKeyException("key too big (" + b.length + ") max is " + len);
            System.arraycopy(b, 1, rv, 0, len);
        } else {
            // smaller
            System.arraycopy(b, 0, rv, len - b.length, b.length);
        }
        return rv;
    }

    /**
     *  http://download.oracle.com/javase/1.5.0/docs/guide/security/CryptoSpec.html
     *  Signature Format	ASN.1 sequence of two INTEGER values: r and s, in that order:
     *                                SEQUENCE ::= { r INTEGER, s INTEGER }
     *
     *  http://en.wikipedia.org/wiki/Abstract_Syntax_Notation_One
     *  30 -- tag indicating SEQUENCE
     *  xx - length in octets
     *
     *  02 -- tag indicating INTEGER
     *  xx - length in octets
     *  xxxxxx - value
     *
     *  Convert to BigInteger and back so we have the minimum length representation, as required.
     *  r and s are always non-negative.
     *
     *  Only supports sigs up to about 252 bytes. See code to fix BER encoding for this before you
     *  add a SigType with bigger signatures.
     *
     *  @throws IllegalArgumentException if too big
     *  @since 0.8.7, moved to SigUtil in 0.9.9
     */
    private static byte[] sigBytesToASN1(byte[] sig) {
        //System.out.println("pre TO asn1\n" + net.i2p.util.HexDump.dump(sig));
        int len = sig.length;
        int sublen = len / 2;
        byte[] tmp = new byte[sublen];

        System.arraycopy(sig, 0, tmp, 0, sublen);
        BigInteger r = new BigInteger(1, tmp);
        byte[] rb = r.toByteArray();
        if (rb.length > 127)
            throw new IllegalArgumentException("FIXME R length > 127");
        System.arraycopy(sig, sublen, tmp, 0, sublen);
        BigInteger s = new BigInteger(1, tmp);
        byte[] sb = s.toByteArray();
        if (sb.length > 127)
            throw new IllegalArgumentException("FIXME S length > 127");
        int seqlen = rb.length + sb.length + 4;
        if (seqlen > 255)
            throw new IllegalArgumentException("FIXME seq length > 255");
        int totlen = seqlen + 2;
        if (seqlen > 127)
            totlen++;
        byte[] rv = new byte[totlen];
        int idx = 0;

        rv[idx++] = 0x30;
        if (seqlen > 127)
            rv[idx++] =(byte) 0x81;
        rv[idx++] = (byte) seqlen;

        rv[idx++] = 0x02;
        rv[idx++] = (byte) rb.length;
        System.arraycopy(rb, 0, rv, idx, rb.length);
        idx += rb.length;

        rv[idx++] = 0x02;
        rv[idx++] = (byte) sb.length;
        System.arraycopy(sb, 0, rv, idx, sb.length);

        //System.out.println("post TO asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    /**
     *  See above.
     *  Only supports sigs up to about 252 bytes. See code to fix BER encoding for bigger than that.
     *
     *  @return len bytes
     *  @since 0.8.7, moved to SigUtil in 0.9.9
     */
    private static byte[] aSN1ToSigBytes(byte[] asn, int len)
                              throws SignatureException {
        //System.out.println("pre from asn1 len=" + len + "\n" + net.i2p.util.HexDump.dump(asn));
        if (asn[0] != 0x30)
            throw new SignatureException("asn[0] = " + (asn[0] & 0xff));
        // handles total len > 127
        int idx = 2;
        if ((asn[1] & 0x80) != 0)
            idx += asn[1] & 0x7f;
        if (asn[idx] != 0x02)
            throw new SignatureException("asn[2] = " + (asn[idx] & 0xff));
        byte[] rv = new byte[len];
        int sublen = len / 2;
        int rlen = asn[++idx];
        if ((rlen & 0x80) != 0)
            throw new SignatureException("FIXME R length > 127");
        if ((asn[++idx] & 0x80) != 0)
            throw new SignatureException("R is negative");
        if (rlen > sublen + 1)
            throw new SignatureException("R too big " + rlen);
        if (rlen == sublen + 1)
            System.arraycopy(asn, idx + 1, rv, 0, sublen);
        else
            System.arraycopy(asn, idx, rv, sublen - rlen, rlen);
        idx += rlen;
        int slenloc = idx + 1;
        if (asn[idx] != 0x02)
            throw new SignatureException("asn[s] = " + (asn[idx] & 0xff));
        int slen = asn[slenloc];
        if ((slen & 0x80) != 0)
            throw new SignatureException("FIXME S length > 127");
        if ((asn[slenloc + 1] & 0x80) != 0)
            throw new SignatureException("S is negative");
        if (slen > sublen + 1)
            throw new SignatureException("S too big " + slen);
        if (slen == sublen + 1)
            System.arraycopy(asn, slenloc + 2, rv, sublen, sublen);
        else
            System.arraycopy(asn, slenloc + 1, rv, len - slen, slen);
        //System.out.println("post from asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    public static void clearCaches() {
        synchronized(_pubkeyCache) {
            _pubkeyCache.clear();
        }
        synchronized(_privkeyCache) {
            _privkeyCache.clear();
        }
    }
}
