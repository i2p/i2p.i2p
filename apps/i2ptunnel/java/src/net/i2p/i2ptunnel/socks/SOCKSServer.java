/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.i2ptunnel.I2PTunnel;
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

    I2PSocket destSocket = null;

    Object FIXME = new Object();

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
    public I2PSocket getDestinationI2PSocket(I2PSOCKSTunnel t) throws SOCKSException {
        setupServer();

        if (connHostName == null) {
            _log.error("BUG: destination host name has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }
        if (connPort == 0) {
            _log.error("BUG: destination port has not been initialized!");
            throw new SOCKSException("BUG! See the logs!");
        }

        // FIXME: here we should read our config file, select an
        // outproxy, and instantiate the proper socket class that
        // handles the outproxy itself (SOCKS4a, SOCKS5, HTTP CONNECT...).
        I2PSocket destSock;

        try {
            if (connHostName.toLowerCase().endsWith(".i2p")) {
                _log.debug("connecting to " + connHostName + "...");
                // Let's not due a new Dest for every request, huh?
                //I2PSocketManager sm = I2PSocketManagerFactory.createManager();
                //destSock = sm.connect(I2PTunnel.destFromName(connHostName), null);
                // TODO get the streaming lib options in there
                destSock = t.createI2PSocket(I2PTunnel.destFromName(connHostName));
                confirmConnection();
                _log.debug("connection confirmed - exchanging data...");
            } else {
                _log.error("We don't support outproxies (yet)");
                throw new SOCKSException("Ouproxies not supported (yet)");
            }
        } catch (DataFormatException e) {
            throw new SOCKSException("Error in destination format");
        } catch (SocketException e) {
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (IOException e) {
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (I2PException e) {
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        }

        return destSock;
    }
}
