package net.i2p.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.CRL;
import java.security.cert.CRLException;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Java X.509 certificate utilities, consolidated from various places.
 *
 *  @since 0.9.9
 */
public final class CertUtil {
        
    private static final String CERT_DIR = "certificates";
    private static final String REVOCATION_DIR = "revocations";
    private static final int LINE_LENGTH = 64;

    /**
     *  Write a certificate to a file in base64 format.
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static boolean saveCert(Certificate cert, File file) {
        OutputStream os = null;
        try {
           os = new SecureFileOutputStream(file);
           exportCert(cert, os);
           return true;
        } catch (CertificateEncodingException cee) {
            error("Error writing X509 Certificate " + file.getAbsolutePath(), cee);
           return false;
        } catch (IOException ioe) {
            error("Error writing X509 Certificate " + file.getAbsolutePath(), ioe);
           return false;
        } finally {
            try { if (os != null) os.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Writes the private key and all certs in base64 format.
     *  Does NOT close the stream. Throws on all errors.
     *
     *  @param pk non-null
     *  @param certs certificate chain, null or empty to export pk only
     *  @throws InvalidKeyException if the key does not support encoding
     *  @throws CertificateEncodingException if a cert does not support encoding
     *  @since 0.9.24
     */
    public static void exportPrivateKey(PrivateKey pk, Certificate[] certs, OutputStream out)
                                                throws IOException, GeneralSecurityException {
        exportPrivateKey(pk, out);
        if (certs == null)
            return;
        for (int i = 0; i < certs.length; i++) {
            exportCert(certs[i], out);
        }
    }

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  Writes a certificate in base64 format.
     *  Does NOT close the stream. Throws on all errors.
     *
     *  @since 0.9.24, pulled out of saveCert(), public since 0.9.25
     */
    public static void exportCert(Certificate cert, OutputStream out)
                                                throws IOException, CertificateEncodingException {
        // Get the encoded form which is suitable for exporting
        byte[] buf = cert.getEncoded();
        writePEM(buf, "CERTIFICATE", out);
    }

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  Writes a private key in base64 format.
     *  Does NOT close the stream. Throws on all errors.
     *
     *  @throws InvalidKeyException if the key does not support encoding
     *  @since 0.9.24
     */
    private static void exportPrivateKey(PrivateKey pk, OutputStream out)
                                                throws IOException, InvalidKeyException {
        // Get the encoded form which is suitable for exporting
        byte[] buf = pk.getEncoded();
        if (buf == null)
            throw new InvalidKeyException("encoding unsupported for this key");
        writePEM(buf, "PRIVATE KEY", out);
    }

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  Writes data in base64 format.
     *  Does NOT close the stream. Throws on all errors.
     *
     *  @since 0.9.25 consolidated from other methods
     */
    private static void writePEM(byte[] buf, String what, OutputStream out)
                                                throws IOException {
        PrintWriter wr = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
        wr.println("-----BEGIN " + what + "-----");
        String b64 = Base64.encode(buf, true);     // true = use standard alphabet
        for (int i = 0; i < b64.length(); i += LINE_LENGTH) {
            wr.println(b64.substring(i, Math.min(i + LINE_LENGTH, b64.length())));
        }
        wr.println("-----END " + what + "-----");
        wr.flush();
        if (wr.checkError())
            throw new IOException("Failed write to " + out);
    }

    /**
     *  Get the set of Subject Alternative Names, including
     *  DNSNames, RFC822Names, IPv4 and v6 addresses as strings.
     *
     *  see X509Certificate.getSubjectAlternativeNames()
     *
     *  @return non-null, empty on error or none found
     *  @since 0.9.34
     */
    public static Set<String> getSubjectAlternativeNames(X509Certificate cert) {
        Set<String> rv = new HashSet<String>(8);
        try {
            Collection<List<?>> c = cert.getSubjectAlternativeNames();
            if (c != null) {
                for (List<?> list : c) {
                    try {
                        rv.add((String) list.get(1));
                    } catch (ClassCastException cce) {}
                }
            }
        } catch(GeneralSecurityException gse) {}
        return rv;
    }

