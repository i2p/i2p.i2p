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

import net.i2p.I2PAppContext;
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
    
    private static final int MAX_SIZE = 64*1024;
    
    public DataMessage(I2PAppContext context) {
        super(context);
        _data = null;
    }
    
    public byte[] getData() { return _data; }
    public void setData(byte data[]) { _data = data; }
    
    public int getSize() { return _data.length; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            int size = (int)DataHelper.readLong(in, 4);
            if ( (size <= 0) || (size > MAX_SIZE) )
                throw new I2NPMessageException("wtf, size out of range?  " + size);
            _data = new byte[size];
            int read = read(in, _data);
            if (read != size)
                throw new DataFormatException("Not enough bytes to read (read = " + read + ", expected = " + size + ")");
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        if (_data == null) 
            return 4;
        else
            return 4 + _data.length;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) {
        if (_data == null) {
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
            out[curIndex++] = 0x0;
        } else {
            byte len[] = DataHelper.toLong(4, _data.length);
            System.arraycopy(len, 0, out, curIndex, 4);
            curIndex += 4;
            System.arraycopy(_data, 0, out, curIndex, _data.length);
            curIndex += _data.length;
        }
        return curIndex;
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
