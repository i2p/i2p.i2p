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
    
    public DeliveryStatusMessage() { 
	setMessageId(-1);
	setArrival(null);
    }
    
    public long getMessageId() { return _id; }
    public void setMessageId(long id) { _id = id; }
    
    public Date getArrival() { return _arrival; }
    public void setArrival(Date arrival) { _arrival = arrival; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
	if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
	    _id = DataHelper.readLong(in, 4);
	    _arrival = DataHelper.readDate(in);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] writeMessage() throws I2NPMessageException, IOException {
	if ( (_id < 0) || (_arrival == null) ) throw new I2NPMessageException("Not enough data to write out");
	
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
	    DataHelper.writeLong(os, 4, _id);
	    DataHelper.writeDate(os, _arrival);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
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
        buf.append("\n\tArrival: ").append(getArrival());
        buf.append("]");
        return buf.toString();
    }
}
