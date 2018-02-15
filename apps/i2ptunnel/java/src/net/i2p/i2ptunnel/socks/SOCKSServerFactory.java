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
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.socks.SOCKSException;

/**
 * Factory class for creating SOCKS forwarders through I2P
 */
class SOCKSServerFactory {

    private final static String ERR_REQUEST_DENIED =
        "HTTP/1.1 403 Access Denied - This is a SOCKS proxy, not a HTTP proxy\r\n" +
        "Content-Type: text/html; charset=iso-8859-1\r\n" +
        "Cache-Control: no-cache\r\n" +
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
     * @param props non-null
     */
    public static SOCKSServer createSOCKSServer(I2PAppContext ctx, Socket s, Properties props) throws SOCKSException {
        SOCKSServer serv;

        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            int socksVer = in.readByte();

            switch (socksVer) {
            case 0x04:
                // SOCKS version 4/4a
                if (Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_AUTH)) &&
                    props.containsKey(I2PTunnelHTTPClientBase.PROP_USER) &&
                    props.containsKey(I2PTunnelHTTPClientBase.PROP_PW)) {
                    throw new SOCKSException("SOCKS 4/4a not supported when authorization is required");
                }
                serv = new SOCKS4aServer(ctx, s, props);
                break;
            case 0x05:
                // SOCKS version 5
                serv = new SOCKS5Server(ctx, s, props);
                break;
            case 'C':
            case 'G':
            case 'H':
            case 'P':
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                out.write(DataHelper.getASCII(ERR_REQUEST_DENIED));
                throw new SOCKSException("HTTP request to socks");
            default:
                throw new SOCKSException("SOCKS protocol version not supported (" + Integer.toHexString(socksVer) + ")");
            }
        } catch (IOException e) {
            //_log.debug("error reading SOCKS protocol version");
            throw new SOCKSException("Connection error", e);
        }

        return serv;
    }
}
