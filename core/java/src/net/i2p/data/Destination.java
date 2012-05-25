package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.util.Log;

/**
 * Defines an end point in the I2P network.  The Destination may move around
 * in the network, but messages sent to the Destination will find it
 *
 * @author jrandom
 */
public class Destination extends DataStructureImpl {
    protected final static Log _log = new Log(Destination.class);
    protected Certificate _certificate;
    protected SigningPublicKey _signingKey;
    protected PublicKey _publicKey;
    protected Hash __calculatedHash;

    public Destination() {
        setCertificate(null);
        setSigningPublicKey(null);
        setPublicKey(null);
        __calculatedHash = null;
    }

    /**
     * alternative constructor which takes a base64 string representation
     * @param s a Base64 representation of the destination, as (eg) is used in hosts.txt
     */
    public Destination(String s) throws DataFormatException {
        this();
        fromBase64(s);
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
    
    public int writeBytes(byte target[], int offset) {
        int cur = offset;
        System.arraycopy(_publicKey.getData(), 0, target, cur, PublicKey.KEYSIZE_BYTES);
        cur += PublicKey.KEYSIZE_BYTES;
        System.arraycopy(_signingKey.getData(), 0, target, cur, SigningPublicKey.KEYSIZE_BYTES);
        cur += SigningPublicKey.KEYSIZE_BYTES;
        cur += _certificate.writeBytes(target, cur);
        return cur - offset;
    }
    
    public int readBytes(byte source[], int offset) throws DataFormatException {
        if (source == null) throw new DataFormatException("Null source");
        if (source.length <= offset + PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES) 
            throw new DataFormatException("Not enough data (len=" + source.length + " off=" + offset + ")");
        int cur = offset;
        
        _publicKey = new PublicKey();
        byte buf[] = new byte[PublicKey.KEYSIZE_BYTES];
        System.arraycopy(source, cur, buf, 0, PublicKey.KEYSIZE_BYTES);
        _publicKey.setData(buf);
        cur += PublicKey.KEYSIZE_BYTES;
        
        _signingKey = new SigningPublicKey();
        buf = new byte[SigningPublicKey.KEYSIZE_BYTES];
        System.arraycopy(source, cur, buf, 0, SigningPublicKey.KEYSIZE_BYTES);
        _signingKey.setData(buf);
        cur += SigningPublicKey.KEYSIZE_BYTES;
        
        _certificate = new Certificate();
        cur += _certificate.readBytes(source, cur);
        
        return cur - offset;
    }

    public int size() {
        return PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES + _certificate.size();
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof Destination)) return false;
        Destination dst = (Destination) object;
        return DataHelper.eq(getCertificate(), dst.getCertificate())
               && DataHelper.eq(getSigningPublicKey(), dst.getSigningPublicKey())
               && DataHelper.eq(getPublicKey(), dst.getPublicKey());
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getCertificate()) + DataHelper.hashCode(getSigningPublicKey())
               + DataHelper.hashCode(getPublicKey());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[Destination: ");
        buf.append("\n\tHash: ").append(calculateHash().toBase64());
        buf.append("\n\tPublic Key: ").append(getPublicKey());
        buf.append("\n\tSigning Public Key: ").append(getSigningPublicKey());
        buf.append("\n\tCertificate: ").append(getCertificate());
        buf.append("]");
        return buf.toString();
    }
    
    @Override
    public Hash calculateHash() {
        if (__calculatedHash == null) __calculatedHash = super.calculateHash();
        return __calculatedHash;
    }
    
    public static void main(String args[]) {
        if (args.length == 0) {
            System.err.println("Usage: Destination filename");
        } else {
            FileInputStream in = null;
            try {
                in = new FileInputStream(args[0]);
                Destination d = new Destination();
                d.readBytes(in);
                System.out.println(d.toBase64());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
    }
}
