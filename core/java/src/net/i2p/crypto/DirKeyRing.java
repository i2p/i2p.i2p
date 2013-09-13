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
import java.security.cert.X509Certificate;

import net.i2p.data.SigningPublicKey;

/**
 *  Dumb storage in a directory for testing.
 *  No sanitization of filenames, unsafe.
 *
 *  @since 0.9.9
 */
class DirKeyRing implements KeyRing {

    private final File _base;

    public DirKeyRing(File baseDir) {
        _base = baseDir;
    }

    public SigningPublicKey getKey(String keyName, String scope, SigType type)
                            throws GeneralSecurityException, IOException {
        keyName = keyName.replace("@", "_at_");
        File test = new File(keyName);
        if (test.getParent() != null)
            throw new IOException("bad key name");
        File sd = new File(_base, scope);
        //File td = new File(sd, Integer.toString(type.getCode()));
        File kd = new File(sd, keyName + ".crt");
        if (!kd.exists())
            return null;
        InputStream fis = null;
        try {
            fis = new FileInputStream(kd);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
            PublicKey pk = cert.getPublicKey();
            return SigUtil.fromJavaKey(pk, type);
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
    }

    public void setKey(String keyName, String scope, SigningPublicKey key) {}
}
