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
 * Contains the sending router's current time, to sync (and verify sync)
 *
 */
public class DateMessage extends I2NPMessageImpl {
    public final static int MESSAGE_TYPE = 16;
    private long _now;
    
    public DateMessage(I2PAppContext context) {
        super(context);
        _now = context.clock().now();
    }
    
    public long getNow() { return _now; }
    public void setNow(long now) { _now = now; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _now = DataHelper.fromLong(data, curIndex, DataHelper.DATE_LENGTH);
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return DataHelper.DATE_LENGTH; // now
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if (_now <= 0) throw new I2NPMessageException("Not enough data to write out");
        
        DataHelper.toLong(out, curIndex, DataHelper.DATE_LENGTH, _now);
        curIndex += DataHelper.DATE_LENGTH;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return (int)getNow();
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DateMessage) ) {
            DateMessage msg = (DateMessage)object;
            return msg.getNow() == getNow();
        } else {
            return false;
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[DateMessage: ");
        buf.append("Now: ").append(_now);
        buf.append("]");
        return buf.toString();
    }
}
