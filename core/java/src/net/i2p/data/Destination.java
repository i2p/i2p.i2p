package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.util.Log;

/**
 * Defines an end point in the I2P network.  The Destination may move aroundn
 * in the network, but messages sent to the Destination will find it
 *
 * @author jrandom
 */
public class Destination extends DataStructureImpl {
    private final static Log _log = new Log(Destination.class);
    private Certificate _certificate;
    private SigningPublicKey _signingKey;
    private PublicKey _publicKey;
    private Hash __calculatedHash;

    public Destination() {
        setCertificate(null);
        setSigningPublicKey(null);
        setPublicKey(null);
        __calculatedHash = null;
    }

    public Certificate getCertificate() {
        return _certificate;
    }

    public void setCertificate(Certificate cert) {
        _certificate = cert;
        __calculatedHash = null;
    }

    public PublicKey getPublicKey() {
        return _publicKey;
    }

    public void setPublicKey(PublicKey key) {
        _publicKey = key;
        __calculatedHash = null;
    }

    public SigningPublicKey getSigningPublicKey() {
        return _signingKey;
    }

    public void setSigningPublicKey(SigningPublicKey key) {
        _signingKey = key;
        __calculatedHash = null;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _publicKey = new PublicKey();
        _publicKey.readBytes(in);
        _signingKey = new SigningPublicKey();
        _signingKey.readBytes(in);
        _certificate = new Certificate();
        _certificate.readBytes(in);
        __calculatedHash = null;
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_certificate == null) || (_publicKey == null) || (_signingKey == null))
            throw new DataFormatException("Not enough data to format the destination");
        _publicKey.writeBytes(out);
        _signingKey.writeBytes(out);
        _certificate.writeBytes(out);
    }

    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof Destination)) return false;
        Destination dst = (Destination) object;
        return DataHelper.eq(getCertificate(), dst.getCertificate())
               && DataHelper.eq(getSigningPublicKey(), dst.getSigningPublicKey())
               && DataHelper.eq(getPublicKey(), dst.getPublicKey());
    }

    public int hashCode() {
        return DataHelper.hashCode(getCertificate()) + DataHelper.hashCode(getSigningPublicKey())
               + DataHelper.hashCode(getPublicKey());
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("[Destination: ");
        buf.append("\n\tHash: ").append(calculateHash().toBase64());
        buf.append("\n\tPublic Key: ").append(getPublicKey());
        buf.append("\n\tSigning Public Key: ").append(getSigningPublicKey());
        buf.append("\n\tCertificate: ").append(getCertificate());
        buf.append("]");
        return buf.toString();
    }

    public Hash calculateHash() {
        if (__calculatedHash == null) __calculatedHash = super.calculateHash();
        return __calculatedHash;
    }
}