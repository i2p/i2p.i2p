package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * Base client implementation
 *
 * @author jrandom
 */
class I2PClientImpl implements I2PClient {
    /**
     * Create the destination with a null payload
     */
    public Destination createDestination(OutputStream destKeyStream) throws I2PException, IOException {
        Certificate cert = new Certificate();
        cert.setCertificateType(Certificate.CERTIFICATE_TYPE_NULL);
        cert.setPayload(null);
        return createDestination(destKeyStream, cert);
    }

    /** 
     * Create the destination with the given payload and write it out along with
     * the PrivateKey and SigningPrivateKey to the destKeyStream
     *
     */
    public Destination createDestination(OutputStream destKeyStream, Certificate cert) throws I2PException, IOException {
        Destination d = new Destination();
        d.setCertificate(cert);
        Object keypair[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey publicKey = (PublicKey) keypair[0];
        PrivateKey privateKey = (PrivateKey) keypair[1];
        Object signingKeys[] = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey signingPubKey = (SigningPublicKey) signingKeys[0];
        SigningPrivateKey signingPrivKey = (SigningPrivateKey) signingKeys[1];
        d.setPublicKey(publicKey);
        d.setSigningPublicKey(signingPubKey);

        d.writeBytes(destKeyStream);
        privateKey.writeBytes(destKeyStream);
        signingPrivKey.writeBytes(destKeyStream);
        destKeyStream.flush();

        return d;
    }

    /**
     * Create a new session (though do not connect it yet)
     *
     */
    public I2PSession createSession(InputStream destKeyStream, Properties options) throws I2PSessionException {
        return createSession(I2PAppContext.getGlobalContext(), destKeyStream, options);
    }
    /**
     * Create a new session (though do not connect it yet)
     *
     */
    public I2PSession createSession(I2PAppContext context, InputStream destKeyStream, Properties options) throws I2PSessionException {
        return new I2PSessionMuxedImpl(context, destKeyStream, options); // thread safe and muxed
    }
}
