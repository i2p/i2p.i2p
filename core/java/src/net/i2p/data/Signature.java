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
 * Defines the signature as defined by the I2P data structure spec.
 * A signature is a 40byte Integer verifying the authenticity of some data 
 * using the algorithm defined in the crypto spec.
 *
 * @author jrandom
 */
public class Signature extends DataStructureImpl {
    private final static Log _log = new Log(Signature.class);
    private byte[] _data;

    public final static int SIGNATURE_BYTES = 40;
    public final static byte[] FAKE_SIGNATURE = new byte[SIGNATURE_BYTES];
    static {
        for (int i = 0; i < SIGNATURE_BYTES; i++)
            FAKE_SIGNATURE[i] = 0x00;
    }

    public Signature() { this(null); }
    public Signature(byte data[]) { setData(data); }

    public byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[SIGNATURE_BYTES];
        int read = read(in, _data);
        if (read != SIGNATURE_BYTES) throw new DataFormatException("Not enough bytes to read the signature");
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the signature to write out");
        if (_data.length != SIGNATURE_BYTES) throw new DataFormatException("Invalid size of data in the private key");
        out.write(_data);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof Signature)) return false;
        return DataHelper.eq(_data, ((Signature) obj)._data);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[Signature: ");
        if (_data == null) {
            buf.append("null signature");
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
}