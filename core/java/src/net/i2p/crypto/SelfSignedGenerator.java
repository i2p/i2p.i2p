package net.i2p.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

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
import net.i2p.util.Addresses;
import net.i2p.util.HexDump;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Generate keys and a selfsigned certificate, suitable for
 *  storing in a Keystore with KeyStoreUtil.storePrivateKey().
 *  All done programatically, no keytool, no BC libs, no sun classes.
 *  Ref: RFC 2459, RFC 5280
 *
 *  This is coded to create a cert that is similar to what comes out of keytool.
 *
 *  NOTE: Recommended use is via KeyStoreUtil.createKeys() and related methods.
 *  This API may not be stable.
 *
 *  @since 0.9.25
 */
public final class SelfSignedGenerator {

    private static final boolean DEBUG = false;

    // Policy Qualifier CPS URI
    private static final String OID_QT_CPSURI = "1.3.6.1.5.5.7.2.1";
    // Policy Qualifier User Notice
    private static final String OID_QT_UNOTICE = "1.3.6.1.5.5.7.2.2";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_C = "2.5.4.6";
    private static final String OID_L = "2.5.4.7";
    private static final String OID_ST = "2.5.4.8";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_OU = "2.5.4.11";
    // Subject Key Identifier
    private static final String OID_SKI = "2.5.29.14";
    // Key Usage
    private static final String OID_USAGE = "2.5.29.15";
    // Subject Alternative Name
    private static final String OID_SAN = "2.5.29.17";
    // Basic Constraints
    private static final String OID_BASIC = "2.5.29.19";
    // CRL number
    private static final String OID_CRLNUM = "2.5.29.20";
    // Certificate Policy
    private static final String OID_POLICY = "2.5.29.32";
    // Certificate Policy - Any
    private static final String OID_POLICY_ANY = "2.5.29.32.0";
    // Authority Key Identifier
    private static final String OID_AKI = "2.5.29.35";

    private static final Map<String, String> OIDS;
    static {
        OIDS = new HashMap<String, String>(16);
        // only OIDs in the distinguished name need to be in here
        OIDS.put(OID_CN, "CN");
        OIDS.put(OID_C, "C");
        OIDS.put(OID_L, "L");
        OIDS.put(OID_ST, "ST");
        OIDS.put(OID_O, "O");
        OIDS.put(OID_OU, "OU");
    }

    /**
     *  @param cname the common name, non-null. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param ou The OU (organizational unit) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param o The O (organization)in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param l The L (city or locality) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param st The ST (state or province) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param c The C (country) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *
     *  @return length 4 array:
     *  rv[0] is a Java PublicKey
     *  rv[1] is a Java PrivateKey
     *  rv[2] is a Java X509Certificate
     *  rv[3] is a Java X509CRL
     */
    public static Object[] generate(String cname, String ou, String o, String l, String st, String c,
                             int validDays, SigType type) throws GeneralSecurityException {
        return generate(cname, null, ou, o, l, st, c, validDays, type);
    }

    /**
     *  @param cname the common name, non-null. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou The OU (organizational unit) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param o The O (organization)in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param l The L (city or locality) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param st The ST (state or province) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param c The C (country) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *
     *  @return length 4 array:
     *  rv[0] is a Java PublicKey
     *  rv[1] is a Java PrivateKey
     *  rv[2] is a Java X509Certificate
     *  rv[3] is a Java X509CRL
     *
     *  @since 0.9.34 added altNames param
     */
    public static Object[] generate(String cname, Set<String> altNames, String ou, String o, String l, String st, String c,
                             int validDays, SigType type) throws GeneralSecurityException {
        SimpleDataStructure[] keys = KeyGenerator.getInstance().generateSigningKeys(type);
        SigningPublicKey pub = (SigningPublicKey) keys[0];
        SigningPrivateKey priv = (SigningPrivateKey) keys[1];
        PublicKey jpub = SigUtil.toJavaKey(pub);
        PrivateKey jpriv = SigUtil.toJavaKey(priv);
        return generate(jpub, jpriv, priv, type, cname, altNames, ou, o, l, st, c, validDays);
    }

