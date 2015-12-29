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
import java.util.Arrays;

import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;

/**
 * KeysAndCert has a public key, a signing key, and a certificate.
 * In that order.
 * We also store a cached Hash.
 *
 * Implemented in 0.8.2 and retrofitted over Destination and RouterIdentity.
 * There's actually no difference between the two of them.
 *
 * As of 0.9.9 this data structure is immutable after the two keys and the certificate
 * are set; attempts to change them will throw an IllegalStateException.
 *
 * @since 0.8.2
 * @author zzz
 */
public class KeysAndCert extends DataStructureImpl {
    protected PublicKey _publicKey;
    protected SigningPublicKey _signingKey;
    protected Certificate _certificate;
    private Hash __calculatedHash;
    protected byte[] _padding;

    public Certificate getCertificate() {
        return _certificate;
    }

    /**
     * @throws IllegalStateException if was already set
     */
    public void setCertificate(Certificate cert) {
        if (_certificate != null)
            throw new IllegalStateException();
        _certificate = cert;
    }

    /**
     *  @return null if not set or unknown
     *  @since 0.9.17
     */
    public SigType getSigType() {
        if (_certificate == null)
            return null;
        if (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            try {
                KeyCertificate kcert = _certificate.toKeyCertificate();
                return kcert.getSigType();
            } catch (DataFormatException dfe) {}
        }
        return SigType.DSA_SHA1;
    }

    public PublicKey getPublicKey() {
        return _publicKey;
    }

    /**
     * @throws IllegalStateException if was already set
     */
    public void setPublicKey(PublicKey key) {
        if (_publicKey != null)
            throw new IllegalStateException();
        _publicKey = key;
    }

    public SigningPublicKey getSigningPublicKey() {
        return _signingKey;
    }

    /**
     * @throws IllegalStateException if was already set
     */
    public void setSigningPublicKey(SigningPublicKey key) {
        if (_signingKey != null)
            throw new IllegalStateException();
        _signingKey = key;
    }
    
    /**
     * @since 0.9.16
     */
    public byte[] getPadding() {
        return _padding;
    }
    
    /**
     * @throws IllegalStateException if was already set
     * @since 0.9.12
     */
    public void setPadding(byte[] padding) {
        if (_padding != null)
            throw new IllegalStateException();
        _padding = padding;
    }
    
    /**
     * @throws IllegalStateException if data already set
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_publicKey != null || _signingKey != null || _certificate != null)
            throw new IllegalStateException();
        _publicKey = PublicKey.create(in);
        SigningPublicKey spk = SigningPublicKey.create(in);
        Certificate  cert = Certificate.create(in);
        if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            // convert SPK to new SPK and padding
            KeyCertificate kcert = cert.toKeyCertificate();
            _signingKey = spk.toTypedKey(kcert);
            _padding = spk.getPadding(kcert);
            _certificate = kcert;
        } else {
            _signingKey = spk;
            _certificate = cert;
        }
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_certificate == null) || (_publicKey == null) || (_signingKey == null))
            throw new DataFormatException("Not enough data to format the router identity");
        _publicKey.writeBytes(out);
        if (_padding != null)
            out.write(_padding);
        else if (_signingKey.length() < SigningPublicKey.KEYSIZE_BYTES)
            throw new DataFormatException("No padding set");
        _signingKey.writeTruncatedBytes(out);
        _certificate.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof KeysAndCert)) return false;
        KeysAndCert  ident = (KeysAndCert) object;
        return
               DataHelper.eq(_signingKey, ident._signingKey)
               && DataHelper.eq(_publicKey, ident._publicKey)
               && Arrays.equals(_padding, ident._padding)
               && DataHelper.eq(_certificate, ident._certificate);
    }
    
    /** the signing key has enough randomness in it to use it by itself for speed */
    @Override
    public int hashCode() {
        // don't use public key, some app devs thinking of using
        // an all-zeros or leading-zeros public key for destinations
        if (_signingKey == null)
            return 0;
        return _signingKey.hashCode();
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append('[').append(getClass().getSimpleName()).append(": ");
        buf.append("\n\tHash: ").append(getHash().toBase64());
        buf.append("\n\tCertificate: ").append(_certificate);
        buf.append("\n\tPublicKey: ").append(_publicKey);
        buf.append("\n\tSigningPublicKey: ").append(_signingKey);
        if (_padding != null)
            buf.append("\n\tPadding: ").append(_padding.length).append(" bytes");
        buf.append(']');
        return buf.toString();
    }
    
    /**
     *  Throws IllegalStateException if keys and cert are not initialized,
     *  as of 0.9.12. Prior to that, returned null.
     *
     *  @throws IllegalStateException
     */
    @Override
    public Hash calculateHash() {
        return getHash();
    }

    /**
     *  Throws IllegalStateException if keys and cert are not initialized,
     *  as of 0.9.12. Prior to that, returned null.
     *
     *  @throws IllegalStateException
     */
    public Hash getHash() {
        if (__calculatedHash != null)
            return __calculatedHash;
        byte identBytes[];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(400);
            writeBytes(baos);
            identBytes = baos.toByteArray();
        } catch (IOException ioe) {
            throw new IllegalStateException("KAC hash error", ioe);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("KAC hash error", dfe);
        }
        __calculatedHash = SHA256Generator.getInstance().calculateHash(identBytes);
        return __calculatedHash;
    }
}
