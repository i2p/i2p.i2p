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

import net.i2p.crypto.KeyGenerator;
import net.i2p.util.Log;

/**
 * Defines the SigningPrivateKey as defined by the I2P data structure spec.
 * A private key is 256byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (signing, not decrypting)
 *
 * @author jrandom
 */
public class SigningPrivateKey extends DataStructureImpl {
    private final static Log _log = new Log(SigningPrivateKey.class);
    private byte[] _data;

    public final static int KEYSIZE_BYTES = 20;

    public SigningPrivateKey() { this((byte[])null); }
    public SigningPrivateKey(byte data[]) { setData(data); }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPrivateKey
     */
    public SigningPrivateKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }

    public byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[KEYSIZE_BYTES];
        int read = read(in, _data);
        if (read != KEYSIZE_BYTES) throw new DataFormatException("Not enough bytes to read the private key");
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the private key to write out");
        if (_data.length != KEYSIZE_BYTES) throw new DataFormatException("Invalid size of data in the private key");
        out.write(_data);
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SigningPrivateKey)) return false;
        return DataHelper.eq(_data, ((SigningPrivateKey) obj)._data);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SigningPrivateKey: ");
        if (_data == null) {
            buf.append("null key");
        } else {
            buf.append("size: ").append(_data.length);
            //int len = 32;
            //if (len > _data.length) len = _data.length;
            //buf.append(" first ").append(len).append(" bytes: ");
            //buf.append(DataHelper.toString(_data, len));
        }
        buf.append("]");
        return buf.toString();
    }

    /** converts this signing private key to its public equivalent
     * @return a SigningPublicKey object derived from this private key
     */
    public SigningPublicKey toPublic() {
        return KeyGenerator.getSigningPublicKey(this);
    }
}