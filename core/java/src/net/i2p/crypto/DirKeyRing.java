package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

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
        File sd = new File(_base, scope);
        File td = new File(sd, Integer.toString(type.getCode()));
        File kd = new File(td, keyName + ".key");
        if (!kd.exists())
            return null;
        PublicKey pk = SigUtil.importJavaPublicKey(kd, type);
        return SigUtil.fromJavaKey(pk, type);
    }

    public void setKey(String keyName, String scope, SigningPublicKey key) {}
}