    /**
     *  Get a value out of the subject distinguished name.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     */
    public static String getSubjectValue(X509Certificate cert, String type) {
        X500Principal p = cert.getSubjectX500Principal();
        return getValue(p, type);
    }

    /**
     *  Get a value out of the issuer distinguished name.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     *  @since 0.9.24
     */
    public static String getIssuerValue(X509Certificate cert, String type) {
        X500Principal p = cert.getIssuerX500Principal();
        return getValue(p, type);
    }

    /**
     *  Get a value out of a X500Principal.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     */
    private static String getValue(X500Principal p, String type) {
        if (SystemVersion.isAndroid()) {
            error("Don't call this in Android", new UnsupportedOperationException("I did it"));
            return null;
        }
        if (p == null)
            return null;
        type = type.toUpperCase(Locale.US);
        String subj = p.getName();
        // Use reflection for this to avoid VerifyErrors on some Androids
        try {
            Class<?> ldapName = Class.forName("javax.naming.ldap.LdapName");
            Constructor<?> ldapCtor = ldapName.getConstructor(String.class);
            Object name = ldapCtor.newInstance(subj);
            Method getRdns = ldapName.getDeclaredMethod("getRdns");
            Class<?> rdnClass = Class.forName("javax.naming.ldap.Rdn");
            Method getType = rdnClass.getDeclaredMethod("getType");
            Method getValue = rdnClass.getDeclaredMethod("getValue");
            for (Object rdn : (List) getRdns.invoke(name)) {
                if (type.equals(((String) getType.invoke(rdn)).toUpperCase(Locale.US)))
                    return (String) getValue.invoke(rdn);
            }
        } catch (ClassNotFoundException e) {
        } catch (IllegalAccessException e) {
        } catch (InstantiationException e) {
        } catch (InvocationTargetException e) {
        } catch (NoSuchMethodException e) {
        }
        return null;
    }

    private static void error(String msg, Throwable t) {
        log(I2PAppContext.getGlobalContext(), Log.ERROR, msg, t);
    }

    //private static void error(I2PAppContext ctx, String msg, Throwable t) {
    //    log(ctx, Log.ERROR, msg, t);
    //}

    private static void log(I2PAppContext ctx, int level, String msg, Throwable t) {
        Log l = ctx.logManager().getLog(CertUtil.class);
        l.log(level, msg, t);
    }

    /**
     *  Get the Java public key from a X.509 certificate file.
     *  Throws if the certificate is invalid (e.g. expired).
     *
     *  This DOES check for revocation.
     *
     *  @return non-null, throws on all errors including certificate invalid
     *  @since 0.9.24 moved from SU3File private method
     */
    public static PublicKey loadKey(File kd) throws IOException, GeneralSecurityException {
        X509Certificate cert = loadCert(kd);
        if (isRevoked(cert))
            throw new CRLException("Certificate is revoked");
        return cert.getPublicKey();
    }

