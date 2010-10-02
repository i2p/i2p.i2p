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
import net.i2p.data.Payload;

/**
 * Defines the payload message a router sends to the client
 *
 * @author jrandom
 */
public class MessagePayloadMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 31;
    private long _sessionId;
    private long _messageId;
    private Payload _payload;

    public MessagePayloadMessage() {
        _sessionId = -1;
        _messageId = -1;
    }

    public long getSessionId() {
        return _sessionId;
    }

    public void setSessionId(long id) {
        _sessionId = id;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    public Payload getPayload() {
        return _payload;
    }

    public void setPayload(Payload payload) {
        _payload = payload;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
            _payload = new Payload();
            _payload.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new RuntimeException("go away, we dont want any");
    }
    
    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.
     *
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if (_sessionId <= 0)
            throw new I2CPMessageException("Unable to write out the message, as the session ID has not been defined");
        if (_messageId < 0)
            throw new I2CPMessageException("Unable to write out the message, as the message ID has not been defined");
        if (_payload == null)
            throw new I2CPMessageException("Unable to write out the message, as the payload has not been defined");

        int size = 2 + 4 + 4 + _payload.getSize();
        try {
            DataHelper.writeLong(out, 4, size);
            DataHelper.writeLong(out, 1, getType());
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
            DataHelper.writeLong(out, 4, _payload.getSize());
            out.write(_payload.getEncryptedData());
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof MessagePayloadMessage)) {
            MessagePayloadMessage msg = (MessagePayloadMessage) object;
            return _sessionId == msg.getSessionId()
                   && _messageId == msg.getMessageId()
                   && DataHelper.eq(_payload, msg.getPayload());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[MessagePayloadMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tMessageId: ").append(_messageId);
        buf.append("\n\tPayload: ").append(_payload);
        buf.append("]");
        return buf.toString();
    }
}
