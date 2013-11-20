package net.i2p.crypto;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Java X.509 certificate utilities, consolidated from various places.
 *
 *  @since 0.9.9
 */
public class CertUtil {
        
    private static final int LINE_LENGTH = 64;

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  This method writes a certificate to a file in base64 format.
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static boolean saveCert(Certificate cert, File file) {
        OutputStream os = null;
        try {
           // Get the encoded form which is suitable for exporting
           byte[] buf = cert.getEncoded();
           os = new SecureFileOutputStream(file);
           PrintWriter wr = new PrintWriter(os);
           wr.println("-----BEGIN CERTIFICATE-----");
           String b64 = Base64.encode(buf, true);     // true = use standard alphabet
           for (int i = 0; i < b64.length(); i += LINE_LENGTH) {
               wr.println(b64.substring(i, Math.min(i + LINE_LENGTH, b64.length())));
           }
           wr.println("-----END CERTIFICATE-----");
           wr.flush();
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
     *  Get a value out of the subject distinguished name
     *  @param type e.g. "CN"
     *  @return value or null if not found
     */
    public static String getSubjectValue(X509Certificate cert, String type) {
        type = type.toUpperCase(Locale.US);
        X500Principal p = cert.getSubjectX500Principal();
        String subj = p.getName();
        try {
            LdapName name = new LdapName(subj);
            for (Rdn rdn : name.getRdns()) {
                if (type.equals(rdn.getType().toUpperCase(Locale.US)))
                    return (String) rdn.getValue();
            }
        } catch (InvalidNameException ine) {}
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
}
