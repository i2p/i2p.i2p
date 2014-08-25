package net.i2p.data.router;


import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;

import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SigningPrivateKey;

/**
 *  Same format as super, simply adds a method to
 *  treat it as a RouterIdentity instead of a Destination.
 *
 *  @since 0.9.16
 */
public class RouterPrivateKeyFile extends PrivateKeyFile {
    
    public RouterPrivateKeyFile(File file) {
        super(file);
    }
    
    /**
     *  Read it in from the file.
     *  Also sets the local privKey and signingPrivKey.
     */
    public RouterIdentity getRouterIdentity() throws IOException, DataFormatException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(this.file));
            RouterIdentity ri = new RouterIdentity();
            ri.readBytes(in);
            privKey = new PrivateKey();
            privKey.readBytes(in);
            SigType type = ri.getSigningPublicKey().getType();
            if (type == null)
                throw new DataFormatException("Unknown sig type");
            signingPrivKey = new SigningPrivateKey(type);
            signingPrivKey.readBytes(in);

            // set it a Destination, so we may call validateKeyPairs()
            // or other methods
            dest = new Destination();
            dest.setPublicKey(ri.getPublicKey());
            dest.setSigningPublicKey(ri.getSigningPublicKey());
            dest.setCertificate(ri.getCertificate());
            dest.setPadding(ri.getPadding());

            return ri;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ioe) {}
            }
        }
    }
}