    /**
     *  @param cname the common name, non-null. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou The OU (organizational unit) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param o The O (organization)in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param l The L (city or locality) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param st The ST (state or province) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param c The C (country) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *
     *  @return length 4 array:
     *  rv[0] is a Java PublicKey
     *  rv[1] is a Java PrivateKey
     *  rv[2] is a Java X509Certificate
     *  rv[3] is a Java X509CRL
     *
     *  @since 0.9.34 added altNames param
     */
    private static Object[] generate(PublicKey jpub, PrivateKey jpriv, SigningPrivateKey priv, SigType type,
                                     String cname, Set<String> altNames, String ou, String o, String l, String st, String c,
                                     int validDays) throws GeneralSecurityException {
        String oid;
        switch (type) {
            case DSA_SHA1:
            case ECDSA_SHA256_P256:
            case ECDSA_SHA384_P384:
            case ECDSA_SHA512_P521:
            case RSA_SHA256_2048:
            case RSA_SHA384_3072:
            case RSA_SHA512_4096:
            case EdDSA_SHA512_Ed25519:
            case EdDSA_SHA512_Ed25519ph:
                oid = type.getOID();
                break;
            default:
                throw new GeneralSecurityException("Unsupported: " + type);
        }
        byte[] sigoid = getEncodedOIDSeq(oid);

        byte[] tbs = genTBS(cname, altNames, ou, o, l, st, c, validDays, sigoid, jpub);
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
        System.arraycopy(sigoid, 0, cb, idx, sigoid.length);
        idx += sigoid.length;

        // sig (bit string)
        cb[idx++] = 0x03;
        idx = intToASN1(cb, idx, sigbytes.length + 1);
        cb[idx++] = 0;
        System.arraycopy(sigbytes, 0, cb, idx, sigbytes.length);

        if (DEBUG) {
            System.out.println("Sig OID");
            System.out.println(HexDump.dump(sigoid));
            System.out.println("Signature");
            System.out.println(HexDump.dump(sigbytes));
            System.out.println("Whole cert");
            System.out.println(HexDump.dump(cb));
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(cb);

        X509Certificate cert;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate)cf.generateCertificate(bais);
            cert.checkValidity();
        } catch (IllegalArgumentException iae) {
            throw new GeneralSecurityException("cert error", iae);
        }
        X509CRL crl = generateCRL(cert, validDays, 1, sigoid, jpriv);

        // some simple tests
        PublicKey cpub = cert.getPublicKey();
        cert.verify(cpub);
        if (!cpub.equals(jpub))
            throw new GeneralSecurityException("pubkey mismatch");
        // todo crl tests

