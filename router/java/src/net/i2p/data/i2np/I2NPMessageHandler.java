package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Handle messages from router to router
 *
 */
public class I2NPMessageHandler {
    private final static Log _log = new Log(I2NPMessageHandler.class);
    private long _lastReadBegin;
    private long _lastReadEnd;
    public I2NPMessageHandler() {}
    
    /**
     * Read an I2NPMessage from the stream and return the fully populated object.
     * 
     * @throws IOException if there is an IO problem reading from the stream
     * @throws I2NPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public I2NPMessage readMessage(InputStream in) throws IOException, I2NPMessageException {
        try {
            int type = (int)DataHelper.readLong(in, 1);
	    _lastReadBegin = Clock.getInstance().now();
            I2NPMessage msg = createMessage(in, type);
            msg.readBytes(in, type);
	    _lastReadEnd = Clock.getInstance().now();
            return msg;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message", dfe);
        }
    }
    
    public long getLastReadTime() { return _lastReadEnd - _lastReadBegin; }
    
    /**
     * Yes, this is fairly ugly, but its the only place it ever happens.  
     *
     */
    private static I2NPMessage createMessage(InputStream in, int type) throws IOException, I2NPMessageException {
        switch (type) {
	    case DatabaseStoreMessage.MESSAGE_TYPE:
		return new DatabaseStoreMessage();
	    case DatabaseLookupMessage.MESSAGE_TYPE:
		return new DatabaseLookupMessage();
	    case DatabaseSearchReplyMessage.MESSAGE_TYPE:
		return new DatabaseSearchReplyMessage();
	    case DeliveryStatusMessage.MESSAGE_TYPE:
		return new DeliveryStatusMessage();
	    case GarlicMessage.MESSAGE_TYPE:
		return new GarlicMessage();
	    case TunnelMessage.MESSAGE_TYPE:
		return new TunnelMessage();
	    case DataMessage.MESSAGE_TYPE:
		return new DataMessage();
	    case SourceRouteReplyMessage.MESSAGE_TYPE:
		return new SourceRouteReplyMessage();
	    case TunnelCreateMessage.MESSAGE_TYPE:
		return new TunnelCreateMessage();
	    case TunnelCreateStatusMessage.MESSAGE_TYPE:
		return new TunnelCreateStatusMessage();
            default:
                throw new I2NPMessageException("The type "+ type + " is an unknown I2NP message");
        }
    }
    
    public static void main(String args[]) {
        try {
            I2NPMessage msg = new I2NPMessageHandler().readMessage(new FileInputStream(args[0]));
            System.out.println(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
