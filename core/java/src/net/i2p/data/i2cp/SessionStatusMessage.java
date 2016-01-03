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

/**
 * Defines the message a router sends to a client indicating the
 * status of the session.
 *
 * @author jrandom
 */
public class SessionStatusMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 20;
    private SessionId _sessionId;
    private int _status;

    public final static int STATUS_DESTROYED = 0;
    public final static int STATUS_CREATED = 1;
    public final static int STATUS_UPDATED = 2;
    public final static int STATUS_INVALID = 3;
    /** @since 0.9.12 */
    public final static int STATUS_REFUSED = 4;

    public SessionStatusMessage() {
        setStatus(STATUS_INVALID);
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public int getStatus() {
        return _status;
    }

    public void setStatus(int status) {
        _status = status;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _status = (int) DataHelper.readLong(in, 1);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 1, _status);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[SessionStatusMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tStatus: ").append(getStatus());
        buf.append("]");
        return buf.toString();
    }
}
