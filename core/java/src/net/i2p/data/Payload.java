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
import java.util.Arrays;

/**
 * Defines the actual payload of a message being delivered, including the 
 * standard encryption wrapping, as defined by the I2P data structure spec.
 *
 * This is used mostly in I2CP, where we used to do end-to-end encryption.
 * Since we don't any more, you probably just want to use the
 * get/set EncryptedData methods.
 *
 * @author jrandom
 */
public class Payload extends DataStructureImpl {
    //private final static Log _log = new Log(Payload.class);
    private byte[] _encryptedData;
    private byte[] _unencryptedData;

    /** So we don't OOM on I2CP protocol errors. Actual max is smaller. */
    private static final int MAX_LENGTH = 64*1024;

    public Payload() {
    }

    /**
     * Retrieve the unencrypted body of the message.  
     *
     * Deprecated.
     * Unless you are doing encryption, use getEncryptedData() instead.
     *
     * @return body of the message, or null if the message has either not been
     *          decrypted yet or if the hash is not correct
     */
    public byte[] getUnencryptedData() {
        return _unencryptedData;
    }

    /**
     * Populate the message body with data.  This does not automatically encrypt
     * yet.
     * 
     * Deprecated.
     * Unless you are doing encryption, use setEncryptedData() instead.
     * @throws IllegalArgumentException if bigger than 64KB
     */
    public void setUnencryptedData(byte[] data) {
        if (data.length > MAX_LENGTH)
            throw new IllegalArgumentException();
        _unencryptedData = data;
    }

    /** the real data */
    public byte[] getEncryptedData() {
        return _encryptedData;
    }

    /**
     * the real data
     * @throws IllegalArgumentException if bigger than 64KB
     */
    public void setEncryptedData(byte[] data) {
        if (data.length > MAX_LENGTH)
            throw new IllegalArgumentException();
        _encryptedData = data;
    }

    public int getSize() {
        if (_unencryptedData != null)
            return _unencryptedData.length;
        else if (_encryptedData != null)
            return _encryptedData.length;
        else
            return 0;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        int size = (int) DataHelper.readLong(in, 4);
        if (size < 0 || size > MAX_LENGTH) throw new DataFormatException("payload size out of range (" + size + ")");
        _encryptedData = new byte[size];
        int read = read(in, _encryptedData);
        if (read != size) throw new DataFormatException("Incorrect number of bytes read in the payload structure");
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("read payload: " + read + " bytes");
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_encryptedData == null) throw new DataFormatException("Not yet encrypted.  Please set the encrypted data");
        DataHelper.writeLong(out, 4, _encryptedData.length);
        out.write(_encryptedData);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("wrote payload: " + _encryptedData.length);
    }

    /**
     *  @return the written length (NOT the new offset)    
     */    
    public int writeBytes(byte target[], int offset) {
        if (_encryptedData == null) throw new IllegalStateException("Not yet encrypted.  Please set the encrypted data");
        DataHelper.toLong(target, offset, 4, _encryptedData.length);
        offset += 4;
        System.arraycopy(_encryptedData, 0, target, offset, _encryptedData.length);
        return 4 + _encryptedData.length;
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Payload)) return false;
        Payload p = (Payload) object;
        return Arrays.equals(_unencryptedData, p.getUnencryptedData())
               && Arrays.equals(_encryptedData, p.getEncryptedData());
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_encryptedData != null ? _encryptedData : _unencryptedData);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(32);
        buf.append("[Payload: ");
        if (_encryptedData != null)
            buf.append(_encryptedData.length).append(" bytes");
        else
            buf.append("null");
        buf.append("]");
        return buf.toString();
    }
}
