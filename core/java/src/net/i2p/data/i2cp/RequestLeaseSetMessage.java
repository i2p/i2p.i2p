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
import java.util.Date;
import java.util.List;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Defines the message a client sends to a router when destroying
 * existing session.
 *
 * @author jrandom
 */
public class RequestLeaseSetMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 21;
    private SessionId _sessionId;
    private List<TunnelEndpoint> _endpoints;
    private Date _end;

    public RequestLeaseSetMessage() {
        _endpoints = new ArrayList();
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public int getEndpoints() {
        return _endpoints.size();
    }

    public Hash getRouter(int endpoint) {
        if ((endpoint < 0) || (_endpoints.size() < endpoint)) return null;
        return _endpoints.get(endpoint).getRouter();
    }

    public TunnelId getTunnelId(int endpoint) {
        if ((endpoint < 0) || (_endpoints.size() < endpoint)) return null;
        return _endpoints.get(endpoint).getTunnelId();
    }

    /** @deprecated unused - presumably he meant remove? */
    public void remoteEndpoint(int endpoint) {
        if ((endpoint >= 0) && (endpoint < _endpoints.size())) _endpoints.remove(endpoint);
    }

    public void addEndpoint(Hash router, TunnelId tunnel) {
        if (router == null) throw new IllegalArgumentException("Null router (tunnel=" + tunnel +")");
        if (tunnel == null) throw new IllegalArgumentException("Null tunnel (router=" + router +")");
        _endpoints.add(new TunnelEndpoint(router, tunnel));
    }

    public Date getEndDate() {
        return _end;
    }

    public void setEndDate(Date end) {
        _end = end;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int numTunnels = (int) DataHelper.readLong(in, 1);
            _endpoints.clear();
            for (int i = 0; i < numTunnels; i++) {
                //Hash router = new Hash();
                //router.readBytes(in);
                Hash router = Hash.create(in);
                TunnelId tunnel = new TunnelId();
                tunnel.readBytes(in);
                _endpoints.add(new TunnelEndpoint(router, tunnel));
            }
            _end = DataHelper.readDate(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_endpoints == null))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 1, _endpoints.size());
            for (int i = 0; i < _endpoints.size(); i++) {
                Hash router = getRouter(i);
                router.writeBytes(os);
                TunnelId tunnel = getTunnelId(i);
                tunnel.writeBytes(os);
            }
            DataHelper.writeDate(os, _end);
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
        if ((object != null) && (object instanceof RequestLeaseSetMessage)) {
            RequestLeaseSetMessage msg = (RequestLeaseSetMessage) object;
            if (getEndpoints() != msg.getEndpoints()) return false;
            for (int i = 0; i < getEndpoints(); i++) {
                if (!DataHelper.eq(getRouter(i), msg.getRouter(i)) || !DataHelper.eq(getTunnelId(i), msg.getTunnelId(i)))
                    return false;
            }
            return DataHelper.eq(getSessionId(), msg.getSessionId()) && DataHelper.eq(getEndDate(), msg.getEndDate());
        }
         
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[RequestLeaseMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tTunnels:");
        for (int i = 0; i < getEndpoints(); i++) {
            buf.append("\n\t\tRouterIdentity: ").append(getRouter(i));
            buf.append("\n\t\tTunnelId: ").append(getTunnelId(i));
        }
        buf.append("\n\tEndDate: ").append(getEndDate());
        buf.append("]");
        return buf.toString();
    }

    private static class TunnelEndpoint {
        private Hash _router;
        private TunnelId _tunnelId;

        public TunnelEndpoint(Hash router, TunnelId id) {
            _router = router;
            _tunnelId = id;
        }

        public Hash getRouter() {
            return _router;
        }

        public TunnelId getTunnelId() {
            return _tunnelId;
        }
    }
}
