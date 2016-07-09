package net.i2p.client.impl;

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
import java.security.GeneralSecurityException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.KeyCertificate;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.RandomSource;

/**
 * Base client implementation.
 * An I2PClient contains no state, it is just a facility for creating private key files
 * and generating sesssions from existing private key files.
 *
 * @author jrandom
 */
public class I2PClientImpl implements I2PClient {

    /**
     * Create a destination with a DSA 1024/160 signature type and a null certificate.
     * This is not bound to the I2PClient, you must supply the data back again
     * in createSession().
     *
     * Caller must close stream.
     *
     * @param destKeyStream location to write out the destination, PrivateKey, and SigningPrivateKey,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     */
    public Destination createDestination(OutputStream destKeyStream) throws I2PException, IOException {
        return createDestination(destKeyStream, DEFAULT_SIGTYPE);
    }

    /**
     * Create a destination with the given signature type.
     * It will have a null certificate for DSA 1024/160 and KeyCertificate otherwise.
     * This is not bound to the I2PClient, you must supply the data back again
     * in createSession().
     *
     * Caller must close stream.
     *
     * @param destKeyStream location to write out the destination, PrivateKey, and SigningPrivateKey,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @since 0.9.12
     */
    public Destination createDestination(OutputStream destKeyStream, SigType type) throws I2PException, IOException {
        Certificate cert;
        if (type == SigType.DSA_SHA1) {
            cert = Certificate.NULL_CERT;
        } else {
            cert = new KeyCertificate(type);
        }
        return createDestination(destKeyStream, cert);
    }

    /** 
     * Create the destination with the given payload and write it out along with
     * the PrivateKey and SigningPrivateKey to the destKeyStream
     *
     * If cert is a KeyCertificate, the signing keypair will be of the specified type.
     * The KeyCertificate data must be .............................
     * The padding if any will be randomized. The extra key data if any will be set in the
     * key cert.
     *
     * Caller must close stream.
     *
     * @param destKeyStream location to write out the destination, PrivateKey, and SigningPrivateKey,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     */
    public Destination createDestination(OutputStream destKeyStream, Certificate cert) throws I2PException, IOException {
        Destination d = new Destination();
        Object keypair[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey publicKey = (PublicKey) keypair[0];
        PrivateKey privateKey = (PrivateKey) keypair[1];
        SimpleDataStructure signingKeys[];
        if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            KeyCertificate kcert = cert.toKeyCertificate();
            SigType type = kcert.getSigType();
            try {
                signingKeys = KeyGenerator.getInstance().generateSigningKeys(type);
            } catch (GeneralSecurityException gse) {
                throw new I2PException("keygen fail", gse);
            }
        } else {
            signingKeys = KeyGenerator.getInstance().generateSigningKeys();
        }
        SigningPublicKey signingPubKey = (SigningPublicKey) signingKeys[0];
        SigningPrivateKey signingPrivKey = (SigningPrivateKey) signingKeys[1];
        d.setPublicKey(publicKey);
        d.setSigningPublicKey(signingPubKey);
        if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            // fix up key certificate or padding
            KeyCertificate kcert = cert.toKeyCertificate();
            SigType type = kcert.getSigType();
            int len = type.getPubkeyLen();
            if (len < 128) {
                byte[] pad = new byte[128 - len];
                RandomSource.getInstance().nextBytes(pad);
                d.setPadding(pad);
            } else if (len > 128) {
                System.arraycopy(signingPubKey.getData(), 128, kcert.getPayload(), KeyCertificate.HEADER_LENGTH, len - 128);
            }
        }
        d.setCertificate(cert);

        d.writeBytes(destKeyStream);
        privateKey.writeBytes(destKeyStream);
        signingPrivKey.writeBytes(destKeyStream);
        destKeyStream.flush();

        return d;
    }

    /**
     * Create a new session (though do not connect it yet)
     *
     * @param destKeyStream location from which to read the Destination, PrivateKey, and SigningPrivateKey from,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     */
    public I2PSession createSession(InputStream destKeyStream, Properties options) throws I2PSessionException {
        return createSession(I2PAppContext.getGlobalContext(), destKeyStream, options);
    }

    /**
     * Create a new session (though do not connect it yet)
     *
     * @param destKeyStream location from which to read the Destination, PrivateKey, and SigningPrivateKey from,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     */
    public I2PSession createSession(I2PAppContext context, InputStream destKeyStream, Properties options) throws I2PSessionException {
        return new I2PSessionMuxedImpl(context, destKeyStream, options); // thread safe and muxed
    }
}
