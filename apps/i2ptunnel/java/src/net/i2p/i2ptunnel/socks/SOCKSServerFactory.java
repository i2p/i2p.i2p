/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;

import net.i2p.util.Log;

/**
 * Factory class for creating SOCKS forwarders through I2P
 */
public class SOCKSServerFactory {
    private final static Log _log = new Log(SOCKSServerFactory.class);

    /**
     * Create a new SOCKS server, using the provided socket (that must
     * be connected to a client) to select the proper SOCKS protocol
     * version.  This method wil strip the SOCKS VER field from the
     * provided sockets's input stream.
     *
     * @param s a Socket used to choose the SOCKS server type
     */
    public static SOCKSServer createSOCKSServer(Socket s) throws SOCKSException {
        SOCKSServer serv;

        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            int socksVer = in.readByte();

            switch (socksVer) {
            case 0x05:
                // SOCKS version 5
                serv = new SOCKS5Server(s);
                break;
            default:
                _log.debug("SOCKS protocol version not supported (" + Integer.toHexString(socksVer) + ")");
                return null;
            }
        } catch (IOException e) {
            _log.debug("error reading SOCKS protocol version");
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        return serv;
    }
}