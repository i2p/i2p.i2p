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

/**
 * KeysAndCert has a public key, a signing key, and a certificate.
 * In that order.
 * We also store a cached Hash.
 *
 * Implemented in 0.8.2 and retrofitted over Destination and RouterIdentity.
 * There's actually no difference between the two of them.
 *
 * @since 0.8.2
 * @author zzz
 */
public class KeysAndCert extends DataStructureImpl {
    protected PublicKey _publicKey;
    protected SigningPublicKey _signingKey;
    protected Certificate _certificate;
    protected Hash __calculatedHash;

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
        //_publicKey = new PublicKey();
        //_publicKey.readBytes(in);
        _publicKey = PublicKey.create(in);
        //_signingKey = new SigningPublicKey();
        //_signingKey.readBytes(in);
        _signingKey = SigningPublicKey.create(in);
        //_certificate = new Certificate();
        //_certificate.readBytes(in);
        _certificate = Certificate.create(in);
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
        if ((object == null) || !(object instanceof KeysAndCert)) return false;
        KeysAndCert  ident = (KeysAndCert) object;
        return DataHelper.eq(_certificate, ident._certificate)
               && DataHelper.eq(_signingKey, ident._signingKey)
               && DataHelper.eq(_publicKey, ident._publicKey);
    }
    
    /** the public key has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        if (_publicKey == null)
            return 0;
        return _publicKey.hashCode();
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(": ");
        buf.append("\n\tHash: ").append(getHash().toBase64());
        buf.append("\n\tCertificate: ").append(_certificate);
        buf.append("\n\tPublicKey: ").append(_publicKey);
        buf.append("\n\tSigningPublicKey: ").append(_signingKey);
        buf.append(']');
        return buf.toString();
    }
    
    @Override
    public Hash calculateHash() {
        return getHash();
    }

    public Hash getHash() {
        if (__calculatedHash != null)
            return __calculatedHash;
        byte identBytes[];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeBytes(baos);
            identBytes = baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
        __calculatedHash = SHA256Generator.getInstance().calculateHash(identBytes);
        return __calculatedHash;
    }
}
