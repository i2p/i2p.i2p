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
import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Defines the message sent back in reply to a message when requested, containing
 * the private ack id.
 *
 * @author jrandom
 */
public class DeliveryStatusMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(DeliveryStatusMessage.class);
    public final static int MESSAGE_TYPE = 10;
    private long _id;
    private Date _arrival;
    
    public DeliveryStatusMessage(I2PAppContext context) {
        super(context);
        setMessageId(-1);
        setArrival(null);
    }
    
    public long getMessageId() { return _id; }
    public void setMessageId(long id) { _id = id; }
    
    public Date getArrival() { return _arrival; }
    public void setArrival(Date arrival) { _arrival = arrival; }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _id = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        _arrival = DataHelper.fromDate(data, curIndex);
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return 4 + DataHelper.DATE_LENGTH; // id + arrival
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_id < 0) || (_arrival == null) ) throw new I2NPMessageException("Not enough data to write out");
        
        byte id[] = DataHelper.toLong(4, _id);
        System.arraycopy(id, 0, out, curIndex, 4);
        curIndex += 4;
        byte date[] = DataHelper.toDate(_arrival);
        System.arraycopy(date, 0, out, curIndex, DataHelper.DATE_LENGTH);
        curIndex += DataHelper.DATE_LENGTH;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return (int)getMessageId() +
        DataHelper.hashCode(getArrival());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof DeliveryStatusMessage) ) {
            DeliveryStatusMessage msg = (DeliveryStatusMessage)object;
            return DataHelper.eq(getMessageId(),msg.getMessageId()) &&
                   DataHelper.eq(getArrival(),msg.getArrival());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[DeliveryStatusMessage: ");
        buf.append("\n\tMessage ID: ").append(getMessageId());
        buf.append("\n\tArrival: ").append(_context.clock().now() - _arrival.getTime());
        buf.append("ms in the past");
        buf.append("]");
        return buf.toString();
    }
}
