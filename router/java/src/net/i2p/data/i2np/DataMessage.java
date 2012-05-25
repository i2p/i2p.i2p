package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
// import net.i2p.util.Log;

/**
 * Defines a message containing arbitrary bytes of data
 *
 * @author jrandom
 */
public class DataMessage extends I2NPMessageImpl {
    // private final static Log _log = new Log(DataMessage.class);
    public final static int MESSAGE_TYPE = 20;
    private byte _data[];
    
    // private static final int MAX_SIZE = 64*1024;  // LINT -- field hides another field, and not used
    
    public DataMessage(I2PAppContext context) {
        super(context);
        _data = null;
    }
    
    public byte[] getData() { 
        verifyUnwritten();
        return _data; 
    }
    public void setData(byte[] data) { 
        verifyUnwritten();
        _data = data; 
    }
    
    public int getSize() { 
        verifyUnwritten();
        return _data.length;
    }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        long size = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        if (size > 64*1024)
            throw new I2NPMessageException("wtf, size=" + size);
        _data = new byte[(int)size];
        System.arraycopy(data, curIndex, _data, 0, (int)size);
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
        verifyUnwritten();
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
    
    @Override
    protected void written() {
        super.written();
        _data = null;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getData());
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DataMessage) ) {
            DataMessage msg = (DataMessage)object;
            return DataHelper.eq(getData(),msg.getData());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DataMessage: ");
        buf.append("\n\tData: ").append(DataHelper.toString(getData(), 64));
        buf.append("]");
        return buf.toString();
    }
}
