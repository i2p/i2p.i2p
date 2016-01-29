package net.i2p.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.security.auth.x500.X500Principal;

import static net.i2p.crypto.SigUtil.intToASN1;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 *  Generate keys and a selfsigned certificate, suitable for
 *  storing in a Keystore with KeyStoreUtil.storePrivateKey().
 *  All done programatically, no keytool, no BC libs, no sun classes.
 *  Ref: RFC 2459
 *
 *  This is coded to create a cert that matches what comes out of keytool
 *  exactly, even if I don't understand all of it.
 *
 *  @since 0.9.25
 */
public final class SelfSignedGenerator {

    private static final String OID_CN = "2.5.4.3";
    private static final String OID_C = "2.5.4.6";
    private static final String OID_L = "2.5.4.7";
    private static final String OID_ST = "2.5.4.8";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_OU = "2.5.4.11";
    // Subject Key Identifier
    private static final String OID_SKI = "2.5.29.14";
    //private static final String OID_RSA_4096_PUB = "1.2.840.113549.1.1.1";
    // TODO put these in SigType
    private static final String OID_DSA_1024_SIG = "1.2.840.10040.4.3";
    private static final String OID_ECDSA_P256_SIG = "1.2.840.10045.4.3.2";
    private static final String OID_ECDSA_P384_SIG = "1.2.840.10045.4.3.3";
    private static final String OID_ECDSA_P521_SIG = "1.2.840.10045.4.3.4";
    private static final String OID_RSA_2048_SIG = "1.2.840.113549.1.1.11";
    private static final String OID_RSA_3072_SIG = "1.2.840.113549.1.1.12";
    private static final String OID_RSA_4096_SIG = "1.2.840.113549.1.1.13";

    private static final Map<String, String> OIDS;
    static {
        OIDS = new HashMap<String, String>(16);
        OIDS.put(OID_CN, "CN");
        OIDS.put(OID_C, "C");
        OIDS.put(OID_L, "L");
        OIDS.put(OID_ST, "ST");
        OIDS.put(OID_O, "O");
        OIDS.put(OID_OU, "OU");
        OIDS.put(OID_SKI, "SKI");
    }

