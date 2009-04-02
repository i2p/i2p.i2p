/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

import net.i2p.util.Log;

/**
 * Factory class for creating SOCKS forwarders through I2P
 */
public class SOCKSServerFactory {
    private final static Log _log = new Log(SOCKSServerFactory.class);

    private final static String ERR_REQUEST_DENIED =
        "HTTP/1.1 403 Access Denied\r\n" +
        "Content-Type: text/html; charset=iso-8859-1\r\n" +
        "Cache-control: no-cache\r\n" +
        "\r\n" +
        "<html><body><H1>I2P SOCKS PROXY ERROR: REQUEST DENIED</H1>" +
        "Your browser is misconfigured. This is a SOCKS proxy, not a HTTP proxy" +
        "</body></html>";
    
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
            case 0x04:
                // SOCKS version 4/4a
                serv = new SOCKS4aServer(s);
                break;
            case 0x05:
                // SOCKS version 5
                serv = new SOCKS5Server(s);
                break;
            case 'C':
            case 'G':
            case 'H':
            case 'P':
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                out.write(ERR_REQUEST_DENIED.getBytes());
                throw new SOCKSException("HTTP request to socks");
            default:
                throw new SOCKSException("SOCKS protocol version not supported (" + Integer.toHexString(socksVer) + ")");
            }
        } catch (IOException e) {
            _log.debug("error reading SOCKS protocol version");
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        return serv;
    }
}
