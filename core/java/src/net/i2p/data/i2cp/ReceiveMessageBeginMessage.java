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

/**
 * Defines the message a client sends to a router when asking the 
 * router to start sending a message to it.
 *
 * @author jrandom
 */
public class ReceiveMessageBeginMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 6;
    private int _sessionId;
    private long _messageId;

    public ReceiveMessageBeginMessage() {
        _sessionId = -1;
        _messageId = -1;
    }

    public long getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId >= 0 ? new SessionId(_sessionId) : null;
    }

    /** @param id 0-65535 */
    public void setSessionId(long id) {
        _sessionId = (int) id;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = (int) DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException("This shouldn't be called... use writeMessage(out)");
    }

    
    /** 
     * Override to reduce mem churn
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        int len = 2 + // sessionId
                  4; // messageId
        
        try {
            DataHelper.writeLong(out, 4, len);
            out.write((byte) MESSAGE_TYPE);
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[ReceiveMessageBeginMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tMessageId: ").append(_messageId);
        buf.append("]");
        return buf.toString();
    }
}
