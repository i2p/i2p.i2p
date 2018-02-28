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

import static net.i2p.socks.SOCKS5Constants.*;
import net.i2p.util.Addresses;

/**
 *  A simple SOCKS 5 client.
 *  Note: Caller is advised to setSoTimeout on the socket. Not done here.
 *
 *  @since 0.9.33 adapted from net.i2p.i2ptunnel.socks.SOCKS5Server
 */
public class SOCKS5Client {

    private SOCKS5Client() {}

    /**
     *  Act as a SOCKS 5 client to connect to a proxy
     *
     *  Will throw and close sock on all errors.
     *  Caller must close sock on success.
     *
     *  @param sock socket to the proxy
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     */
    public static void connect(Socket sock, String connHostName, int connPort) throws IOException {
        connect(sock, connHostName, connPort, null, null);
    }

    /**
     *  Act as a SOCKS 5 client to connect to a proxy
     *
     *  Will throw and close sock on all errors.
     *  Caller must close sock on success.
     *
     *  @param sock socket to the proxy
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     *  @param configUser username for proxy authentication or null
     *  @param configPW password for proxy authentication or null
     */
    public static void connect(Socket sock, String connHostName, int connPort, String configUser, String configPW) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = sock.getInputStream();
            out = sock.getOutputStream();
            connect(in, out, connHostName, connPort, configUser, configPW);
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ioe) {}
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            throw e;
        }
    }

    /**
     *  Act as a SOCKS 5 client to connect to a proxy
     *
     *  Will throw and close pin and pout on all errors.
     *  Caller must close pin and pout on success.
     *
     *  @param pin input stream from the proxy
     *  @param pout output stream to the proxy
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     */
    public static void connect(InputStream pin, OutputStream pout, String connHostName, int connPort) throws IOException {
        connect(pin, pout, connHostName, connPort, null, null);
    }

    /**
     *  Act as a SOCKS 5 client to connect to a proxy
     *
     *  Will throw and close pin and pout on all errors.
     *  Caller must close pin and pout on success.
     *
     *  @param pin input stream from the proxy
     *  @param pout output stream to the proxy
     *  @param connHostName hostname for the proxy to connect to
     *  @param connPort port for the proxy to connect to
     *  @param configUser username for proxy authentication or null
     *  @param configPW password for proxy authentication or null
     */
    public static void connect(InputStream pin, OutputStream pout, String connHostName, int connPort, String configUser, String configPW) throws IOException {
        DataOutputStream out = null;
        DataInputStream in = null;
        try {
            out = new DataOutputStream(pout);
            boolean authAvail = configUser != null && configPW != null;

            // send the init
            out.writeByte(SOCKS_VERSION_5);
            if (authAvail) {
                out.writeByte(2);
                out.writeByte(Method.NO_AUTH_REQUIRED);
                out.writeByte(Method.USERNAME_PASSWORD);
            } else {
                out.writeByte(1);
                out.writeByte(Method.NO_AUTH_REQUIRED);
            }
            out.flush();

            // read init reply
            in = new DataInputStream(pin);
            // is this right or should we not try to do 5-to-4 conversion?
            int hisVersion = in.readByte();
            if (hisVersion != SOCKS_VERSION_5 /* && addrtype == AddressType.DOMAINNAME */ )
                throw new SOCKSException("SOCKS proxy is not Version 5");
            //else if (hisVersion != 4)
            //    throw new SOCKSException("Unsupported SOCKS Proxy Version");

            int method = in.readByte();
            if (method == Method.NO_AUTH_REQUIRED) {
                // good
            } else if (method == Method.USERNAME_PASSWORD) {
                if (authAvail) {
                    // send the auth
                    out.writeByte(AUTH_VERSION);
                    byte[] user = configUser.getBytes("UTF-8");
                    byte[] pw = configPW.getBytes("UTF-8");
                    out.writeByte(user.length);
                    out.write(user);
                    out.writeByte(pw.length);
                    out.write(pw);
                    out.flush();
                    // read the auth reply
                    if (in.readByte() != AUTH_VERSION)
                        throw new SOCKSException("Bad auth version from proxy");
                    if (in.readByte() != AUTH_SUCCESS)
                        throw new SOCKSException("Proxy authorization failure");
                } else {
                    throw new SOCKSException("Proxy requires authorization, please configure username/password");
                }
            } else {
                throw new SOCKSException("Proxy authorization failure");
            }

            int addressType;
            if (Addresses.isIPv4Address(connHostName))
                addressType = AddressType.IPV4;
            else if (Addresses.isIPv6Address(connHostName))
                addressType = AddressType.IPV6;
            else
                addressType = AddressType.DOMAINNAME;

            // send the connect command
            out.writeByte(SOCKS_VERSION_5);
            out.writeByte(Command.CONNECT);
            out.writeByte(0); // reserved
            out.writeByte(addressType);
            if (addressType == AddressType.IPV4 || addressType == AddressType.IPV6) {
                out.write(InetAddress.getByName(connHostName).getAddress());
            } else {
                byte[] d = connHostName.getBytes("ISO-8859-1");
                out.writeByte(d.length);
                out.write(d);
            }
            out.writeShort(connPort);
            out.flush();

            // read the connect reply
            hisVersion = in.readByte();
            if (hisVersion != SOCKS_VERSION_5)
                throw new SOCKSException("Proxy response is not Version 5");
            int reply = in.readByte();
            in.readByte();  // reserved
            int type = in.readByte();
            int count = 0;
            if (type == AddressType.IPV4) {
                count = 4;
            } else if (type == AddressType.DOMAINNAME) {
                count = in.readUnsignedByte();
            } else if (type == AddressType.IPV6) {
                count = 16;
            } else {
                throw new SOCKSException("Unsupported address type in proxy response");
            }
            byte[] addr = new byte[count];
            in.readFully(addr);  // address
            in.readUnsignedShort();  // port
            if (reply != Reply.SUCCEEDED)
                throw new SOCKSException("Proxy rejected request, response = " + reply);
            // throw away the address in the response
            // todo pass the response through?
        } catch (IOException e) {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            throw e;
        }
    }
}
