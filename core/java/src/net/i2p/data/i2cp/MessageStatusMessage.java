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
import net.i2p.util.Log;

/**
 * Defines the message a client sends to a router when destroying
 * existing session.
 *
 * @author jrandom
 */
public class MessageStatusMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(SessionStatusMessage.class);
    public final static int MESSAGE_TYPE = 22;
    private SessionId _sessionId;
    private MessageId _messageId;
    private long _nonce;
    private long _size;
    private int _status;
    
    public final static int STATUS_AVAILABLE = 0;
    public final static int STATUS_SEND_ACCEPTED = 1;
    public final static int STATUS_SEND_BEST_EFFORT_SUCCESS = 2;
    public final static int STATUS_SEND_BEST_EFFORT_FAILURE = 3;
    public final static int STATUS_SEND_GUARANTEED_SUCCESS = 4;
    public final static int STATUS_SEND_GUARANTEED_FAILURE = 5;
    
    public MessageStatusMessage() { 
        setSessionId(null);
        setStatus(-1);
        setMessageId(null);
        setSize(-1);
	setNonce(-1);
    }
    
    public SessionId getSessionId() { return _sessionId; }
    public void setSessionId(SessionId id) { _sessionId = id; }
    public int getStatus() { return _status; }
    public void setStatus(int status) { _status = status; }
    public MessageId getMessageId() { return _messageId; }
    public void setMessageId(MessageId id) { _messageId = id; }
    public long getSize() { return _size; }
    public void setSize(long size) { _size = size; }
    public long getNonce() { return _nonce; }
    public void setNonce(long nonce) { _nonce = nonce; }
    
    public static final String getStatusString(int status) { 
	switch (status) {
	    case STATUS_AVAILABLE:                return "AVAILABLE          ";
	    case STATUS_SEND_ACCEPTED:            return "SEND ACCEPTED      ";
	    case STATUS_SEND_BEST_EFFORT_SUCCESS: return "BEST EFFORT SUCCESS";
	    case STATUS_SEND_BEST_EFFORT_FAILURE: return "BEST EFFORT FAILURE";
	    case STATUS_SEND_GUARANTEED_SUCCESS:  return "GUARANTEED SUCCESS ";
	    case STATUS_SEND_GUARANTEED_FAILURE:  return "GUARANTEED FAILURE ";
	    default:                              return "***INVALID STATUS: " + status;
	}
    }
    
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _messageId = new MessageId();
            _messageId.readBytes(in);
            _status = (int)DataHelper.readLong(in, 1);
            _size = DataHelper.readLong(in, 4);
            _nonce = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }
    
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ( (_sessionId == null) || (_messageId == null) || (_status < 0) || (_nonce <= 0) )
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            _sessionId.writeBytes(os);
            _messageId.writeBytes(os);
            DataHelper.writeLong(os, 1, _status);
            DataHelper.writeLong(os, 4, _size);
            DataHelper.writeLong(os, 4, _nonce);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof MessageStatusMessage) ) {
            MessageStatusMessage msg = (MessageStatusMessage)object;
            return DataHelper.eq(getSessionId(),msg.getSessionId()) &&
                   DataHelper.eq(getMessageId(),msg.getMessageId()) && 
                   (getNonce() == msg.getNonce()) && 
                   DataHelper.eq(getSize(),msg.getSize()) && 
                   DataHelper.eq(getStatus(),msg.getStatus());
        } else {
            return false;
        }
    }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer();
        buf.append("[MessageStatusMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tNonce: ").append(getNonce());
        buf.append("\n\tMessageId: ").append(getMessageId());
        buf.append("\n\tStatus: ").append(getStatusString(getStatus()));
        buf.append("\n\tSize: ").append(getSize());
        buf.append("]");
        return buf.toString();
    }
}
