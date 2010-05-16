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
import net.i2p.data.Destination;
import net.i2p.data.Payload;

/**
 * Defines the message a client sends to a router to ask it to deliver
 * a new message
 *
 * @author jrandom
 */
public class SendMessageMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 5;
    private SessionId _sessionId;
    private Destination _destination;
    private Payload _payload;
    private long _nonce;

    public SendMessageMessage() {
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public Destination getDestination() {
        return _destination;
    }

    public void setDestination(Destination destination) {
        _destination = destination;
    }

    public Payload getPayload() {
        return _payload;
    }

    public void setPayload(Payload payload) {
        _payload = payload;
    }

    public long getNonce() {
        return _nonce;
    }

    public void setNonce(long nonce) {
        _nonce = nonce;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        if (true) throw new IllegalStateException("wtf, do not run me");
    }

    /**
     * Read the body into the data structures
     *
     * @throws IOException 
     */
    @Override
    public void readMessage(InputStream in, int length, int type) throws I2CPMessageException, IOException {
        if (type != getType())
            throw new I2CPMessageException("Invalid message type (found: " + type + " supported: " + getType()
                                           + " class: " + getClass().getName() + ")");
        if (length < 0) throw new IOException("Negative payload size");

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

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new RuntimeException("wtf, dont run me");
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.  Override the parent so we can be more mem efficient
     *
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_destination == null) || (_payload == null) || (_nonce <= 0))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        int len = 2 + _destination.size() + _payload.getSize() + 4 + 4;
        
        try {
            DataHelper.writeLong(out, 4, len);
            DataHelper.writeLong(out, 1, getType());
            _sessionId.writeBytes(out);
            _destination.writeBytes(out);
            _payload.writeBytes(out);
            DataHelper.writeLong(out, 4, _nonce);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing the msg", dfe);
        }
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof SendMessageMessage)) {
            SendMessageMessage msg = (SendMessageMessage) object;
            return DataHelper.eq(getSessionId(), msg.getSessionId())
                   && DataHelper.eq(getDestination(), msg.getDestination()) && (getNonce() == msg.getNonce())
                   && DataHelper.eq(getPayload(), msg.getPayload());
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
        buf.append("\n\tPayload: ").append(getPayload());
        buf.append("]");
        return buf.toString();
    }
}
