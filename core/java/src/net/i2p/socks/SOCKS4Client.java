/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.socks;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import static net.i2p.socks.SOCKS4Constants.*;
import net.i2p.util.Addresses;

/**
 *  A simple SOCKS 4/4a client.
 *  Note: Caller is advised to setSoTimeout on the socket. Not done here.
 *
 *  @since 0.9.33 adapted from net.i2p.i2ptunnel.socks.SOCKS5Server
 */
public class SOCKS4Client {

    private SOCKS4Client() {}

    /**
     *  Act as a SOCKS 4 client to connect to a proxy
     *
     *  Will throw and close sock on all errors.
     *  Caller must close sock on success.
     *
     *  @param sock socket to the proxy
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     */
    public static void connect(Socket sock, String connHostName, int connPort) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = sock.getInputStream();
            out = sock.getOutputStream();
            connect(in, out, connHostName, connPort);
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ioe) {}
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            throw e;
        }
    }

    /**
     *  Act as a SOCKS 4 client to connect to a proxy
     *
     *  Will throw and close pin and pout on all errors.
     *  Caller must close pin and pout on success.
     *
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     */
    public static void connect(InputStream pin, OutputStream pout, String connHostName, int connPort) throws IOException {
        DataOutputStream out = null;
        DataInputStream in = null;
        try {
            out = new DataOutputStream(pout);

            // send the init
            out.writeByte(SOCKS_VERSION_4);
            out.writeByte(Command.CONNECT);
            out.writeShort(connPort);
            boolean isIPv4;
            if (Addresses.isIPv4Address(connHostName)) {
                isIPv4 = true;
                out.write(InetAddress.getByName(connHostName).getAddress());
            } else if (Addresses.isIPv6Address(connHostName)) {
                throw new SOCKSException("IPv6 not supported in SOCKS 4");
            } else {
                isIPv4 = false;
                out.writeInt(1); // 0.0.0.1
            }
            out.writeByte(0);    // empty username
            if (!isIPv4) {
                byte[] d = connHostName.getBytes("ISO-8859-1");
                out.write(d);
                out.writeByte(0);
            }
            out.flush();

            // read init reply
            in = new DataInputStream(pin);
            in.readByte();  // dummy
            int reply = in.readByte();
            if (reply != Reply.SUCCEEDED)
                throw new SOCKSException("Proxy rejected request, response = " + reply);
            // throw away the address in the response
            // todo pass the response through?
            in.readShort(); // port
            in.readInt();   // IP
        } catch (IOException e) {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            throw e;
        }
    }
}
