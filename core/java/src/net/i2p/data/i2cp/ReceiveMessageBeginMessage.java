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
 * Defines the message a client sends to a router when asking the 
 * router to start sending a message to it.
 *
 * @author jrandom
 */
public class ReceiveMessageBeginMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(ReceiveMessageBeginMessage.class);
    public final static int MESSAGE_TYPE = 6;
    private SessionId _sessionId;
    private MessageId _messageId;

    public ReceiveMessageBeginMessage() {
        setSessionId(null);
        setMessageId(null);
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public MessageId getMessageId() {
        return _messageId;
    }

    public void setMessageId(MessageId id) {
        _messageId = id;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _messageId = new MessageId();
            _messageId.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_messageId == null))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        byte rv[] = new byte[2+4];
        DataHelper.toLong(rv, 0, 2, _sessionId.getSessionId());
        DataHelper.toLong(rv, 2, 4, _messageId.getMessageId());
        return rv;
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    public boolean equals(Object object) {
        if ((object != null) && (object instanceof ReceiveMessageBeginMessage)) {
            ReceiveMessageBeginMessage msg = (ReceiveMessageBeginMessage) object;
            return DataHelper.eq(getSessionId(), msg.getSessionId())
                   && DataHelper.eq(getMessageId(), msg.getMessageId());
        }
            
        return false;
    }

    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[ReceiveMessageBeginMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tMessageId: ").append(getMessageId());
        buf.append("]");
        return buf.toString();
    }
}