    /**
     *  rv[0] is a Java PublicKey
     *  rv[1] is a Java PrivateKey
     *  rv[2] is a Java X509Certificate
     */
    public static Object[] generate(String cname, String ou, String o, String l, String st, String c,
                             int validDays, SigType type) throws GeneralSecurityException {
        SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(type);
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        PublicKey jpub = SigUtil.toJavaKey(pub);
        PrivateKey jpriv = SigUtil.toJavaKey(priv);

        // TODO just put the oid in the sigtype
        String oid;
        switch (type) {
            case DSA_SHA1:
                oid = OID_DSA_1024_SIG;
                break;
            case ECDSA_SHA256_P256:
                oid = OID_ECDSA_P256_SIG;
                break;
            case ECDSA_SHA384_P384:
                oid = OID_ECDSA_P384_SIG;
                break;
            case ECDSA_SHA512_P521:
                oid = OID_ECDSA_P521_SIG;
                break;
            case RSA_SHA256_2048:
                oid = OID_RSA_2048_SIG;
                break;
            case RSA_SHA384_3072:
                oid = OID_RSA_3072_SIG;
                break;
            case RSA_SHA512_4096:
                oid = OID_RSA_4096_SIG;
                break;
            default:
                throw new GeneralSecurityException("Unsupported: " + type);
        }
        byte[] sigoid = getEncodedOIDSeq(oid);

        byte[] tbs = genTBS(cname, ou, o, l, st, c, validDays, sigoid, jpub);
        int tbslen = tbs.length;

        Signature sig = DSAEngine.getInstance().sign(tbs, priv);
        if (sig == null)
            throw new GeneralSecurityException("sig failed");
        byte[] sigbytes= SigUtil.toJavaSig(sig);

        int seqlen = tbslen + sigoid.length + spaceFor(sigbytes.length + 1);
        int totlen = spaceFor(seqlen);
        byte[] cb = new byte[totlen];
        int idx = 0;

        // construct the whole encoded cert
        cb[idx++] = 0x30;
        idx = intToASN1(cb, idx, seqlen);

        // TBS cert
        System.arraycopy(tbs, 0, cb, idx, tbs.length);
        idx += tbs.length;

        // sig algo
        System.out.println("Sig OID");
        System.out.println(net.i2p.util.HexDump.dump(sigoid));
        System.arraycopy(sigoid, 0, cb, idx, sigoid.length);
        idx += sigoid.length;

        // sig (bit string)
        System.out.println("Signature");
        System.out.println(net.i2p.util.HexDump.dump(sigbytes));
        cb[idx++] = 0x03;
        idx = intToASN1(cb, idx, sigbytes.length + 1);
        cb[idx++] = 0;
        System.arraycopy(sigbytes, 0, cb, idx, sigbytes.length);

        System.out.println("Whole cert");
        System.out.println(net.i2p.util.HexDump.dump(cb));
        ByteArrayInputStream bais = new ByteArrayInputStream(cb);

        X509Certificate cert;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate)cf.generateCertificate(bais);
            cert.checkValidity();
        } catch (IllegalArgumentException iae) {
            throw new GeneralSecurityException("cert error", iae);
        }

        Object[] rv = { jpub, jpriv, cert };
        return rv;
    }

    private static byte[] genTBS(String cname, String ou, String o, String l, String st, String c,
                          int validDays, byte[] sigoid, PublicKey jpub) throws GeneralSecurityException {
        // a0 ???, int = 2
        byte[] version = { (byte) 0xa0, 3, 2, 1, 2 };

        // postive serial number (int)
        byte[] serial = new byte[6];
        serial[0] = 2;
        serial[1] = 4;
        RandomSource.getInstance().nextBytes(serial, 2, 4);
        serial[2] &= 0x7f;

        // going to use this for both issuer and subject
        String dname = "CN=" + cname + ",OU=" + ou + ",O=" + o + ",L=" + l + ",ST=" + st + ",C=" + c;
        byte[] issuer = (new X500Principal(dname, OIDS)).getEncoded();
        byte[] validity = getValidity(validDays);
        byte[] subject = issuer;

        byte[] pubbytes = jpub.getEncoded();
        byte[] extbytes = getExtensions(pubbytes);

        int len = version.length + serial.length + sigoid.length + issuer.length +
                  validity.length + subject.length + pubbytes.length + extbytes.length;

        int totlen = spaceFor(len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, len);
        System.out.println(net.i2p.util.HexDump.dump(version));
        System.arraycopy(version, 0, rv, idx, version.length);
        idx += version.length;
        System.out.println("serial");
        System.out.println(net.i2p.util.HexDump.dump(serial));
        System.arraycopy(serial, 0, rv, idx, serial.length);
        idx += serial.length;
        System.out.println("oid");
        System.out.println(net.i2p.util.HexDump.dump(sigoid));
        System.arraycopy(sigoid, 0, rv, idx, sigoid.length);
        idx += sigoid.length;
        System.out.println("issuer");
        System.out.println(net.i2p.util.HexDump.dump(issuer));
        System.arraycopy(issuer, 0, rv, idx, issuer.length);
        idx += issuer.length;
        System.out.println("valid");
        System.out.println(net.i2p.util.HexDump.dump(validity));
        System.arraycopy(validity, 0, rv, idx, validity.length);
        idx += validity.length;
        System.out.println("subject");
        System.out.println(net.i2p.util.HexDump.dump(subject));
        System.arraycopy(subject, 0, rv, idx, subject.length);
        idx += subject.length;
        System.out.println("pub");
        System.out.println(net.i2p.util.HexDump.dump(pubbytes));
        System.arraycopy(pubbytes, 0, rv, idx, pubbytes.length);
        idx += pubbytes.length;
        System.out.println("extensions");
        System.out.println(net.i2p.util.HexDump.dump(extbytes));
        System.arraycopy(extbytes, 0, rv, idx, extbytes.length);

        System.out.println("TBS cert");
        System.out.println(net.i2p.util.HexDump.dump(rv));
        return rv;
    }

    /**
     *  @param val the length of the value, 65535 max
     *  @return the length of the TLV
     */
    private static int spaceFor(int val) {
        int rv;
        if (val > 255)
            rv = 3;
        else if (val > 127)
            rv = 2;
        else
            rv = 1;
        return 1 + rv + val;
    }

    /**
     *  @return 32 bytes ASN.1 encoded object
     */
    private static byte[] getValidity(int validDays) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        baos.write(0x30);
        // len to be filled in later
        baos.write(0);
        long now = System.currentTimeMillis();
        long then = now + (validDays * 24L * 60 * 60 * 1000);
        // UTCDate format (HH 0-23)
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss");
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        byte[] nowbytes = DataHelper.getASCII(fmt.format(new Date(now)));
        byte[] thenbytes = DataHelper.getASCII(fmt.format(new Date(then)));
        baos.write(0x17);
        baos.write(nowbytes.length + 1);
        baos.write(nowbytes, 0, nowbytes.length);
        baos.write('Z');
        baos.write(0x17);
        baos.write(thenbytes.length + 1);
        baos.write(thenbytes, 0, thenbytes.length);
        baos.write('Z');
        byte[] rv = baos.toByteArray();
        rv[1] = (byte) (rv.length - 2);
        return rv;
    }

    /**
     *
     *  @param pubbytes bit string
     *  @return 35 bytes ASN.1 encoded object
     */
    private static byte[] getExtensions(byte[] pubbytes) {
        // RFC 2549 sec. 4.2.1.2
        // subject public key identifier is the sha1 hash of the bit string of the public key
        // without the tag, length, and igore fields
        int pidx = 1;
        int skip = pubbytes[pidx++];
        if ((skip & 0x80)!= 0)
            pidx += skip & 0x80;
        pidx++; // ignore
        MessageDigest md = SHA1.getInstance();
        md.update(pubbytes, pidx, pubbytes.length - pidx);
        byte[] sha = md.digest();
        byte[] oid = getEncodedOID(OID_SKI);

        int wraplen = spaceFor(sha.length);
        int extlen = oid.length + spaceFor(wraplen);
        int extslen = spaceFor(extlen);
        int seqlen = spaceFor(extslen);
        int totlen = spaceFor(seqlen);
        byte[] rv = new byte[totlen];
        int idx = 0;
        rv[idx++] = (byte) 0xa3;
        idx = intToASN1(rv, idx, seqlen);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, extslen);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, extlen);
        System.arraycopy(oid, 0, rv, idx, oid.length);
        idx += oid.length;
        // don't know why we wrap the int in another int
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, wraplen);
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, sha.length);
        System.arraycopy(sha, 0, rv, idx, sha.length);
        return rv;
    }

    /**
     *  0x30 len 0x06 len encodedbytes... 0x05 0
     *  @return ASN.1 encoded object
     *  @throws IllegalArgumentException
     */
    private static byte[] getEncodedOIDSeq(String oid) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16);
        baos.write(0x30);
        // len to be filled in later
        baos.write(0);
        byte[] b = getEncodedOID(oid);
        baos.write(b, 0, b.length);
        // NULL
        baos.write(0x05);
        baos.write(0);
        byte[] rv = baos.toByteArray();
        rv[1] = (byte) (rv.length - 2);
        return rv;
    }

    /**
     *  0x06 len encodedbytes...
     *  @return ASN.1 encoded object
     *  @throws IllegalArgumentException
     */
    private static byte[] getEncodedOID(String oid) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16);
        baos.write(0x06);
        // len to be filled in later
        baos.write(0);
        String[] f = DataHelper.split(oid, "[.]");
        if (f.length < 2)
            throw new IllegalArgumentException("length: " + f.length);
        baos.write((40 * Integer.parseInt(f[0])) + Integer.parseInt(f[1]));
        for (int i = 2; i < f.length; i++) {
            int v = Integer.parseInt(f[i]);
            if (v >= 128 * 128 * 128 || v < 0)
                throw new IllegalArgumentException();
            if (v >= 128 * 128)
                baos.write((v >> 14) | 0x80);
            if (v >= 128)
                baos.write((v >> 7) | 0x80);
            baos.write(v & 0x7f);
        }
        byte[] rv = baos.toByteArray();
        if (rv.length > 129)
            throw new IllegalArgumentException();
        rv[1] = (byte) (rv.length - 2);
        return rv;
    }

