package net.i2p.crypto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECPoint;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.LHMCache;
import net.i2p.util.NativeBigInteger;


/**
 * Utilities for Signing keys and Signatures
 *
 * @since 0.9.9, public since 0.9.12
 */
public final class SigUtil {

    private static final Map<SigningPublicKey, ECPublicKey> _ECPubkeyCache = new LHMCache<SigningPublicKey, ECPublicKey>(64);
    private static final Map<SigningPrivateKey, ECPrivateKey> _ECPrivkeyCache = new LHMCache<SigningPrivateKey, ECPrivateKey>(16);
    private static final Map<SigningPublicKey, EdDSAPublicKey> _EdPubkeyCache = new LHMCache<SigningPublicKey, EdDSAPublicKey>(64);
    private static final Map<SigningPrivateKey, EdDSAPrivateKey> _EdPrivkeyCache = new LHMCache<SigningPrivateKey, EdDSAPrivateKey>(16);

    private SigUtil() {}

    /**
     *  @return JAVA key!
     */
    public static PublicKey toJavaKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        switch (pk.getType().getBaseAlgorithm()) {
            case DSA:
                return toJavaDSAKey(pk);
            case EC:
                return toJavaECKey(pk);
            case EdDSA:
                return toJavaEdDSAKey(pk);
            case RSA:
                return toJavaRSAKey(pk);
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     *  @return JAVA key!
     */
    public static PrivateKey toJavaKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        switch (pk.getType().getBaseAlgorithm()) {
            case DSA:
                return toJavaDSAKey(pk);
            case EC:
                return toJavaECKey(pk);
            case EdDSA:
                return toJavaEdDSAKey(pk);
            case RSA:
                return toJavaRSAKey(pk);
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     *  Use if SigType is unknown.
     *  For efficiency, use fromJavakey(pk, type) if type is known.
     *
     *  @param pk JAVA key!
     *  @throws IllegalArgumentException on unknown type
     *  @since 0.9.18
     */
    public static SigningPublicKey fromJavaKey(PublicKey pk)
                              throws GeneralSecurityException {
        if (pk instanceof DSAPublicKey) {
            return fromJavaKey((DSAPublicKey) pk);
        }
        if (pk instanceof ECPublicKey) {
            ECPublicKey k = (ECPublicKey) pk;
            AlgorithmParameterSpec spec = k.getParams();
            SigType type;
            if (spec.equals(SigType.ECDSA_SHA256_P256.getParams()))
                type = SigType.ECDSA_SHA256_P256;
            else if (spec.equals(SigType.ECDSA_SHA384_P384.getParams()))
                type = SigType.ECDSA_SHA384_P384;
            else if (spec.equals(SigType.ECDSA_SHA512_P521.getParams()))
                type = SigType.ECDSA_SHA512_P521;
            else
                throw new IllegalArgumentException("Unknown EC type");
            return fromJavaKey(k, type);
        }
        if (pk instanceof EdDSAPublicKey) {
            return fromJavaKey((EdDSAPublicKey) pk, SigType.EdDSA_SHA512_Ed25519);
        }
        if (pk instanceof RSAPublicKey) {
            RSAPublicKey k = (RSAPublicKey) pk;
            int sz = k.getModulus().bitLength();
            SigType type;
            if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA256_2048.getParams()).getKeysize())
                type = SigType.RSA_SHA256_2048;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA384_3072.getParams()).getKeysize())
                type = SigType.RSA_SHA384_3072;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA512_4096.getParams()).getKeysize())
                type = SigType.RSA_SHA512_4096;
            else
                throw new IllegalArgumentException("Unknown RSA type");
            return fromJavaKey(k, type);
        }
        throw new IllegalArgumentException("Unknown type: " + pk.getClass());
    }

    /**
     *  Use if SigType is known.
     *
     *  @param pk JAVA key!
     */
    public static SigningPublicKey fromJavaKey(PublicKey pk, SigType type)
                              throws GeneralSecurityException {
        switch (type.getBaseAlgorithm()) {
            case DSA:
                return fromJavaKey((DSAPublicKey) pk);
            case EC:
                return fromJavaKey((ECPublicKey) pk, type);
            case EdDSA:
                return fromJavaKey((EdDSAPublicKey) pk, type);
            case RSA:
                return fromJavaKey((RSAPublicKey) pk, type);
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    /**
     *  Use if SigType is unknown.
     *  For efficiency, use fromJavakey(pk, type) if type is known.
     *
     *  @param pk JAVA key!
     *  @throws IllegalArgumentException on unknown type
     *  @since 0.9.18
     */
    public static SigningPrivateKey fromJavaKey(PrivateKey pk)
                              throws GeneralSecurityException {
        if (pk instanceof DSAPrivateKey) {
            return fromJavaKey((DSAPrivateKey) pk);
        }
        if (pk instanceof ECPrivateKey) {
            ECPrivateKey k = (ECPrivateKey) pk;
            AlgorithmParameterSpec spec = k.getParams();
            SigType type;
            if (spec.equals(SigType.ECDSA_SHA256_P256.getParams()))
                type = SigType.ECDSA_SHA256_P256;
            else if (spec.equals(SigType.ECDSA_SHA384_P384.getParams()))
                type = SigType.ECDSA_SHA384_P384;
            else if (spec.equals(SigType.ECDSA_SHA512_P521.getParams()))
                type = SigType.ECDSA_SHA512_P521;
            else {
                // failing on Android (ticket #2296)
                throw new IllegalArgumentException("Unknown EC type: " + pk.getClass() + " spec: " + spec.getClass());
            }
            return fromJavaKey(k, type);
        }
        if (pk instanceof EdDSAPrivateKey) {
            return fromJavaKey((EdDSAPrivateKey) pk, SigType.EdDSA_SHA512_Ed25519);
        }
        if (pk instanceof RSAPrivateKey) {
            RSAPrivateKey k = (RSAPrivateKey) pk;
            int sz = k.getModulus().bitLength();
            SigType type;
            if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA256_2048.getParams()).getKeysize())
                type = SigType.RSA_SHA256_2048;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA384_3072.getParams()).getKeysize())
                type = SigType.RSA_SHA384_3072;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA512_4096.getParams()).getKeysize())
                type = SigType.RSA_SHA512_4096;
            else
                throw new IllegalArgumentException("Unknown RSA type");
            return fromJavaKey(k, type);
        }
        throw new IllegalArgumentException("Unknown type: " + pk.getClass());
    }

    /**
     *  Use if SigType is known.
     *
     *  @param pk JAVA key!
     */
    public static SigningPrivateKey fromJavaKey(PrivateKey pk, SigType type)
                              throws GeneralSecurityException {
        switch (type.getBaseAlgorithm()) {
            case DSA:
                return fromJavaKey((DSAPrivateKey) pk);
            case EC:
                return fromJavaKey((ECPrivateKey) pk, type);
            case EdDSA:
                return fromJavaKey((EdDSAPrivateKey) pk, type);
            case RSA:
                return fromJavaKey((RSAPrivateKey) pk, type);
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    /**
     *  @return JAVA key!
     */
    public static ECPublicKey toJavaECKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        ECPublicKey rv;
        synchronized (_ECPubkeyCache) {
            rv = _ECPubkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaECKey(pk);
        synchronized (_ECPubkeyCache) {
            _ECPubkeyCache.put(pk, rv);
        }
        return rv;
    }

    /**
     *  @return JAVA key!
     */
    public static ECPrivateKey toJavaECKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        ECPrivateKey rv;
        synchronized (_ECPrivkeyCache) {
            rv = _ECPrivkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaECKey(pk);
        synchronized (_ECPrivkeyCache) {
            _ECPrivkeyCache.put(pk, rv);
        }
        return rv;
    }

    private static ECPublicKey cvtToJavaECKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        SigType type = pk.getType();
        BigInteger[] xy = split(pk.getData());
        ECPoint w = new ECPoint(xy[0], xy[1]);
        // see ECConstants re: casting
        ECPublicKeySpec ks = new ECPublicKeySpec(w, (ECParameterSpec) type.getParams());
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(ks);
    }

    private static ECPrivateKey cvtToJavaECKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        SigType type = pk.getType();
        byte[] b = pk.getData();
        BigInteger s = new NativeBigInteger(1, b);
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
        byte[] b = combine(x, y, len);
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
     *  @return JAVA EdDSA public key!
     *  @since 0.9.15
     */
    public static EdDSAPublicKey toJavaEdDSAKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        EdDSAPublicKey rv;
        synchronized (_EdPubkeyCache) {
            rv = _EdPubkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaEdDSAKey(pk);
        synchronized (_EdPubkeyCache) {
            _EdPubkeyCache.put(pk, rv);
        }
        return rv;
    }

    /**
     *  @return JAVA EdDSA private key!
     *  @since 0.9.15
     */
    public static EdDSAPrivateKey toJavaEdDSAKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        EdDSAPrivateKey rv;
        synchronized (_EdPrivkeyCache) {
            rv = _EdPrivkeyCache.get(pk);
        }
        if (rv != null)
            return rv;
        rv = cvtToJavaEdDSAKey(pk);
        synchronized (_EdPrivkeyCache) {
            _EdPrivkeyCache.put(pk, rv);
        }
        return rv;
    }

    /**
     *  @since 0.9.15
     */
    private static EdDSAPublicKey cvtToJavaEdDSAKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        try {
            return new EdDSAPublicKey(new EdDSAPublicKeySpec(
                pk.getData(), (EdDSAParameterSpec) pk.getType().getParams()));
        } catch (IllegalArgumentException iae) {
            throw new InvalidKeyException(iae);
        }
    }

    /**
     *  @since 0.9.15
     */
    private static EdDSAPrivateKey cvtToJavaEdDSAKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        try {
            return new EdDSAPrivateKey(new EdDSAPrivateKeySpec(
                pk.getData(), (EdDSAParameterSpec) pk.getType().getParams()));
        } catch (IllegalArgumentException iae) {
            throw new InvalidKeyException(iae);
        }
    }

    /**
     *  @since 0.9.15
     */
    public static SigningPublicKey fromJavaKey(EdDSAPublicKey pk, SigType type)
            throws GeneralSecurityException {
        return new SigningPublicKey(type, pk.getAbyte());
    }

    /**
     *  @since 0.9.15
     */
    public static SigningPrivateKey fromJavaKey(EdDSAPrivateKey pk, SigType type)
            throws GeneralSecurityException {
        return new SigningPrivateKey(type, pk.getSeed());
    }

    public static DSAPublicKey toJavaDSAKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("DSA");
        // y p q g
        KeySpec ks = new DSAPublicKeySpec(new NativeBigInteger(1, pk.getData()),
                                            CryptoConstants.dsap,
                                            CryptoConstants.dsaq,
                                            CryptoConstants.dsag);
        return (DSAPublicKey) kf.generatePublic(ks);
    }

    public static DSAPrivateKey toJavaDSAKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("DSA");
        // x p q g
        KeySpec ks = new DSAPrivateKeySpec(new NativeBigInteger(1, pk.getData()),
                                            CryptoConstants.dsap,
                                            CryptoConstants.dsaq,
                                            CryptoConstants.dsag);
        return (DSAPrivateKey) kf.generatePrivate(ks);
    }

    public static SigningPublicKey fromJavaKey(DSAPublicKey pk)
                              throws GeneralSecurityException {
        BigInteger y = pk.getY();
        SigType type = SigType.DSA_SHA1;
        int len = type.getPubkeyLen();
        byte[] by = rectify(y, len);
        return new SigningPublicKey(type, by);
    }

    public static SigningPrivateKey fromJavaKey(DSAPrivateKey pk)
                              throws GeneralSecurityException {
        BigInteger x = pk.getX();
        SigType type = SigType.DSA_SHA1;
        int len = type.getPrivkeyLen();
        byte[] bx = rectify(x, len);
        return new SigningPrivateKey(type, bx);
    }

    /**
     *  @deprecated unused
     */
    public static RSAPublicKey toJavaRSAKey(SigningPublicKey pk)
                              throws GeneralSecurityException {
        SigType type = pk.getType();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        BigInteger n = new NativeBigInteger(1, pk.getData());
        BigInteger e = ((RSAKeyGenParameterSpec)type.getParams()).getPublicExponent();
        // modulus exponent
        KeySpec ks = new RSAPublicKeySpec(n, e);
        return (RSAPublicKey) kf.generatePublic(ks);
    }

    /**
     *  As of 0.9.31, if pk is a RSASigningPrivateCrtKey,
     *  this will return a RSAPrivateCrtKey.
     */
    public static RSAPrivateKey toJavaRSAKey(SigningPrivateKey pk)
                              throws GeneralSecurityException {
        if (pk instanceof RSASigningPrivateCrtKey)
            return ((RSASigningPrivateCrtKey) pk).toJavaKey();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        // private key is modulus (pubkey) + exponent
        BigInteger[] nd = split(pk.getData());
        // modulus exponent
        KeySpec ks = new RSAPrivateKeySpec(nd[0], nd[1]);
        return (RSAPrivateKey) kf.generatePrivate(ks);
    }

    /**
     *
     */
    public static SigningPublicKey fromJavaKey(RSAPublicKey pk, SigType type)
                              throws GeneralSecurityException {
        BigInteger n = pk.getModulus();
        int len = type.getPubkeyLen();
        byte[] bn = rectify(n, len);
        return new SigningPublicKey(type, bn);
    }

    /**
     *  As of 0.9.31, if pk is a RSAPrivateCrtKey,
     *  this will return a RSASigningPrivateCrtKey.
     */
    public static SigningPrivateKey fromJavaKey(RSAPrivateKey pk, SigType type)
                              throws GeneralSecurityException {
        // private key is modulus (pubkey) + exponent
        BigInteger n = pk.getModulus();
        BigInteger d = pk.getPrivateExponent();
        byte[] b = combine(n, d, type.getPrivkeyLen());
        if (pk instanceof RSAPrivateCrtKey)
            return RSASigningPrivateCrtKey.fromJavaKey((RSAPrivateCrtKey) pk);
        return new SigningPrivateKey(type, b);
    }

    /**
     *  @return ASN.1 representation
     */
    public static byte[] toJavaSig(Signature sig) {
        // RSA and EdDSA sigs are not ASN encoded
        if (sig.getType().getBaseAlgorithm() == SigAlgo.RSA || sig.getType().getBaseAlgorithm() == SigAlgo.EdDSA)
            return sig.getData();
        return sigBytesToASN1(sig.getData());
    }

    /**
     *  @param asn ASN.1 representation
     *  @return a Signature with SigType type
     */
    public static Signature fromJavaSig(byte[] asn, SigType type)
                              throws SignatureException {
        // RSA and EdDSA sigs are not ASN encoded
        if (type.getBaseAlgorithm() == SigAlgo.RSA || type.getBaseAlgorithm() == SigAlgo.EdDSA)
            return new Signature(type, asn);
        return new Signature(type, aSN1ToSigBytes(asn, type.getSigLen()));
    }

    /**
     *  @return JAVA key!
     */
    public static PublicKey importJavaPublicKey(File file, SigType type)
                              throws GeneralSecurityException, IOException {
        byte[] data = getData(file);
        KeySpec ks = new X509EncodedKeySpec(data);
        String algo = type.getBaseAlgorithm().getName();
        KeyFactory kf = KeyFactory.getInstance(algo);
        return kf.generatePublic(ks);
    }

    /**
     *  @return JAVA key!
     */
    public static PrivateKey importJavaPrivateKey(File file, SigType type)
                              throws GeneralSecurityException, IOException {
        byte[] data = getData(file);
        KeySpec ks = new PKCS8EncodedKeySpec(data);
        String algo = type.getBaseAlgorithm().getName();
        KeyFactory kf = KeyFactory.getInstance(algo);
        return kf.generatePrivate(ks);
    }

    /** 16 KB max */
    private static byte[] getData(File file) throws IOException {
        byte buf[] = new byte[1024];
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try {
            in = new FileInputStream(file);
            int read = 0;
            int tot = 0;
            while ( (read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                tot += read;
                if (tot > 16*1024)
                    throw new IOException("too big");
            }
            return out.toByteArray();
        } finally {
            if (in != null) 
                try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Split a byte array into two BigIntegers
     *  @param b length must be even
     *  @return array of two BigIntegers
     *  @since 0.9.9
     */
    private static NativeBigInteger[] split(byte[] b) {
        if ((b.length & 0x01) != 0)
            throw new IllegalArgumentException("length must be even");
        int sublen = b.length / 2;
        byte[] bx = new byte[sublen];
        byte[] by = new byte[sublen];
        System.arraycopy(b, 0, bx, 0, sublen);
        System.arraycopy(b, sublen, by, 0, sublen);
        NativeBigInteger x = new NativeBigInteger(1, bx);
        NativeBigInteger y = new NativeBigInteger(1, by);
        return new NativeBigInteger[] {x, y};
    }

    /**
     *  Combine two BigIntegers of nominal length = len / 2
     *  @return array of exactly len bytes
     *  @since 0.9.9, package private since 0.9.31
     */
    static byte[] combine(BigInteger x, BigInteger y, int len)
                              throws InvalidKeyException {
        if ((len & 0x01) != 0)
            throw new InvalidKeyException("length must be even");
        int sublen = len / 2;
        byte[] b = new byte[len];
        byte[] bx = rectify(x, sublen);
        byte[] by = rectify(y, sublen);
        System.arraycopy(bx, 0, b, 0, sublen);
        System.arraycopy(by, 0, b, sublen, sublen);
        return b;
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
     *<pre>
     *  Signature Format: ASN.1 sequence of two INTEGER values: r and s, in that order:
     *                                SEQUENCE ::= { r INTEGER, s INTEGER }
     *
     *  http://en.wikipedia.org/wiki/Abstract_Syntax_Notation_One
     *  30 -- tag indicating SEQUENCE
     *  xx - length in octets
     *
     *  02 -- tag indicating INTEGER
     *  xx - length in octets
     *  xxxxxx - value
     *</pre>
     *
     *  Convert to BigInteger and back so we have the minimum length representation, as required.
     *  r and s are always non-negative.
     *
     *  Only supports sigs up to about 252 bytes. See code to fix BER encoding for this before you
     *  add a SigType with bigger signatures.
     *
     *  @param sig length must be even
     *  @throws IllegalArgumentException if too big
     *  @since 0.8.7, moved to SigUtil in 0.9.9
     */
    private static byte[] sigBytesToASN1(byte[] sig) {
        BigInteger[] rs = split(sig);
        return sigBytesToASN1(rs[0], rs[1]);
    }

    /**
     *  http://download.oracle.com/javase/1.5.0/docs/guide/security/CryptoSpec.html
     *<pre>
     *  Signature Format: ASN.1 sequence of two INTEGER values: r and s, in that order:
     *                                SEQUENCE ::= { r INTEGER, s INTEGER }
     *
     *  http://en.wikipedia.org/wiki/Abstract_Syntax_Notation_One
     *  30 -- tag indicating SEQUENCE
     *  xx - length in octets
     *
     *  02 -- tag indicating INTEGER
     *  xx - length in octets
     *  xxxxxx - value
     *</pre>
     *
     *  r and s are always non-negative.
     *
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for this before you
     *  add a SigType with bigger signatures.
     *
     *  @throws IllegalArgumentException if too big
     *  @since 0.9.25, split out from sigBytesToASN1(byte[])
     */
    public static byte[] sigBytesToASN1(BigInteger r, BigInteger s) {
        int extra = 4;
        byte[] rb = r.toByteArray();
        if (rb.length > 127) {
            extra++;
            if (rb.length > 255)
                extra++;
        }
        byte[] sb = s.toByteArray();
        if (sb.length > 127) {
            extra++;
            if (sb.length > 255)
                extra++;
        }
        int seqlen = rb.length + sb.length + extra;
        int totlen = seqlen + 2;
        if (seqlen > 127) {
            totlen++;
            if (seqlen > 255)
                totlen++;
        }
        byte[] rv = new byte[totlen];
        int idx = 0;

        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seqlen);

        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, rb.length);
        System.arraycopy(rb, 0, rv, idx, rb.length);
        idx += rb.length;

        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, sb.length);
        System.arraycopy(sb, 0, rv, idx, sb.length);

        //System.out.println("post TO asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    /**
     *  Output an length or integer value in ASN.1
     *  Does NOT output the tag e.g. 0x02 / 0x30
     *
     *  @param val 0-65535
     *  @return the new index
     *  @since 0.9.25
     */
    public static int intToASN1(byte[] d, int idx, int val) {
        if (val < 0 || val > 65535)
            throw new IllegalArgumentException("fixme length " + val);
        if (val > 127) {
            if (val > 255) {
                d[idx++] = (byte) 0x82;
                d[idx++] = (byte) (val >> 8);
            } else {
                d[idx++] = (byte) 0x81;
            }
        }
        d[idx++] = (byte) val;
        return idx;
    }

    /**
     *  See above.
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for bigger than that.
     *
     *  @param len must be even, twice the nominal length of each BigInteger
     *  @return len bytes, call split() on the result to get two BigIntegers
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
        if ((rlen & 0x80) != 0) {
            if ((rlen & 0xff) == 0x81) { 
                rlen = asn[++idx] & 0xff;
            } else if ((rlen & 0xff) == 0x82) {
                rlen = asn[++idx] & 0xff;
                rlen <<= 8;
                rlen |= asn[++idx] & 0xff;
            } else {
                throw new SignatureException("FIXME R length > 65535");
            }
        }
        if ((asn[++idx] & 0x80) != 0)
            throw new SignatureException("R is negative");
        if (rlen > sublen + 1)
            throw new SignatureException("R too big " + rlen);
        if (rlen == sublen + 1)
            System.arraycopy(asn, idx + 1, rv, 0, sublen);
        else
            System.arraycopy(asn, idx, rv, sublen - rlen, rlen);
        idx += rlen;

        if (asn[idx] != 0x02)
            throw new SignatureException("asn[s] = " + (asn[idx] & 0xff));
        int slen = asn[++idx];
        if ((slen & 0x80) != 0) {
            if ((slen & 0xff) == 0x81) { 
                slen = asn[++idx] & 0xff;
            } else if ((slen & 0xff) == 0x82) {
                slen = asn[++idx] & 0xff;
                slen <<= 8;
                slen |= asn[++idx] & 0xff;
            } else {
                throw new SignatureException("FIXME S length > 65535");
            }
        }
        if ((asn[++idx] & 0x80) != 0)
            throw new SignatureException("S is negative");
        if (slen > sublen + 1)
            throw new SignatureException("S too big " + slen);
        if (slen == sublen + 1)
            System.arraycopy(asn, idx + 1, rv, sublen, sublen);
        else
            System.arraycopy(asn, idx, rv, len - slen, slen);
        //System.out.println("post from asn1\n" + net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    /**
     *  See above.
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for bigger than that.
     *
     *  @param len nominal length of each BigInteger
     *  @return two BigIntegers
     *  @since 0.9.25
     */
    public static NativeBigInteger[] aSN1ToBigInteger(byte[] asn, int len)
                              throws SignatureException {
        byte[] sig = aSN1ToSigBytes(asn, len * 2);
        return split(sig);
    }

    public static void clearCaches() {
        synchronized(_ECPubkeyCache) {
            _ECPubkeyCache.clear();
        }
        synchronized(_ECPrivkeyCache) {
            _ECPrivkeyCache.clear();
        }
        synchronized(_EdPubkeyCache) {
            _EdPubkeyCache.clear();
        }
        synchronized(_EdPrivkeyCache) {
            _EdPrivkeyCache.clear();
        }
    }
}