        Object[] rv = { jpub, jpriv, cert, crl };
        return rv;
    }

    /**
     *  @param cert the old cert to be replaced
     *  @param jpriv the private key
     *
     *  @return length 4 array:
     *  rv[0] is a Java PublicKey, from cert as passed in
     *  rv[1] is a Java PrivateKey, jpriv as passed in
     *  rv[2] is a Java X509Certificate, new one
     *  rv[3] is a Java X509CRL, new one
     *
     *  @since 0.9.34 added altNames param
     */
    public static Object[] renew(X509Certificate cert, PrivateKey jpriv, int validDays) throws GeneralSecurityException {
        String cname = CertUtil.getSubjectValue(cert, "CN");
        if (cname == null)
            cname = "localhost";
        String ou = CertUtil.getSubjectValue(cert, "OU");
        String o = CertUtil.getSubjectValue(cert, "O");
        String l = CertUtil.getSubjectValue(cert, "L");
        String st = CertUtil.getSubjectValue(cert, "ST");
        String c = CertUtil.getSubjectValue(cert, "C");
        Set<String> altNames = CertUtil.getSubjectAlternativeNames(cert);
        SigningPrivateKey priv = SigUtil.fromJavaKey(jpriv);
        SigType type = priv.getType();
        SigningPublicKey pub = KeyGenerator.getSigningPublicKey(priv);
        PublicKey jpub = SigUtil.toJavaKey(pub);
        if (type == null)
                throw new GeneralSecurityException("Unsupported: " + jpriv);
        return generate(jpub, jpriv, priv, type, cname, altNames, ou, o, l, st, c, validDays);
    }

    /**
     *  Generate a CRL for the given cert, signed with the given private key
     */
    private static X509CRL generateCRL(X509Certificate cert, int validDays, int crlNum,
                                       byte[] sigoid, PrivateKey jpriv) throws GeneralSecurityException {

        SigningPrivateKey priv = SigUtil.fromJavaKey(jpriv);

        byte[] tbs = genTBSCRL(cert, validDays, crlNum, sigoid);
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
        System.arraycopy(sigoid, 0, cb, idx, sigoid.length);
        idx += sigoid.length;

        // sig (bit string)
        cb[idx++] = 0x03;
        idx = intToASN1(cb, idx, sigbytes.length + 1);
        cb[idx++] = 0;
        System.arraycopy(sigbytes, 0, cb, idx, sigbytes.length);

     /****
        if (DEBUG) {
            System.out.println("CRL Sig OID");
            System.out.println(HexDump.dump(sigoid));
            System.out.println("CRL Signature");
            System.out.println(HexDump.dump(sigbytes));
            System.out.println("Whole CRL");
            System.out.println(HexDump.dump(cb));
        }
      ****/

        ByteArrayInputStream bais = new ByteArrayInputStream(cb);

        X509CRL rv;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            // wow, unlike for x509Certificates, there's no validation here at all
            // ASN.1 errors don't cause any exceptions
            rv = (X509CRL)cf.generateCRL(bais);
        } catch (IllegalArgumentException iae) {
            throw new GeneralSecurityException("cert error", iae);
        }

        return rv;
    }

    /**
     *  @param cname the common name, non-null
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou The OU (organizational unit) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param o The O (organization)in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param l The L (city or locality) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param st The ST (state or province) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     *  @param c The C (country) in the distinguished name, non-null before 0.9.28, may be null as of 0.9.28
     */
    private static byte[] genTBS(String cname, Set<String> altNames, String ou, String o, String l, String st, String c,
                          int validDays, byte[] sigoid, PublicKey jpub) throws GeneralSecurityException {
        // a0 ???, int = 2
        byte[] version = { (byte) 0xa0, 3, 2, 1, 2 };

        // positive serial number (long)
        byte[] serial = new byte[10];
        serial[0] = 2;
        serial[1] = 8;
        RandomSource.getInstance().nextBytes(serial, 2, 8);
        serial[2] &= 0x7f;

        // going to use this for both issuer and subject
        StringBuilder buf = new StringBuilder(128);
        buf.append("CN=").append(cname);
        if (ou != null)
            buf.append(",OU=").append(ou);
        if (o != null)
            buf.append(",O=").append(o);
        if (l != null)
            buf.append(",L=").append(l);
        if (st != null)
            buf.append(",ST=").append(st);
        if (c != null)
            buf.append(",C=").append(c);
        String dname = buf.toString();
        byte[] issuer = (new X500Principal(dname, OIDS)).getEncoded();
        byte[] validity = getValidity(validDays);
        byte[] subject = issuer;

        byte[] pubbytes = jpub.getEncoded();
        byte[] extbytes = getExtensions(pubbytes, cname, altNames);

        int len = version.length + serial.length + sigoid.length + issuer.length +
                  validity.length + subject.length + pubbytes.length + extbytes.length;

        int totlen = spaceFor(len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, len);
        System.arraycopy(version, 0, rv, idx, version.length);
        idx += version.length;
        System.arraycopy(serial, 0, rv, idx, serial.length);
        idx += serial.length;
        System.arraycopy(sigoid, 0, rv, idx, sigoid.length);
        idx += sigoid.length;
        System.arraycopy(issuer, 0, rv, idx, issuer.length);
        idx += issuer.length;
        System.arraycopy(validity, 0, rv, idx, validity.length);
        idx += validity.length;
        System.arraycopy(subject, 0, rv, idx, subject.length);
        idx += subject.length;
        System.arraycopy(pubbytes, 0, rv, idx, pubbytes.length);
        idx += pubbytes.length;
        System.arraycopy(extbytes, 0, rv, idx, extbytes.length);

        if (DEBUG) {
            System.out.println(HexDump.dump(version));
            System.out.println("serial");
            System.out.println(HexDump.dump(serial));
            System.out.println("oid");
            System.out.println(HexDump.dump(sigoid));
            System.out.println("issuer");
            System.out.println(HexDump.dump(issuer));
            System.out.println("valid");
            System.out.println(HexDump.dump(validity));
            System.out.println("subject");
            System.out.println(HexDump.dump(subject));
            System.out.println("pub");
            System.out.println(HexDump.dump(pubbytes));
            System.out.println("extensions");
            System.out.println(HexDump.dump(extbytes));
            System.out.println("TBS cert");
            System.out.println(HexDump.dump(rv));
        }
        return rv;
    }

    /**
     *
     *  @param crlNum 0-255 because lazy
     *  @return ASN.1 encoded object
     */
    private static byte[] genTBSCRL(X509Certificate cert, int validDays,
                                    int crlNum, byte[] sigalg) throws GeneralSecurityException {
        // a0 ???, int = 2
        byte[] version = { 2, 1, 1 };
        byte[] issuer = cert.getIssuerX500Principal().getEncoded();

        byte[] serial = cert.getSerialNumber().toByteArray();
        if (serial.length > 255)
            throw new IllegalArgumentException();
        // backdate to allow for clock skew
        long now = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
        long then = now + ((validDays + 1) * 24L * 60 * 60 * 1000);
        // used for CRL time and revocation time
        byte[] nowbytes = getDate(now);
        // used for next CRL time
        byte[] thenbytes = getDate(then);

        byte[] extbytes = getCRLExtensions(crlNum);

        int revlen = 2 + serial.length + nowbytes.length;
        int revseqlen = spaceFor(revlen);
        int revsseqlen = spaceFor(revseqlen);


        int len = version.length + sigalg.length + issuer.length + nowbytes.length +
                  thenbytes.length + revsseqlen + extbytes.length;

        int totlen = spaceFor(len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, len);
        System.arraycopy(version, 0, rv, idx, version.length);
        idx += version.length;
        System.arraycopy(sigalg, 0, rv, idx, sigalg.length);
        idx += sigalg.length;
        System.arraycopy(issuer, 0, rv, idx, issuer.length);
        idx += issuer.length;
        System.arraycopy(nowbytes, 0, rv, idx, nowbytes.length);
        idx += nowbytes.length;
        System.arraycopy(thenbytes, 0, rv, idx, thenbytes.length);
        idx += thenbytes.length;
        // the certs
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, revseqlen);
        // the cert
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, revlen);
        rv[idx++] = 0x02;
        rv[idx++] = (byte) serial.length;
        System.arraycopy(serial, 0, rv, idx, serial.length);
        idx += serial.length;
        System.arraycopy(nowbytes, 0, rv, idx, nowbytes.length);
        idx += nowbytes.length;
        // extensions
        System.arraycopy(extbytes, 0, rv, idx, extbytes.length);

        if (DEBUG) {
            System.out.println("version");
            System.out.println(HexDump.dump(version));
            System.out.println("sigalg");
            System.out.println(HexDump.dump(sigalg));
            System.out.println("issuer");
            System.out.println(HexDump.dump(issuer));
            System.out.println("now");
            System.out.println(HexDump.dump(nowbytes));
            System.out.println("then");
            System.out.println(HexDump.dump(thenbytes));
            System.out.println("serial");
            System.out.println(HexDump.dump(serial));
            System.out.println("extensions");
            System.out.println(HexDump.dump(extbytes));
            System.out.println("TBS CRL");
            System.out.println(HexDump.dump(rv));
        }
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
     *  Sequence of two UTCDates
     *  @return 32 bytes ASN.1 encoded object
     */
    private static byte[] getValidity(int validDays) {
        byte[] rv = new byte[32];
        rv[0] = 0x30;
        rv[1] = 30;
        // backdate to allow for clock skew
        long now = System.currentTimeMillis() - (24L * 60 * 60 * 1000);
        long then = now + ((validDays + 1) * 24L * 60 * 60 * 1000);
        byte[] nowbytes = getDate(now);
        byte[] thenbytes = getDate(then);
        System.arraycopy(nowbytes, 0, rv, 2, 15);
        System.arraycopy(thenbytes, 0, rv, 17, 15);
        return rv;
    }

    /**
     *  A single UTCDate
     *  @return 15 bytes ASN.1 encoded object
     */
    private static byte[] getDate(long now) {
        // UTCDate format (HH 0-23)
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss");
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        byte[] nowbytes = DataHelper.getASCII(fmt.format(new Date(now)));
        if (nowbytes.length != 12)
            throw new IllegalArgumentException();
        byte[] rv = new byte[15];
        rv[0] = 0x17;
        rv[1] = 13;
        System.arraycopy(nowbytes, 0, rv, 2, 12);
        rv[14] = (byte) 'Z';
        return rv;
    }

    /**
     *  Add the following extensions:
     *   1) Subject Key Identifier
     *   2) Key Usage
     *   3) Basic Constraints
     *   4) Subject Alternative Name
     *      As of 0.9.34, adds 127.0.0.1 and ::1 to the SAN also
     *   5) Authority Key Identifier
     *  (not necessarily output in that order)
     *
     *  Ref: RFC 5280
     *
     *  @param pubbytes bit string
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @return ASN.1 encoded object
     */
    private static byte[] getExtensions(byte[] pubbytes, String cname, Set<String> altNames) {
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
        byte[] oid1 = getEncodedOID(OID_SKI);
        byte[] oid2 = getEncodedOID(OID_USAGE);
        byte[] oid3 = getEncodedOID(OID_BASIC);
        byte[] oid4 = getEncodedOID(OID_SAN);
        byte[] oid5 = getEncodedOID(OID_AKI);
        byte[] oid6 = getEncodedOID(OID_POLICY);
        byte[] oid7 = getEncodedOID(OID_POLICY_ANY);
        byte[] oid8 = getEncodedOID(OID_QT_UNOTICE);
        byte[] oid9 = getEncodedOID(OID_QT_CPSURI);
        byte[] TRUE = new byte[] { 1, 1, (byte) 0xff };

        // extXlen does NOT include the 0x30 and length

        int wrap1len = spaceFor(sha.length);
        int ext1len = oid1.length + spaceFor(wrap1len);

        int wrap2len = 4;
        int ext2len = oid2.length + TRUE.length + spaceFor(wrap2len);

        int wrap3len = spaceFor(TRUE.length);
        int ext3len = oid3.length + TRUE.length + spaceFor(wrap3len);

        int wrap41len = 0;
        // SEQUENCE doesn't have to be sorted, but let's do it for consistency,
        // so it's platform-independent and the same after renewal
        if (altNames == null) {
            altNames = new TreeSet<String>();
        } else {
            altNames = new TreeSet<String>(altNames);
            altNames.remove("0:0:0:0:0:0:0:1");  // We don't want dup of "::1"
        }
        altNames.add(cname);
        final boolean isCA = !cname.contains("@") && !cname.endsWith(".family.i2p.net");
        if (isCA) {
            altNames.add("localhost");
            altNames.add("127.0.0.1");
            altNames.add("::1");
        }
        for (String n : altNames) {
            int len;
            if (Addresses.isIPv4Address(n))
                len = 4;
            else if (Addresses.isIPv6Address(n))
                len = 16;
            else
                len = n.length();
            wrap41len += spaceFor(len);
        }
        int wrap4len = spaceFor(wrap41len);
        int ext4len = oid4.length + spaceFor(wrap4len);

        int wrap51len = wrap1len;
        int wrap5len = spaceFor(wrap51len);
        int ext5len = oid5.length + spaceFor(wrap5len);

        byte[] policyTextBytes = DataHelper.getASCII("This self-signed certificate is required for secure local access to I2P services.");
        byte[] policyURIBytes = DataHelper.getASCII("https://geti2p.net/");
        int wrap61len = spaceFor(policyTextBytes.length); // usernotice ia5string
        int wrap62len = oid8.length + spaceFor(wrap61len); // PQ 1 Info OID + usernotice seq.
        int wrap63len = spaceFor(policyURIBytes.length); // uri ia5string
        int wrap64len = oid9.length + wrap63len; // PQ 2 Info OID + ia5string
        int wrap65len = spaceFor(wrap62len) + spaceFor(wrap64len); // qualifiers seq
        int wrap66len = spaceFor(oid7.length + wrap65len); // PInfo elements seq
        int wrap67len = spaceFor(wrap66len); // PInfo seq
        int wrap68len = spaceFor(wrap67len); // Policies seq
        int ext6len = oid6.length + spaceFor(wrap68len); // OID + octet string

        int extslen = spaceFor(ext1len) + spaceFor(ext2len) + spaceFor(ext4len) + spaceFor(ext5len);
        if (isCA)
            extslen += spaceFor(ext3len) + spaceFor(ext6len);
        int seqlen = spaceFor(extslen);
        int totlen = spaceFor(seqlen);
        byte[] rv = new byte[totlen];
        int idx = 0;

        rv[idx++] = (byte) 0xa3;
        idx = intToASN1(rv, idx, seqlen);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, extslen);

        // Subject Key Identifier
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, ext1len);
        System.arraycopy(oid1, 0, rv, idx, oid1.length);
        idx += oid1.length;
        // octet string wraps an octet string
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, wrap1len);
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, sha.length);
        System.arraycopy(sha, 0, rv, idx, sha.length);
        idx += sha.length;

        // Authority Key Identifier
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, ext5len);
        System.arraycopy(oid5, 0, rv, idx, oid5.length);
        idx += oid5.length;
        // octet string wraps a sequence containing a choice 0 (key identifier) byte string
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, wrap5len);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, wrap51len);
        rv[idx++] = (byte) 0x80; // choice
        idx = intToASN1(rv, idx, sha.length);
        System.arraycopy(sha, 0, rv, idx, sha.length);
        idx += sha.length;

        if (isCA) {
            // Basic Constraints (critical)
            rv[idx++] = (byte) 0x30;
            idx = intToASN1(rv, idx, ext3len);
            System.arraycopy(oid3, 0, rv, idx, oid3.length);
            idx += oid3.length;
            System.arraycopy(TRUE, 0, rv, idx, TRUE.length);
            idx += TRUE.length;
            // octet string wraps an sequence containing TRUE
            rv[idx++] = (byte) 0x04;
            idx = intToASN1(rv, idx, wrap3len);
            rv[idx++] = (byte) 0x30;
            idx = intToASN1(rv, idx, TRUE.length);
            System.arraycopy(TRUE, 0, rv, idx, TRUE.length);
            idx += TRUE.length;
        }

        // Key Usage (critical)
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, ext2len);
        System.arraycopy(oid2, 0, rv, idx, oid2.length);
        idx += oid2.length;
        System.arraycopy(TRUE, 0, rv, idx, TRUE.length);
        idx += TRUE.length;
        // octet string wraps a bit string
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, wrap2len);
        rv[idx++] = (byte) 0x03;
        rv[idx++] = (byte) 0x02;
        rv[idx++] = (byte) 0x01;
        rv[idx++] = (byte) 0xa6; // sig, key encipherment, cert, CRL

        // Subject Alternative Name
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, ext4len);
        System.arraycopy(oid4, 0, rv, idx, oid4.length);
        idx += oid4.length;
        // octet string wraps a sequence containing the names and IP addresses
        rv[idx++] = (byte) 0x04;
        idx = intToASN1(rv, idx, wrap4len);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, wrap41len);
        for (String n : altNames) {
            byte[] b;
            if (Addresses.isIPv4Address(n) ||
                Addresses.isIPv6Address(n)) {
                b = Addresses.getIP(n);
                if (b == null)  // shouldn't happen
                    throw new IllegalArgumentException("fail " + n);
                rv[idx++] = (byte) 0x87; // choice, octet string for IP address
            } else {
                b = DataHelper.getASCII(n);
                rv[idx++] = (byte) (isCA ? 0x82 : 0x81); // choice, dNSName or rfc822Name, IA5String implied
            }
            idx = intToASN1(rv, idx, b.length);
            System.arraycopy(b, 0, rv, idx, b.length);
            idx += b.length;
        }

        // Policy
        // https://www.sysadmins.lv/blog-en/certificate-policies-extension-all-you-should-know-part-1.aspx
        if (isCA) {
            rv[idx++] = (byte) 0x30;
            idx = intToASN1(rv, idx, ext6len);
            System.arraycopy(oid6, 0, rv, idx, oid6.length);
            idx += oid6.length;
            rv[idx++] = (byte) 0x04;  // octet string wraps a sequence
            idx = intToASN1(rv, idx, wrap68len);
            rv[idx++] = (byte) 0x30;  // Policies seq
            idx = intToASN1(rv, idx, wrap67len);
            rv[idx++] = (byte) 0x30;  // Policy info seq
            idx = intToASN1(rv, idx, wrap66len);
            System.arraycopy(oid7, 0, rv, idx, oid7.length);
            idx += oid7.length;
            rv[idx++] = (byte) 0x30;  // Policy qualifiers seq
            idx = intToASN1(rv, idx, wrap65len);

            // This should be what IE links to as "Issuer Statement"
            rv[idx++] = (byte) 0x30;  // Policy qualifier info 2 seq
            idx = intToASN1(rv, idx, wrap64len);
            System.arraycopy(oid9, 0, rv, idx, oid9.length);
            idx += oid9.length;
            rv[idx++] = (byte) 0x16;  // choice 0 URI ia5string
            idx = intToASN1(rv, idx, policyURIBytes.length);
            System.arraycopy(policyURIBytes, 0, rv, idx, policyURIBytes.length);
            idx += policyURIBytes.length;

            // User notice text
            rv[idx++] = (byte) 0x30;  // Policy qualifier info 1 seq
            idx = intToASN1(rv, idx, wrap62len);
            System.arraycopy(oid8, 0, rv, idx, oid8.length);
            idx += oid8.length;
            rv[idx++] = (byte) 0x30;  // choice 1 notice seq.
            idx = intToASN1(rv, idx, wrap61len);
            rv[idx++] = (byte) 0x16;  // choice 0 ia5string
            idx = intToASN1(rv, idx, policyTextBytes.length);
            System.arraycopy(policyTextBytes, 0, rv, idx, policyTextBytes.length);
            idx += policyTextBytes.length;
        }

        return rv;
    }

    /**
     *
     *  @param crlNum 0-255 because lazy
     *  @return 16 bytes ASN.1 encoded object
     */
    private static byte[] getCRLExtensions(int crlNum) {
        if (crlNum < 0 || crlNum > 255)
            throw new IllegalArgumentException();
        byte[] oid = getEncodedOID(OID_CRLNUM);
        int extlen = oid.length + 5;
        int extslen = spaceFor(extlen);
        int seqlen = spaceFor(extslen);
        int totlen = spaceFor(seqlen);
        byte[] rv = new byte[totlen];
        int idx = 0;
        rv[idx++] = (byte) 0xa0;
        idx = intToASN1(rv, idx, seqlen);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, extslen);
        rv[idx++] = (byte) 0x30;
        idx = intToASN1(rv, idx, extlen);
        System.arraycopy(oid, 0, rv, idx, oid.length);
        idx += oid.length;
        // don't know why we wrap the int in an octet string
        rv[idx++] = (byte) 0x04;
        rv[idx++] = (byte) 3;
        rv[idx++] = (byte) 0x02;
        rv[idx++] = (byte) 1;
        rv[idx++] = (byte) crlNum;
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

    /**
     *  Note: For CLI testing, use java -jar i2p.jar su3file keygen pubkey.crt keystore.ks commonName
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        } else if (args[0].equals("keygen")) {
            if (args.length >= 4)
                SU3File.main(args);
            else
                usage();
        } else if (args[0].equals("renew")) {
            if (args.length >= 3) {
                String ksPW, cert, ks;
                if (args[1].equals("-p")) {
                    ksPW = args[2];
                    cert = args[3];
                    ks = args[4];
                } else {
                    ksPW = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
                    cert = args[1];
                    ks = args[2];
                }
                String keypw = "";
                try {
                    while (keypw.length() < 6) {
                        System.out.print("Enter password for key: ");
                        keypw = DataHelper.readLine(System.in);
                        if (keypw == null) {
                            System.out.println("\nEOF reading password");
                            System.exit(1);
                        }
                        keypw = keypw.trim();
                        if (keypw.length() > 0 && keypw.length() < 6)
                            System.out.println("Key password must be at least 6 characters");
                    }
                } catch (IOException ioe) {
                    System.out.println("Error asking for password");
                    throw ioe;
                }
                File ksf = new File(ks);
                X509Certificate newCert = KeyStoreUtil.renewPrivateKeyCertificate(ksf, ksPW, null, keypw, 3652);
                CertUtil.saveCert(newCert, new File(cert));
                System.out.println("Certificate renewed for 10 years, and stored in " + cert + " and " + ks);
            } else {
                usage();
            }
        } else {
            usage();
        }
/****
        try {
            int i = 0;
            for (SigType t : java.util.EnumSet.allOf(SigType.class)) {
                if (t.isAvailable())
                    test("test" + i, t);
                i++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
****/
    }

    private static void usage() {
        System.err.println("Usage: selfsignedgenerator keygen [-t type|code] [-p keystorepw] [-r crlFile.crl] publicKeyFile.crt keystore.ks localhost\n" +
                           "       selfsignedgenerator renew  [-p keystorepw] publicKeyFile.crt keystore.ks");
    }
