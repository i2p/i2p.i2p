package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines an end point in the I2P network.  The Destination may move around
 * in the network, but messages sent to the Destination will find it
 *
 * @author jrandom
 */
public class Destination extends KeysAndCert {

    public Destination() {
    }

    /**
     * alternative constructor which takes a base64 string representation
     * @param s a Base64 representation of the destination, as (eg) is used in hosts.txt
     */
    public Destination(String s) throws DataFormatException {
        fromBase64(s);
    }

    /**
     *  deprecated, used only by Packet.java in streaming
     *  @return the written length (NOT the new offset)    
     */    
    public int writeBytes(byte target[], int offset) {
        int cur = offset;
        System.arraycopy(_publicKey.getData(), 0, target, cur, PublicKey.KEYSIZE_BYTES);
        cur += PublicKey.KEYSIZE_BYTES;
        System.arraycopy(_signingKey.getData(), 0, target, cur, SigningPublicKey.KEYSIZE_BYTES);
        cur += SigningPublicKey.KEYSIZE_BYTES;
        cur += _certificate.writeBytes(target, cur);
        return cur - offset;
    }
    
    /** deprecated, used only by Packet.java in streaming */
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
}
