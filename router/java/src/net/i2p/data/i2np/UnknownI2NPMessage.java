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

/**
 * This is the same as DataMessage but with a variable message type.
 * This is defined so routers can route messages they don't know about.
 * We don't extend DataMessage so that any code that does (instanceof DataMessage)
 * won't return true for this type. Load tests use DataMessage, for example.
 * See InboundMessageDistributor.
 *
 * There is no setData() method, the only way to create one of these is to
 * read it with readMessage() (i.e., it came from some other router)
 *
 * @since 0.7.12
 */
public class UnknownI2NPMessage extends I2NPMessageImpl {
    private byte _data[];
    private int _type;
    
    /** @param type 0-255 */
    public UnknownI2NPMessage(I2PAppContext context, int type) {
        super(context);
        _type = type;
    }
    
    /** warning - only public for equals() */
    public byte[] getData() { 
        return _data; 
    }

    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != _type) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        long size = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        if (size > MAX_SIZE)
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
    
    /** @return 0-255 */
    public int getType() { return _type; }
    
    @Override
    public int hashCode() {
        return _type + DataHelper.hashCode(getData());
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof UnknownI2NPMessage) ) {
            UnknownI2NPMessage msg = (UnknownI2NPMessage)object;
            return _type == msg.getType() && DataHelper.eq(getData(), msg.getData());
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[UnknownI2NPMessage: ");
        buf.append("\n\tType: ").append(_type);
        buf.append("\n\tLength: ").append(calculateWrittenLength() - 4);
        buf.append("]");
        return buf.toString();
    }
}
