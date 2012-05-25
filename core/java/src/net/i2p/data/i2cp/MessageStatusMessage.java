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
    private long _sessionId;
    private long _messageId;
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
        setSessionId(-1);
        setStatus(-1);
        setMessageId(-1);
        setSize(-1);
        setNonce(-1);
    }

    public long getSessionId() {
        return _sessionId;
    }

    public void setSessionId(long id) {
        _sessionId = id;
    }

    public int getStatus() {
        return _status;
    }

    public void setStatus(int status) {
        _status = status;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    public long getSize() {
        return _size;
    }

    public void setSize(long size) {
        _size = size;
    }

    public long getNonce() {
        return _nonce;
    }

    public void setNonce(long nonce) {
        _nonce = nonce;
    }

    public static final String getStatusString(int status) {
        switch (status) {
        case STATUS_AVAILABLE:
            return "AVAILABLE          ";
        case STATUS_SEND_ACCEPTED:
            return "SEND ACCEPTED      ";
        case STATUS_SEND_BEST_EFFORT_SUCCESS:
            return "BEST EFFORT SUCCESS";
        case STATUS_SEND_BEST_EFFORT_FAILURE:
            return "BEST EFFORT FAILURE";
        case STATUS_SEND_GUARANTEED_SUCCESS:
            return "GUARANTEED SUCCESS ";
        case STATUS_SEND_GUARANTEED_FAILURE:
            return "GUARANTEED FAILURE ";
        default:
            return "***INVALID STATUS: " + status;
        }
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
            _status = (int) DataHelper.readLong(in, 1);
            _size = DataHelper.readLong(in, 4);
            _nonce = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    
    /** 
     * Override to reduce mem churn
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        int len = 2 + // sessionId
                  4 + // messageId
                  1 + // status
                  4 + // size
                  4; // nonce
        
        try {
            DataHelper.writeLong(out, 4, len);
            DataHelper.writeLong(out, 1, getType());
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
            DataHelper.writeLong(out, 1, _status);
            DataHelper.writeLong(out, 4, _size);
            DataHelper.writeLong(out, 4, _nonce);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }
    
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException("This shouldn't be called... use writeMessage(out)");
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof MessageStatusMessage)) {
            MessageStatusMessage msg = (MessageStatusMessage) object;
            return DataHelper.eq(getSessionId(), msg.getSessionId())
                   && DataHelper.eq(getMessageId(), msg.getMessageId()) && (getNonce() == msg.getNonce())
                   && DataHelper.eq(getSize(), msg.getSize()) && DataHelper.eq(getStatus(), msg.getStatus());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
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