package net.i2p.data.i2cp;
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
import net.i2p.data.Payload;
import net.i2p.util.Log;

/**
 * Defines the message a client sends to a router to ask it to deliver
 * a new message
 *
 * @author jrandom
 */
public class MessagePayloadMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(MessagePayloadMessage.class);
    public final static int MESSAGE_TYPE = 31;
    private SessionId _sessionId;
    private MessageId _messageId;
    private Payload _payload;
    
    public MessagePayloadMessage() { 
        setSessionId(null);
        setMessageId(null);
        setPayload(null);
    }
    
    public SessionId getSessionId() { return _sessionId; }
    public void setSessionId(SessionId id) { _sessionId = id; }
    public MessageId getMessageId() { return _messageId; }
    public void setMessageId(MessageId id) { _messageId = id; }
    public Payload getPayload() { return _payload; }
    public void setPayload(Payload payload) { _payload = payload; }
    
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _messageId = new MessageId();
            _messageId.readBytes(in);
            _payload = new Payload();
            _payload.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
	if (_sessionId == null)
	    throw new I2CPMessageException("Unable to write out the message, as the session ID has not been defined");
	if (_messageId == null)
	    throw new I2CPMessageException("Unable to write out the message, as the message ID has not been defined");
	if (_payload == null)
	    throw new I2CPMessageException("Unable to write out the message, as the payload has not been defined");
	
        ByteArrayOutputStream os = new ByteArrayOutputStream(512);
        try {
            _sessionId.writeBytes(os);
            _messageId.writeBytes(os);
            _payload.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof MessagePayloadMessage) ) {
            MessagePayloadMessage msg = (MessagePayloadMessage)object;
            return DataHelper.eq(getSessionId(),msg.getSessionId()) &&
                   DataHelper.eq(getMessageId(),msg.getMessageId()) &&
                   DataHelper.eq(getPayload(),msg.getPayload());
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[MessagePayloadMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tMessageId: ").append(getMessageId());
        buf.append("\n\tPayload: ").append(getPayload());
        buf.append("]");
        return buf.toString();
    }
}
