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
import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.util.Log;

/**
 * Defines the message a client sends to a router to ask it to deliver
 * a new message
 *
 * @author jrandom
 */
public class SendMessageMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(SendMessageMessage.class);
    public final static int MESSAGE_TYPE = 5;
    private SessionId _sessionId;
    private Destination _destination;
    private Payload _payload;
    private long _nonce;
    
    public SendMessageMessage() { 
        setSessionId(null);
        setDestination(null);
        setPayload(null);
	setNonce(0);
    }
    
    public SessionId getSessionId() { return _sessionId; }
    public void setSessionId(SessionId id) { _sessionId = id; }
    public Destination getDestination() { return _destination; }
    public void setDestination(Destination destination) { _destination = destination; }
    public Payload getPayload() { return _payload; }
    public void setPayload(Payload payload) { _payload = payload; }
    public long getNonce() { return _nonce; }
    public void setNonce(long nonce) { _nonce = nonce; }
    
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _destination = new Destination();
            _destination.readBytes(in);
            _payload = new Payload();
            _payload.readBytes(in);
	    _nonce = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ( (_sessionId == null) || (_destination == null) || (_payload == null) || (_nonce <= 0) ) 
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(512);
        try {
            _sessionId.writeBytes(os);
            _destination.writeBytes(os);
            _payload.writeBytes(os);
	    DataHelper.writeLong(os, 4, _nonce);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof SendMessageMessage) ) {
            SendMessageMessage msg = (SendMessageMessage)object;
            return DataHelper.eq(getSessionId(),msg.getSessionId()) &&
                   DataHelper.eq(getDestination(),msg.getDestination()) &&
                   (getNonce() == msg.getNonce()) &&
                   DataHelper.eq(getPayload(),msg.getPayload());
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[SendMessageMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tNonce: ").append(getNonce());
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("\n\tPayload: ").append(getPayload());
        buf.append("]");
        return buf.toString();
    }
}
