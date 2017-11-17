/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.net.Socket;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.socks.SOCKSException;
import net.i2p.util.Log;

/**
 * Abstract base class used by all SOCKS servers.
 *
 * @author human
 */
abstract class SOCKSServer {

    private static final String PROP_MAPPING_PREFIX = "ipmapping.";

    /* Details about the connection requested by client */
    protected String connHostName;
    protected int connPort;
    protected int addressType;

    protected final I2PAppContext _context;
    protected final Socket clientSock;
    protected final Properties props;
    protected final Log _log;

    /** @since 0.9.27 */
    protected SOCKSServer(I2PAppContext ctx, Socket clientSock, Properties props) {
        _context = ctx;
        this.clientSock = clientSock;
        this.props = props;
        _log = ctx.logManager().getLog(getClass());
    }

    /**
     * IP to domain name mapping support. This matches the given IP string
     * against a user-set list of mappings. This enables applications which do
     * not properly support the SOCKS5 DOMAINNAME feature to be used with I2P.
     * @param ip The IP address to check.
     * @return   The domain name if a mapping is found, or null otherwise.
     * @since 0.9.5
     */
    protected String getMappedDomainNameForIP(String ip) {
        if (props.containsKey(PROP_MAPPING_PREFIX + ip))
            return props.getProperty(PROP_MAPPING_PREFIX + ip);
        return null;
    }

    /**
     * Perform server initialization (expecially regarding protected
     * variables).
     */
    protected abstract void setupServer() throws SOCKSException;

    /**
     * Get a socket that can be used to send/receive 8-bit clean data
     * to/from the client.
     *
     * @return a Socket connected with the client
     */
    public abstract Socket getClientSocket() throws SOCKSException;

    /**
     * Confirm to the client that the connection has succeeded
     */
    protected abstract void confirmConnection() throws SOCKSException;

    /**
     * Get an I2PSocket that can be used to send/receive 8-bit clean data
     * to/from the destination of the SOCKS connection.
     *
     * @return an I2PSocket connected with the destination
     */
    public abstract I2PSocket getDestinationI2PSocket(I2PSOCKSTunnel t) throws SOCKSException;

    /**
     *  @since 0.9.27
     */
    private boolean shouldUseOutproxyPlugin() {
        return Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_USE_OUTPROXY_PLUGIN, "true"));
    }

    /**
     *  @return null if disabled or not installed
     *  @since 0.9.27
     */
    protected Outproxy getOutproxyPlugin() {
        if (shouldUseOutproxyPlugin()) {
            ClientAppManager mgr = _context.clientAppManager();
            if (mgr != null) {
                ClientApp op = mgr.getRegisteredApp(Outproxy.NAME);
                if (op != null)
                    return (Outproxy) op;
            }
        }
        return null;
    }
}
