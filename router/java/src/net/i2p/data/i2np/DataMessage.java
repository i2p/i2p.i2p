package net.i2p.data.i2np;
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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Defines a message containing arbitrary bytes of data
 *
 * @author jrandom
 */
public class DataMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DataMessage.class);
    public final static int MESSAGE_TYPE = 20;
    private byte _data[];
    
    public DataMessage() { 
	_data = null;
    }
    
    public byte[] getData() { return _data; }
    public void setData(byte data[]) { _data = data; }
    
    public int getSize() { return _data.length; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    int size = (int)DataHelper.readLong(in, 4);
	    _data = new byte[size];
	    int read = read(in, _data);
	    if (read != size) 
		throw new DataFormatException("Not enough bytes to read (read = " + read + ", expected = " + size + ")");
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	ByteArrayOutputStream os = new ByteArrayOutputStream((_data != null ? _data.length + 4 : 4));
        try {
	    DataHelper.writeLong(os, 4, (_data != null ? _data.length : 0));
	    os.write(_data);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
	return DataHelper.hashCode(getData());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DataMessage) ) {
            DataMessage msg = (DataMessage)object;
            return DataHelper.eq(getData(),msg.getData());
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[DataMessage: ");
        buf.append("\n\tData: ").append(DataHelper.toString(getData(), 64));
        buf.append("]");
        return buf.toString();
    }
}
