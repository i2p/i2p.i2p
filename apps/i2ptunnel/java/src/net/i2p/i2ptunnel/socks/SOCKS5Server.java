/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
import net.i2p.i2ptunnel.I2PTunnel;
import static net.i2p.socks.SOCKS5Constants.*;
import net.i2p.util.HexDump;
import net.i2p.util.Log;
import net.i2p.socks.SOCKS5Client;
import net.i2p.socks.SOCKSException;

/*
 * Class that manages SOCKS5 connections, and forwards them to
 * destination hosts or (eventually) some outproxy.
 *
 * @author human
 */
class SOCKS5Server extends SOCKSServer {

    private boolean setupCompleted = false;
    private final boolean authRequired;

    /**
     * Create a SOCKS5 server that communicates with the client using
     * the specified socket.  This method should not be invoked
     * directly: new SOCKS5Server objects should be created by using
     * SOCKSServerFactory.createSOCSKServer().  It is assumed that the
     * SOCKS VER field has been stripped from the input stream of the
     * client socket.
     *
     * @param clientSock client socket
     * @param props non-null
     */
    public SOCKS5Server(I2PAppContext ctx, Socket clientSock, Properties props) {
        super(ctx, clientSock, props);
        this.authRequired =
                    Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_AUTH)) &&
                    props.containsKey(I2PTunnelHTTPClientBase.PROP_USER) &&
                    props.containsKey(I2PTunnelHTTPClientBase.PROP_PW);
    }

    public Socket getClientSocket() throws SOCKSException {
        setupServer();

        return clientSock;
    }

    protected void setupServer() throws SOCKSException {
        if (setupCompleted) { return; }

        DataInputStream in;
        DataOutputStream out;
        try {
            in = new DataInputStream(clientSock.getInputStream());
            out = new DataOutputStream(clientSock.getOutputStream());

            init(in, out);
            if (manageRequest(in, out) == Command.UDP_ASSOCIATE)
                handleUDP(in, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }

        setupCompleted = true;
    }

    /**
     * SOCKS5 connection initialization.  This method assumes that
     * SOCKS "VER" field has been stripped from the input stream.
     */
    private void init(DataInputStream in, DataOutputStream out) throws IOException {
        int nMethods = in.readUnsignedByte();
        int method = Method.NO_ACCEPTABLE_METHODS;

        for (int i = 0; i < nMethods; ++i) {
            int meth = in.readUnsignedByte();
            if (((!authRequired) && meth == Method.NO_AUTH_REQUIRED) ||
                (authRequired && meth == Method.USERNAME_PASSWORD)) {
                // That's fine, we do support this method
                method  = meth;
            }
        }

        switch (method) {
          case Method.USERNAME_PASSWORD:
            _log.debug("username/password authentication required");
            sendInitReply(Method.USERNAME_PASSWORD, out);
            verifyPassword(in, out);
            return;
          case Method.NO_AUTH_REQUIRED:
            _log.debug("no authentication required");
            sendInitReply(Method.NO_AUTH_REQUIRED, out);
            return;
          default:
            _log.debug("no suitable authentication methods found (" + Integer.toHexString(method) + ")");
            sendInitReply(Method.NO_ACCEPTABLE_METHODS, out);
            throw new SOCKSException("Unsupported authentication method");
        }
    }

    /**
     * Wait for the username/password message and verify or throw SOCKSException on failure
     * @since 0.8.2
     */
    private void verifyPassword(DataInputStream in, DataOutputStream out) throws IOException {
        int c = in.readUnsignedByte();
        if (c != AUTH_VERSION) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed");
            throw new SOCKSException("Unsupported authentication version");
        }
        c = in.readUnsignedByte();
        if (c <= 0) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed");
            throw new SOCKSException("Bad authentication");
        }
        byte[] user = new byte[c];
        String u = new String(user, "UTF-8");
        in.readFully(user);
        c = in.readUnsignedByte();
        if (c <= 0) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed, user: " + u);
            throw new SOCKSException("Bad authentication");
        }
        byte[] pw = new byte[c];
        in.readFully(pw);
        // Hopefully these are in UTF-8, since that's what our config file is in
        // these throw UnsupportedEncodingException which is an IOE
        String p = new String(pw, "UTF-8");
        String configUser =  props.getProperty(I2PTunnelHTTPClientBase.PROP_USER);
        String configPW = props.getProperty(I2PTunnelHTTPClientBase.PROP_PW);
        if ((!u.equals(configUser)) || (!p.equals(configPW))) {
            _log.logAlways(Log.WARN, "SOCKS proxy authentication failed, user: " + u);
            sendAuthReply(AUTH_FAILURE, out);
            throw new SOCKSException("SOCKS authorization failure");
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("SOCKS authorization success, user: " + u);
        sendAuthReply(AUTH_SUCCESS, out);
    }

    /**
     * SOCKS5 request management.  This method assumes that all the
     * stuff preceding or enveloping the actual request (e.g. protocol
     * initialization, integrity/confidentiality encapsulations, etc)
     * has been stripped out of the input/output streams.
     */
    private int manageRequest(DataInputStream in, DataOutputStream out) throws IOException {
        int socksVer = in.readUnsignedByte();
        if (socksVer != SOCKS_VERSION_5) {
            _log.debug("error in SOCKS5 request (protocol != 5?)");
            throw new SOCKSException("Invalid protocol version in request: " + socksVer);
        }

        int command = in.readUnsignedByte();
        switch (command) {
        case Command.CONNECT:
            break;
        case Command.BIND:
            _log.debug("BIND command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("BIND command not supported");
        case Command.UDP_ASSOCIATE:
          /*** if(!Boolean.parseBoolean(tunnel.getOptions().getProperty("i2ptunnel.socks.allowUDP"))) {
            _log.debug("UDP ASSOCIATE command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("UDP ASSOCIATE command not supported");
           ***/
            break;
        default:
            _log.debug("unknown command in request (" + Integer.toHexString(command) + ")");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Invalid command in request");
        }

        // Reserved byte, should be 0x00
        in.readByte();

        addressType = in.readUnsignedByte();
        switch (addressType) {
        case AddressType.IPV4:
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4; ++i) {
                int octet = in.readUnsignedByte();
                builder.append(Integer.toString(octet));
                if (i != 3) {
                    builder.append(".");
                }
            }
            connHostName = builder.toString();
            // Check if the requested IP should be mapped to a domain name
            String mappedDomainName = getMappedDomainNameForIP(connHostName);
            if (mappedDomainName != null) {
                _log.debug("IPV4 address " + connHostName + " was mapped to domain name " + mappedDomainName);
                addressType = AddressType.DOMAINNAME;
                connHostName = mappedDomainName;
            } else if (command != Command.UDP_ASSOCIATE)
                _log.warn("IPV4 address type in request: " + connHostName + ". Is your client secure?");
            break;
        case AddressType.DOMAINNAME:
            {
                int addrLen = in.readUnsignedByte();
                if (addrLen == 0) {
                    _log.debug("0-sized address length?");
                    throw new SOCKSException("Illegal DOMAINNAME length");
                }
                byte addr[] = new byte[addrLen];
                in.readFully(addr);
                connHostName = DataHelper.getUTF8(addr);
            }
            _log.debug("DOMAINNAME address type in request: " + connHostName);
            break;
        case AddressType.IPV6:
            if (command != Command.UDP_ASSOCIATE) {
                _log.warn("IP V6 address type in request! Is your client secure?" + " (IPv6 is not supported, anyway :-)");
                sendRequestReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                throw new SOCKSException("IPV6 addresses not supported");
            }
            break;
        default:
            _log.debug("unknown address type in request (" + Integer.toHexString(command) + ")");
            sendRequestReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Invalid addresses type in request");
        }

        connPort = in.readUnsignedShort();
        if (connPort == 0) {
            _log.debug("trying to connect to TCP port 0?  Dropping!");
            sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("Invalid port number in request");
        }
        return command;
    }

    protected void confirmConnection() throws SOCKSException {
        DataOutputStream out;
        try {
            out = new DataOutputStream(clientSock.getOutputStream());

            sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, 1, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }
    }

    /**
     * Send the specified reply during SOCKS5 initialization
     */
    private void sendInitReply(int replyCode, DataOutputStream out) throws IOException {
        byte[] reply = new byte[2];
        reply[0] = SOCKS_VERSION_5;
        reply[1] = (byte) replyCode;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending init reply:\n" + HexDump.dump(reply));
        out.write(reply);
    }

    /**
     * Send the specified reply during SOCKS5 authorization
     * @since 0.8.2
     */
    private void sendAuthReply(int replyCode, DataOutputStream out) throws IOException {
        byte[] reply = new byte[2];
        reply[0] = AUTH_VERSION;
        reply[1] = (byte) replyCode;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending auth reply:\n" + HexDump.dump(reply));
        out.write(reply);
    }

    /**
     * Send the specified reply to a request of the client.  Either
     * one of inetAddr or domainName can be null, depending on
     * addressType.
     */
    private void sendRequestReply(int replyCode, int addressType, InetAddress inetAddr, String domainName,
                                  int bindPort, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();
        DataOutputStream dreps = new DataOutputStream(reps);

        dreps.write(SOCKS_VERSION_5);
        dreps.write(replyCode);

        // Reserved byte, should be 0x00
        dreps.write(0x00);

        dreps.write(addressType);

        switch (addressType) {
        case AddressType.IPV4:
            dreps.write(inetAddr.getAddress());
            break;
        case AddressType.DOMAINNAME:
            dreps.writeByte(domainName.length());
            dreps.writeBytes(domainName);
            break;
        default:
            _log.error("unknown address type passed to sendReply() (" + Integer.toHexString(addressType) + ")!");
            return;
        }

        dreps.writeShort(bindPort);

        byte[] reply = reps.toByteArray();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Sending request reply:\n" + HexDump.dump(reply));
        }

        out.write(reply);
    }

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

        DataOutputStream out; // for errors
        try {
            out = new DataOutputStream(clientSock.getOutputStream());
        } catch (IOException e) {
            throw new SOCKSException("Connection error", e);
        }

        // FIXME: here we should read our config file, select an
        // outproxy, and instantiate the proper socket class that
        // handles the outproxy itself (SOCKS4a, SOCKS5, HTTP CONNECT...).
        I2PSocket destSock;

        try {
            if (connHostName.toLowerCase(Locale.US).endsWith(".i2p")) {
                // Let's not do a new Dest for every request, huh?
                //I2PSocketManager sm = I2PSocketManagerFactory.createManager();
                //destSock = sm.connect(I2PTunnel.destFromName(connHostName), null);
                Destination dest = _context.namingService().lookup(connHostName);
                if (dest == null) {
                    try {
                        sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                    } catch (IOException ioe) {}
                    throw new SOCKSException("Host not found");
                }
                if (_log.shouldDebug())
                    _log.debug("connecting to " + connHostName + "...");
                Properties overrides = new Properties();
                I2PSocketOptions sktOpts = t.buildOptions(overrides);
                sktOpts.setPort(connPort);
                destSock = t.createI2PSocket(dest, sktOpts);
            } else if ("localhost".equals(connHostName) || "127.0.0.1".equals(connHostName)) {
                String err = "No localhost accesses allowed through the Socks Proxy";
                _log.error(err);
                try {
                    sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                } catch (IOException ioe) {}
                throw new SOCKSException(err);
          /****
            } else if (connPort == 80) {
                // rewrite GET line to include hostname??? or add Host: line???
                // or forward to local eepProxy (but that's a Socket not an I2PSocket)
                // use eepProxy configured outproxies?
                String err = "No handler for HTTP outproxy implemented";
                _log.error(err);
                try {
                    sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                } catch (IOException ioe) {}
                throw new SOCKSException(err);
           ****/
            } else {
                Outproxy outproxy = getOutproxyPlugin();
                if (outproxy != null) {
                    // In HTTPClient, we use OutproxyRunner to run a Socket,
                    // but here, we wrap a Socket in a I2PSocket and use the regular Runner.
                    try {
                        destSock = new SocketWrapper(outproxy.connect(connHostName, connPort));
                    } catch (IOException ioe) {
                        try {
                            sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                        } catch (IOException ioe2) {}
                        throw new SOCKSException("connect failed via outproxy plugin", ioe);
                    }
                } else {
                    List<String> proxies = t.getProxies(connPort);
                    if (proxies == null || proxies.isEmpty()) {
                        String err = "No outproxy configured for port " + connPort + " and no default configured either";
                        _log.error(err);
                        try {
                            sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                        } catch (IOException ioe) {}
                        throw new SOCKSException(err);
                    }
                    int p = _context.random().nextInt(proxies.size());
                    String proxy = proxies.get(p);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("connecting to proxy " + proxy + " for " + connHostName + " port " + connPort);
                    try {
                        destSock = outproxyConnect(t, proxy);
                    } catch (SOCKSException se) {
                        try {
                            sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                        } catch (IOException ioe) {}
                        throw se;
                    }
                }
            }
            confirmConnection();
            _log.debug("connection confirmed - exchanging data...");
        } catch (DataFormatException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("socks error", e);
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error in destination format");
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("socks error", e);
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Connection error", e);
        } catch (I2PException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("socks error", e);
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Connection error", e);
        }

        return destSock;
    }

    /**
     *  Act as a SOCKS 5 client to connect to an outproxy
     *  @return open socket or throws error
     *  @since 0.8.2
     */
    private I2PSocket outproxyConnect(I2PSOCKSTunnel tun, String proxy) throws IOException, I2PException {
        Properties overrides = new Properties();
        overrides.setProperty("option.i2p.streaming.connectDelay", "1000");
        I2PSocketOptions proxyOpts = tun.buildOptions(overrides);
        Destination dest = _context.namingService().lookup(proxy);
        if (dest == null)
            throw new SOCKSException("Outproxy not found");
        I2PSocket destSock = tun.createI2PSocket(dest, proxyOpts);
        OutputStream out = null;
        InputStream in = null;
        try {
            out = destSock.getOutputStream();
            in = destSock.getInputStream();
            boolean authAvail = Boolean.parseBoolean(props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_AUTH));
            String configUser =  null;
            String configPW = null;
            if (authAvail) {
                configUser =  props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER_PREFIX + proxy);
                configPW = props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW_PREFIX + proxy);
                if (configUser == null || configPW == null) {
                    configUser =  props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_USER);
                    configPW = props.getProperty(I2PTunnelHTTPClientBase.PROP_OUTPROXY_PW);
                }
            }
            SOCKS5Client.connect(in, out, connHostName, connPort, configUser, configPW);
        } catch (IOException e) {
            try { destSock.close(); } catch (IOException ioe) {}
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            throw e;
        }
        // that's it, caller will send confirmation to our client
        return destSock;
    }

    // This isn't really the right place for this, we can't stop the tunnel once it starts.
    private static SOCKSUDPTunnel _tunnel;
    private static final Object _startLock = new Object();
    private static final byte[] dummyIP = new byte[4];

    /**
     * We got a UDP associate command.
     * Loop here looking for more, never return normally,
     * or else I2PSocksTunnel will create a streaming lib connection.
     *
     * Do UDP Socks clients actually send more than one Associate request?
     * RFC 1928 isn't clear... maybe not.
     */
    private void handleUDP(DataInputStream in, DataOutputStream out) throws SOCKSException {
        List<Integer> ports = new ArrayList<Integer>(1);
        synchronized (_startLock) {
            if (_tunnel == null) {
                // tunnel options?
                _tunnel = new SOCKSUDPTunnel(new I2PTunnel());
                _tunnel.startRunning();
            }
        }
        while (true) {
            // Set it up. connHostName and connPort are the client's info.
            InetAddress ia = null;
            try {
                ia = InetAddress.getByAddress(connHostName, dummyIP);
            } catch (UnknownHostException uhe) {} // won't happen, no resolving done here
            int myPort = _tunnel.add(ia, connPort);
            ports.add(Integer.valueOf(myPort));
            try {
                sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, myPort, out);
            } catch (IOException ioe) { break; }

            // wait for more ???
            try {
                int command = manageRequest(in, out);
                // don't do this...
                if (command != Command.UDP_ASSOCIATE)
                    break;
            } catch (IOException ioe) { break; }
        }

        for (Integer i : ports)
            _tunnel.remove(i);

        // Prevent I2PSocksTunnel from calling getDestinationI2PSocket() above
        // to create a streaming lib connection...
        // This isn't very elegant...
        //
        throw new SOCKSException("End of UDP Processing");
    }
}