/****
    private static final void test(String name, SigType type) throws Exception {
            Object[] rv = generate("cname@example.com", "ou", "o", null, "st", "c", 3652, type);
            //PublicKey jpub = (PublicKey) rv[0];
            PrivateKey jpriv = (PrivateKey) rv[1];
            X509Certificate cert = (X509Certificate) rv[2];
            X509CRL crl = (X509CRL) rv[3];
            File ks = new File(name + ".ks");
            List<X509Certificate> certs = new ArrayList<X509Certificate>(1);
            certs.add(cert);
            KeyStoreUtil.storePrivateKey(ks, "changeit", "foo", "foobar", jpriv, certs);
            System.out.println("Private key saved to " + ks + " with alias foo, password foobar, keystore password changeit");
            File cf = new File(name + ".crt");
            CertUtil.saveCert(cert, cf);
            System.out.println("Certificate saved to " + cf);
            File pf = new File(name + ".priv");
            FileOutputStream pfs = new SecureFileOutputStream(pf);
            KeyStoreUtil.exportPrivateKey(ks, "changeit", "foo", "foobar", pfs);
            pfs.close();
            System.out.println("Private key saved to " + pf);
            File cr = new File(name + ".crl");
            CertUtil.saveCRL(crl, cr);
            System.out.println("CRL saved to " + cr);
    }
****/
}
