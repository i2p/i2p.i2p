package net.i2p.data.i2cp;

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
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.util.Log;

/**
 * Same as SendMessageMessage, but with an expiration to be passed to the router
 *
 * @author zzz
 */
public class SendMessageExpiresMessage extends SendMessageMessage {
    private final static Log _log = new Log(SendMessageExpiresMessage.class);
    public final static int MESSAGE_TYPE = 36;
    private SessionId _sessionId;
    private Destination _destination;
    private Payload _payload;
    private Date _expiration;

    public SendMessageExpiresMessage() {
        super();
        setExpiration(null);
    }

    public Date getExpiration() {
        return _expiration;
    }

    public void setExpiration(Date d) {
        _expiration = d;
    }

    /**
     * Read the body into the data structures
     *
     * @throws IOException 
     */
    @Override
    public void readMessage(InputStream in, int length, int type) throws I2CPMessageException, IOException {
        super.readMessage(in, length, type);

        try {
            _expiration = DataHelper.readDate(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.  Override the parent so we can be more mem efficient
     *
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if ((getSessionId() == null) || (getDestination() == null) || (getPayload() == null) || (getNonce() <= 0) || (_expiration == null))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        int len = 2 + getDestination().size() + getPayload().getSize() + 4 + 4 + DataHelper.DATE_LENGTH;
        
        try {
            DataHelper.writeLong(out, 4, len);
            DataHelper.writeLong(out, 1, getType());
            getSessionId().writeBytes(out);
            getDestination().writeBytes(out);
            getPayload().writeBytes(out);
            DataHelper.writeLong(out, 4, getNonce());
            DataHelper.writeDate(out, _expiration);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing the msg", dfe);
        }
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof SendMessageExpiresMessage)) {
            SendMessageExpiresMessage msg = (SendMessageExpiresMessage) object;
            return super.equals(object)
                   && DataHelper.eq(getExpiration(), msg.getExpiration());
        }
         
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[SendMessageMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tNonce: ").append(getNonce());
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("\n\tExpiration: ").append(getExpiration());
        buf.append("\n\tPayload: ").append(getPayload());
        buf.append("]");
        return buf.toString();
    }
}
