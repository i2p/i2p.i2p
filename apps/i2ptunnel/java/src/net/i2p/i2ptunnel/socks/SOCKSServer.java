/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.net.Socket;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;

/**
 * Abstract base class used by all SOCKS servers.
 *
 * @author human
 */
public abstract class SOCKSServer {
    private static final Log _log = new Log(SOCKSServer.class);

    /* Details about the connection requested by client */
    protected String connHostName = null;
    protected int connPort = 0;

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

}
