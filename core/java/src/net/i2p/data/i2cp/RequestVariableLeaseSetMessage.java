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
import java.util.ArrayList;
import java.util.List;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Lease;
import net.i2p.util.VersionComparator;

/**
 * Defines the message a router sends to a client to request that
 * a leaseset be created and signed. The reply is a CreateLeaseSetMessage.
 *
 * This message has an expiration time for each lease, unlike RequestLeaseSetMessage,
 * which has a single expiration time for all leases.
 *
 * @since 0.9.7
 */
public class RequestVariableLeaseSetMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 37;
    private SessionId _sessionId;
    private final List<Lease> _endpoints;

    private static final String MIN_VERSION = "0.9.7";

    public RequestVariableLeaseSetMessage() {
        _endpoints = new ArrayList<Lease>();
    }

    /**
     *  Does the client support this message?
     *
     *  @param clientVersion may be null
     *  @return version != null and version &gt;= 0.9.7
     */
    public static boolean isSupported(String clientVersion) {
        return clientVersion != null &&
               VersionComparator.comp(clientVersion, MIN_VERSION) >= 0;
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

    public int getEndpoints() {
        return _endpoints.size();
    }

    public Lease getEndpoint(int endpoint) {
        if ((endpoint < 0) || (_endpoints.size() <= endpoint)) return null;
        return _endpoints.get(endpoint);
    }

    public void addEndpoint(Lease lease) {
        if (lease == null)
            throw new IllegalArgumentException();
        _endpoints.add(lease);
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            if (_sessionId != null)
                throw new IllegalStateException();
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int numTunnels = (int) DataHelper.readLong(in, 1);
            for (int i = 0; i < numTunnels; i++) {
                Lease lease = new Lease();
                lease.readBytes(in);
                _endpoints.add(lease);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null)
            throw new I2CPMessageException("No data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(256);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 1, _endpoints.size());
            for (int i = 0; i < _endpoints.size(); i++) {
                _endpoints.get(i).writeBytes(os);
            }
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
        buf.append("[RequestVariableLeaseSetMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tTunnels:");
        for (int i = 0; i < getEndpoints(); i++) {
            buf.append('\n').append(_endpoints.get(i));
        }
        buf.append("]");
        return buf.toString();
    }
}
