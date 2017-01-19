package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.CRLException;
import java.security.cert.X509Certificate;

import net.i2p.util.SystemVersion;

/**
 *  Simple storage of each cert in a separate file in a directory.
 *  Limited sanitization of filenames.
 *
 *  @since 0.9.9, public since 0.9.28
 */
public class DirKeyRing implements KeyRing {

    private final File _base;

    public DirKeyRing(File baseDir) {
        _base = baseDir;
    }

    /**
     *  Cert must be in the file (escaped keyName).crt,
     *  and have a CN == keyName.
     *
     *  This DOES do a revocation check.
     *
     *  CN check unsupported on Android.
     *
     *  @return null if file doesn't exist, throws on all other errors
     */
    public PublicKey getKey(String keyName, String scope, SigType type)
                            throws GeneralSecurityException, IOException {
        String fileName = keyName.replace("@", "_at_").replace("<", "_").replace(">", "_");
        File test = new File(fileName);
        if (test.getParent() != null)
            throw new IOException("bad key name");
        File sd = new File(_base, scope);
        //File td = new File(sd, Integer.toString(type.getCode()));
        File kd = new File(sd, fileName + ".crt");
        if (!kd.exists())
            return null;
        X509Certificate cert = CertUtil.loadCert(kd);
        if (CertUtil.isRevoked(cert))
            throw new CRLException("Certificate is revoked");
        if (!SystemVersion.isAndroid()) {
            // getSubjectValue() unsupported on Android.
            // Any cert problems will be caught in non-Android testing.
            String cn = CertUtil.getSubjectValue(cert, "CN");
            if (!keyName.equals(cn))
                throw new GeneralSecurityException("CN mismatch: " + cn);
        }
        return cert.getPublicKey();
    }

    /**
     *  Unimplemented, unused.
     */
    public void setKey(String keyName, String scope, PublicKey key) {}
}
