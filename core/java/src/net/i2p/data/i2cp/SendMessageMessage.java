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
    protected SessionId _sessionId;
    protected Destination _destination;
    protected Payload _payload;
    protected long _nonce;

    /**
     *  For reading.
     *  Deprecated for writing, use 4-arg constructor
     */
    public SendMessageMessage() {
    }

    /**
     *  For writing
     *
     *  @since 0.9.54
     */
    public SendMessageMessage(SessionId sessID, Destination dest, Payload payload, long nonce) {
        synchronized(this) {
            _sessionId = sessID;
            _destination = dest;
            _payload = payload;
            _nonce = nonce;
        }
    }

    public synchronized SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public synchronized SessionId sessionId() {
        return _sessionId;
    }

    /**
     *  @deprecated use 4-arg constructor
     */
    @Deprecated
    public synchronized void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public synchronized Destination getDestination() {
        return _destination;
    }

    /**
     *  @deprecated use 4-arg constructor
     */
    @Deprecated
    public synchronized void setDestination(Destination destination) {
        _destination = destination;
    }

    public synchronized Payload getPayload() {
        return _payload;
    }

    /**
     *  @deprecated use 4-arg constructor
     */
    @Deprecated
    public synchronized void setPayload(Payload payload) {
        _payload = payload;
    }

    /**
     * @return 0 to 0xffffffff
     */
    public synchronized long getNonce() {
        return _nonce;
    }

    /**
     *  @param nonce 0 to 0xffffffff
     *  @deprecated use 4-arg constructor
     */
    @Deprecated
    public synchronized void setNonce(long nonce) {
        _nonce = nonce;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Read the body into the data structures
     *
     * @throws IOException 
     */
    @Override
    public synchronized void readMessage(InputStream in, int length, int type) throws I2CPMessageException, IOException {
        if (type != getType())
            throw new I2CPMessageException("Invalid message type (found: " + type + " supported: " + getType()
                                           + " class: " + getClass().getName() + ")");
        if (length < 0) throw new IOException("Negative payload size");

        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _destination = Destination.create(in);
            _payload = new Payload();
            _payload.readBytes(in);
            _nonce = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1 
     * byte type header.  Override the parent so we can be more mem efficient
     *
     * @throws IOException 
     */
    @Override
    public synchronized void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if (_sessionId == null)
            throw new I2CPMessageException("No session ID");
        if (_destination == null)
            throw new I2CPMessageException("No dest");
        if (_payload == null)
            throw new I2CPMessageException("No payload");
        if (_nonce < 0)
            throw new I2CPMessageException("No nonce");
        int len = 2 + _destination.size() + _payload.getSize() + 4 + 4;
        
        try {
            DataHelper.writeLong(out, 4, len);
            out.write((byte) getType());
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