/****
    public static void main(String[] args) {
        try {
            test("test0", SigType.DSA_SHA1);
            test("test1", SigType.ECDSA_SHA256_P256);
            test("test2", SigType.ECDSA_SHA384_P384);
            test("test3", SigType.ECDSA_SHA512_P521);
            test("test4", SigType.RSA_SHA256_2048);
            test("test5", SigType.RSA_SHA384_3072);
            test("test6", SigType.RSA_SHA512_4096);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final void test(String name, SigType type) throws Exception {
            Object[] rv = generate("cname", "ou", "l", "o", "st", "c", 3652, type);
            PublicKey jpub = (PublicKey) rv[0];
            PrivateKey jpriv = (PrivateKey) rv[1];
            X509Certificate cert = (X509Certificate) rv[2];
            File ks = new File(name + ".ks");
            List<X509Certificate> certs = new ArrayList<X509Certificate>(1);
            certs.add(cert);
            KeyStoreUtil.storePrivateKey(ks, "changeit", "foo", "foobar", jpriv, certs);
            System.out.println("Private key saved to " + ks + " with alias foo, password foobar, keystore password changeit");
            File cf = new File(name + ".crt");
            CertUtil.saveCert(cert, cf);
            System.out.println("Certificate saved to " + cf);
    }
****/
}
