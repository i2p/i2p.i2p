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
 * Defines the message a client sends to a router when
 * updating the config on an existing session.
 *
 * @author zzz
 */
public class ReconfigureSessionMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 2;
    private SessionId _sessionId;
    private SessionConfig _sessionConfig;

    public ReconfigureSessionMessage() {
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public SessionConfig getSessionConfig() {
        return _sessionConfig;
    }

    public void setSessionConfig(SessionConfig config) {
        _sessionConfig = config;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _sessionConfig = new SessionConfig();
            _sessionConfig.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null || _sessionConfig == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            _sessionId.writeBytes(os);
            _sessionConfig.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof ReconfigureSessionMessage)) {
            ReconfigureSessionMessage msg = (ReconfigureSessionMessage) object;
            return DataHelper.eq(_sessionId, msg.getSessionId())
                   && DataHelper.eq(_sessionConfig, msg.getSessionConfig());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[ReconfigureSessionMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tSessionConfig: ").append(_sessionConfig);
        buf.append("]");
        return buf.toString();
    }
}
