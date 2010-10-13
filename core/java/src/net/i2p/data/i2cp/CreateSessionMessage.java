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
 * Defines the message a client sends to a router when establishing a new 
 * session.
 *
 * @author jrandom
 */
public class CreateSessionMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 1;
    private SessionConfig _sessionConfig;

    public CreateSessionMessage(SessionConfig config) {
        _sessionConfig = config;
    }

    public CreateSessionMessage() {
        _sessionConfig = new SessionConfig();
    }

    public SessionConfig getSessionConfig() {
        return _sessionConfig;
    }

    public void setSessionConfig(SessionConfig config) {
        _sessionConfig = config;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        SessionConfig config = new SessionConfig();
        try {
            config.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the session configuration", dfe);
        }
        setSessionConfig(config);
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionConfig == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            _sessionConfig.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the session config", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof CreateSessionMessage)) {
            CreateSessionMessage msg = (CreateSessionMessage) object;
            return DataHelper.eq(_sessionConfig, msg.getSessionConfig());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[CreateSessionMessage: ");
        buf.append("\n\tConfig: ").append(_sessionConfig);
        buf.append("]");
        return buf.toString();
    }
}
