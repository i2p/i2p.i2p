package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.SHA256Generator;
import net.i2p.util.Log;

/**
 * Defines the unique identifier of a router, including any certificate or 
 * public key.
 *
 * @author jrandom
 */
public class RouterIdentity extends DataStructureImpl {
    private final static Log _log = new Log(RouterIdentity.class);
    private Certificate _certificate;
    private SigningPublicKey _signingKey;
    private PublicKey _publicKey;
    private Hash __calculatedHash;

    public RouterIdentity() {
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
    
    /** 
     * This router specified that they should not be used as a part of a tunnel,
     * nor queried for the netDb, and that disclosure of their contact information
     * should be limited.
     *
     */
    public boolean isHidden() {
        return (_certificate != null) && (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_HIDDEN);
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
            throw new DataFormatException("Not enough data to format the router identity");
        _publicKey.writeBytes(out);
        _signingKey.writeBytes(out);
        _certificate.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof RouterIdentity)) return false;
        RouterIdentity ident = (RouterIdentity) object;
        return DataHelper.eq(getCertificate(), ident.getCertificate())
               && DataHelper.eq(getSigningPublicKey(), ident.getSigningPublicKey())
               && DataHelper.eq(getPublicKey(), ident.getPublicKey());
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getCertificate()) + DataHelper.hashCode(getSigningPublicKey())
               + DataHelper.hashCode(getPublicKey());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[RouterIdentity: ");
        buf.append("\n\tHash: ").append(getHash().toBase64());
        buf.append("\n\tCertificate: ").append(getCertificate());
        buf.append("\n\tPublicKey: ").append(getPublicKey());
        buf.append("\n\tSigningPublicKey: ").append(getSigningPublicKey());
        buf.append("]");
        return buf.toString();
    }
    
    @Override
    public Hash calculateHash() {
        return getHash();
    }

    public Hash getHash() {
        if (__calculatedHash != null) {
        //_log.info("Returning cached ident hash");
        return __calculatedHash; }
        byte identBytes[] = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeBytes(baos);
            identBytes = baos.toByteArray();
        } catch (Throwable t) {
            _log.error("Error writing out hash");
            return null;
        }
        __calculatedHash = SHA256Generator.getInstance().calculateHash(identBytes);
        return __calculatedHash;
    }
}