    /**
     *  Get the certificate from a X.509 certificate file.
     *  Throws if the certificate is invalid (e.g. expired).
     *
     *  This does NOT check for revocation.
     *
     *  @return non-null, throws on all errors including certificate invalid
     *  @since 0.9.24 adapted from SU3File private method
     */
    public static X509Certificate loadCert(File kd) throws IOException, GeneralSecurityException {
        InputStream fis = null;
        try {
            fis = new FileInputStream(kd);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
            cert.checkValidity();
            return cert;
        } catch (IllegalArgumentException iae) {
            // java 1.8.0_40-b10, openSUSE
            // Exception in thread "main" java.lang.IllegalArgumentException: Input byte array has wrong 4-byte ending unit
            // at java.util.Base64$Decoder.decode0(Base64.java:704)
            throw new GeneralSecurityException("cert error", iae);
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Get a single Private Key from an input stream.
     *  Does NOT close the stream.
     *
     *  @return non-null, non-empty, throws on all errors including certificate invalid
     *  @since 0.9.25
     */
    public static PrivateKey loadPrivateKey(InputStream in) throws IOException, GeneralSecurityException {
        try {
            String line;
            while ((line = DataHelper.readLine(in)) != null) {
                if (line.startsWith("---") && line.contains("BEGIN") && line.contains("PRIVATE"))
                    break;
            }
            if (line == null)
                throw new IOException("no private key found");
            StringBuilder buf = new StringBuilder(128);
            while ((line = DataHelper.readLine(in)) != null) {
                if (line.startsWith("---"))
                    break;
                buf.append(line.trim());
            }
            if (buf.length() <= 0)
                throw new IOException("no private key found");
            byte[] data = Base64.decode(buf.toString(), true);
            if (data == null)
                throw new CertificateEncodingException("bad base64 cert");
            PrivateKey rv = null;
            // try all the types
            for (SigAlgo algo : EnumSet.allOf(SigAlgo.class)) {
                try {
                    KeySpec ks = new PKCS8EncodedKeySpec(data);
                    String alg = algo.getName();
                    KeyFactory kf = KeyFactory.getInstance(alg);
                    rv = kf.generatePrivate(ks);
                    break;
                } catch (GeneralSecurityException gse) {
                    //gse.printStackTrace();
                }
            }
            if (rv == null)
                throw new InvalidKeyException("unsupported key type");
            return rv;
        } catch (IllegalArgumentException iae) {
            // java 1.8.0_40-b10, openSUSE
            // Exception in thread "main" java.lang.IllegalArgumentException: Input byte array has wrong 4-byte ending unit
            // at java.util.Base64$Decoder.decode0(Base64.java:704)
            throw new GeneralSecurityException("key error", iae);
        }
    }

    /**
     *  Get one or more certificates from an input stream.
     *  Throws if any certificate is invalid (e.g. expired).
     *  Does NOT close the stream.
     *
     *  This does NOT check for revocation.
     *
     *  @return non-null, non-empty, throws on all errors including certificate invalid
     *  @since 0.9.25
     */
    public static List<X509Certificate> loadCerts(InputStream in) throws IOException, GeneralSecurityException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            List<X509Certificate> rv = new ArrayList<X509Certificate>(certs.size());
            for (Certificate cert : certs) {
                if (!(cert instanceof X509Certificate))
                    throw new GeneralSecurityException("not a X.509 cert");
                X509Certificate xcert = (X509Certificate) cert;
                xcert.checkValidity();
                rv.add(xcert);
            }
            if (rv.isEmpty())
                throw new IOException("no certs found");
            return rv;
        } catch (IllegalArgumentException iae) {
            // java 1.8.0_40-b10, openSUSE
            // Exception in thread "main" java.lang.IllegalArgumentException: Input byte array has wrong 4-byte ending unit
            // at java.util.Base64$Decoder.decode0(Base64.java:704)
            throw new GeneralSecurityException("cert error", iae);
        } finally {
            try { in.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Write a CRL to a file in base64 format.
     *
     *  @return success
     *  @since 0.9.25
     */
    public static boolean saveCRL(X509CRL crl, File file) {
        OutputStream os = null;
        try {
           os = new SecureFileOutputStream(file);
           exportCRL(crl, os);
           return true;
        } catch (CRLException ce) {
            error("Error writing X509 CRL " + file.getAbsolutePath(), ce);
           return false;
        } catch (IOException ioe) {
            error("Error writing X509 CRL " + file.getAbsolutePath(), ioe);
           return false;
        } finally {
            try { if (os != null) os.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Writes a CRL in base64 format.
     *  Does NOT close the stream. Throws on all errors.
     *
     *  @throws CRLException if the crl does not support encoding
     *  @since 0.9.25
     */
    public static void exportCRL(X509CRL crl, OutputStream out)
                                                throws IOException, CRLException {
        byte[] buf = crl.getEncoded();
        writePEM(buf, "X509 CRL", out);
    }

    /**
     *  Is the certificate revoked?
     *  This loads the CRLs from disk.
     *  For efficiency, call loadCRLs() and then pass to isRevoked().
     *
     *  @since 0.9.25
     */
    public static boolean isRevoked(Certificate cert) {
        return isRevoked(I2PAppContext.getGlobalContext(), cert);
    }

    /**
     *  Is the certificate revoked?
     *  This loads the CRLs from disk.
     *  For efficiency, call loadCRLs() and then pass to isRevoked().
     *
     *  @since 0.9.25
     */
    public static boolean isRevoked(I2PAppContext ctx, Certificate cert) {
        CertStore store = loadCRLs(ctx);
        return isRevoked(store, cert);
    }

    /**
     *  Is the certificate revoked?
     *
     *  @since 0.9.25
     */
    public static boolean isRevoked(CertStore store, Certificate cert) {
        try {
            for (CRL crl : store.getCRLs(null)) {
                if (crl.isRevoked(cert))
                    return true;
            }
        } catch (GeneralSecurityException gse) {}
        return false;
    }

    /**
     *  Load CRLs from standard locations.
     *
     *  @return non-null, possibly empty
     *  @since 0.9.25
     */
    public static CertStore loadCRLs() {
        return loadCRLs(I2PAppContext.getGlobalContext());
    }

    /**
     *  Load CRLs from standard locations.
     *
     *  @return non-null, possibly empty
     *  @since 0.9.25
     */
    public static CertStore loadCRLs(I2PAppContext ctx) {
        Set<X509CRL> crls = new HashSet<X509CRL>(8);
        File dir = new File(ctx.getBaseDir(), CERT_DIR);
        dir = new File(dir, REVOCATION_DIR);
        loadCRLs(crls, dir);
        boolean diff = true;
        try {
            diff = !ctx.getBaseDir().getCanonicalPath().equals(ctx.getConfigDir().getCanonicalPath());
        } catch (IOException ioe) {}
        if (diff) {
            File dir2 = new File(ctx.getConfigDir(), CERT_DIR);
            dir2 = new File(dir2, REVOCATION_DIR);
            loadCRLs(crls, dir2);
        }
        //System.out.println("Loaded " + crls.size() + " CRLs");
        CollectionCertStoreParameters ccsp = new CollectionCertStoreParameters(crls);
        try {
            CertStore store = CertStore.getInstance("Collection", ccsp);
            return store;
        } catch (GeneralSecurityException gse) {
            // shouldn't happen
            error("CertStore", gse);
            throw new UnsupportedOperationException(gse);
        }
    }

    /**
     *  Load CRLs from the directory into the set.
     *
     *  @since 0.9.25
     */
    private static void loadCRLs(Set<X509CRL> crls, File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles(new FileSuffixFilter(".crl"));
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    try {
                        X509CRL crl = loadCRL(f);
                        crls.add(crl);
                    } catch (IOException ioe) {
                        error("Cannot load CRL from " + f, ioe);
                    } catch (GeneralSecurityException crle) {
                        error("Cannot load CRL from " + f, crle);
                    }
                }
            }
        }
    }

    /**
     *  Load a CRL.
     *
     *  @return non-null, possibly empty
     *  @since 0.9.25
     */
    private static X509CRL loadCRL(File file) throws IOException, GeneralSecurityException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return loadCRL(in);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Load a CRL. Does NOT Close the stream.
     *
     *  @return non-null
     *  @since 0.9.25 public since 0.9.26
     */
    public static X509CRL loadCRL(InputStream in) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509CRL) cf.generateCRL(in);
    }


    public static final void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: [loadcert | loadcrl | loadcrldir | loadcrldirs | isrevoked | loadprivatekey] file");
            System.exit(1);
        }
        try {
            File f = new File(args[1]);
            if (args[0].equals("loadcert")) {
                X509Certificate cert = loadCert(f);
                System.out.println(net.i2p.util.HexDump.dump(cert.getEncoded()));
            } else if (args[0].equals("loadcrl")) {
                loadCRL(f);
            } else if (args[0].equals("loadcrldir")) {
                Set<X509CRL> crls = new HashSet<X509CRL>(8);
                loadCRLs(crls, f);
                System.out.println("Found " + crls.size() + " CRLs");
            } else if (args[0].equals("loadcrldirs")) {
                CertStore store = loadCRLs(I2PAppContext.getGlobalContext());
                Collection<? extends CRL> crls = store.getCRLs(null);
                System.out.println("Found " + crls.size() + " CRLs");
            } else if (args[0].equals("isrevoked")) {
                Certificate cert = loadCert(f);
                boolean rv = isRevoked(I2PAppContext.getGlobalContext(), cert);
                System.out.println("Revoked? " + rv);
            } else {
                System.out.println("Usage: [loadcert | loadcrl | loadprivatekey] file");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
