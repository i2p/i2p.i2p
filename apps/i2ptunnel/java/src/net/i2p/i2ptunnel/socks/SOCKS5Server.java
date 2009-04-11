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
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/*
 * Class that manages SOCKS5 connections, and forwards them to
 * destination hosts or (eventually) some outproxy.
 *
 * @author human
 */
public class SOCKS5Server extends SOCKSServer {
    private static final Log _log = new Log(SOCKS5Server.class);

    private static final int SOCKS_VERSION_5 = 0x05;

    private Socket clientSock = null;
    private boolean setupCompleted = false;

    /**
     * Create a SOCKS5 server that communicates with the client using
     * the specified socket.  This method should not be invoked
     * directly: new SOCKS5Server objects should be created by using
     * SOCKSServerFactory.createSOCSKServer().  It is assumed that the
     * SOCKS VER field has been stripped from the input stream of the
     * client socket.
     *
     * @param clientSock client socket
     */
    public SOCKS5Server(Socket clientSock) {
        this.clientSock = clientSock;
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
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }

        setupCompleted = true;
    }

    /**
     * SOCKS5 connection initialization.  This method assumes that
     * SOCKS "VER" field has been stripped from the input stream.
     */
    private void init(DataInputStream in, DataOutputStream out) throws IOException, SOCKSException {
        int nMethods = in.readByte() & 0xff;
        boolean methodOk = false;
        int method = Method.NO_ACCEPTABLE_METHODS;

        for (int i = 0; i < nMethods; ++i) {
            method = in.readByte() & 0xff;
            if (method == Method.NO_AUTH_REQUIRED) {
                // That's fine, we do support this method
                break;
            }
        }

        boolean canContinue = false;
        switch (method) {
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
     * SOCKS5 request management.  This method assumes that all the
     * stuff preceding or enveloping the actual request (e.g. protocol
     * initialization, integrity/confidentiality encapsulations, etc)
     * has been stripped out of the input/output streams.
     */
    private int manageRequest(DataInputStream in, DataOutputStream out) throws IOException, SOCKSException {
        int socksVer = in.readByte() & 0xff;
        if (socksVer != SOCKS_VERSION_5) {
            _log.debug("error in SOCKS5 request (protocol != 5? wtf?)");
            throw new SOCKSException("Invalid protocol version in request");
        }

        int command = in.readByte() & 0xff;
        switch (command) {
        case Command.CONNECT:
            break;
        case Command.BIND:
            _log.debug("BIND command is not supported!");
            sendRequestReply(Reply.COMMAND_NOT_SUPPORTED, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            throw new SOCKSException("BIND command not supported");
        case Command.UDP_ASSOCIATE:
          /*** if(!Boolean.valueOf(tunnel.getOptions().getProperty("i2ptunnel.socks.allowUDP")).booleanValue()) {
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

        {
            // Reserved byte, should be 0x00
            byte rsv = in.readByte();
        }

        int addressType = in.readByte() & 0xff;
        switch (addressType) {
        case AddressType.IPV4:
            connHostName = new String("");
            for (int i = 0; i < 4; ++i) {
                int octet = in.readByte() & 0xff;
                connHostName += Integer.toString(octet);
                if (i != 3) {
                    connHostName += ".";
                }
            }
            if (command != Command.UDP_ASSOCIATE)
                _log.warn("IPV4 address type in request: " + connHostName + ". Is your client secure?");
            break;
        case AddressType.DOMAINNAME:
            {
                int addrLen = in.readByte() & 0xff;
                if (addrLen == 0) {
                    _log.debug("0-sized address length? wtf?");
                    throw new SOCKSException("Illegal DOMAINNAME length");
                }
                byte addr[] = new byte[addrLen];
                in.readFully(addr);
                connHostName = new String(addr);
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
        DataInputStream in;
        DataOutputStream out;
        try {
            out = new DataOutputStream(clientSock.getOutputStream());

            sendRequestReply(Reply.SUCCEEDED, AddressType.IPV4, InetAddress.getByName("127.0.0.1"), null, 1, out);
        } catch (IOException e) {
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
        }
    }

    /**
     * Send the specified reply during SOCKS5 initialization
     */
    private void sendInitReply(int replyCode, DataOutputStream out) throws IOException {
        ByteArrayOutputStream reps = new ByteArrayOutputStream();

        reps.write(SOCKS_VERSION_5);
        reps.write(replyCode);

        byte[] reply = reps.toByteArray();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Sending init reply:\n" + HexDump.dump(reply));
        }

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
            _log.error("unknown address type passed to sendReply() (" + Integer.toHexString(addressType) + ")! wtf?");
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
            throw new SOCKSException("Connection error (" + e.getMessage() + ")");
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
                Destination dest = I2PTunnel.destFromName(connHostName);
                if (dest == null) {
                    try {
                        sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                    } catch (IOException ioe) {}
                    throw new SOCKSException("Host not found");
                }
                destSock = t.createI2PSocket(I2PTunnel.destFromName(connHostName));
            } else if ("localhost".equals(connHostName) || "127.0.0.1".equals(connHostName)) {
                String err = "No localhost accesses allowed through the Socks Proxy";
                _log.error(err);
                try {
                    sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                } catch (IOException ioe) {}
                throw new SOCKSException(err);
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
            } else {
                List<String> proxies = t.getProxies(connPort);
                if (proxies == null || proxies.size() <= 0) {
                    String err = "No outproxy configured for port " + connPort + " and no default configured either";
                    _log.error(err);
                    try {
                        sendRequestReply(Reply.CONNECTION_NOT_ALLOWED_BY_RULESET, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
                    } catch (IOException ioe) {}
                    throw new SOCKSException(err);
                }
                int p = I2PAppContext.getGlobalContext().random().nextInt(proxies.size());
                String proxy = proxies.get(p);
                _log.debug("connecting to port " + connPort + " proxy " + proxy + " for " + connHostName + "...");
                // this isn't going to work, these need to be socks outproxies so we need
                // to do a socks session to them?
                destSock = t.createI2PSocket(I2PTunnel.destFromName(proxy));
            }
            confirmConnection();
            _log.debug("connection confirmed - exchanging data...");
        } catch (DataFormatException e) {
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error in destination format");
        } catch (SocketException e) {
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (IOException e) {
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        } catch (I2PException e) {
            try {
                sendRequestReply(Reply.HOST_UNREACHABLE, AddressType.DOMAINNAME, null, "0.0.0.0", 0, out);
            } catch (IOException ioe) {}
            throw new SOCKSException("Error connecting ("
                                     + e.getMessage() + ")");
        }

        return destSock;
    }

    // This isn't really the right place for this, we can't stop the tunnel once it starts.
    static SOCKSUDPTunnel _tunnel;
    static final Object _startLock = new Object();
    static byte[] dummyIP = new byte[4];
    /**
     * We got a UDP associate command.
     * Loop here looking for more, never return normally,
     * or else I2PSocksTunnel will create a streaming lib connection.
     *
     * Do UDP Socks clients actually send more than one Associate request?
     * RFC 1928 isn't clear... maybe not.
     */
    private void handleUDP(DataInputStream in, DataOutputStream out) throws SOCKSException {
        List<Integer> ports = new ArrayList(1);
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
            catch (SOCKSException ioe) { break; }
        }

        for (Integer i : ports)
            _tunnel.remove(i);

        // Prevent I2PSocksTunnel from calling getDestinationI2PSocket() above
        // to create a streaming lib connection...
        // This isn't very elegant...
        //
        throw new SOCKSException("End of UDP Processing");
    }

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    private static class Method {
        private static final int NO_AUTH_REQUIRED = 0x00;
        private static final int NO_ACCEPTABLE_METHODS = 0xff;
    }

    private static class AddressType {
        private static final int IPV4 = 0x01;
        private static final int DOMAINNAME = 0x03;
        private static final int IPV6 = 0x04;
    }

    private static class Command {
        private static final int CONNECT = 0x01;
        private static final int BIND = 0x02;
        private static final int UDP_ASSOCIATE = 0x03;
    }

    private static class Reply {
        private static final int SUCCEEDED = 0x00;
        private static final int GENERAL_SOCKS_SERVER_FAILURE = 0x01;
        private static final int CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
        private static final int NETWORK_UNREACHABLE = 0x03;
        private static final int HOST_UNREACHABLE = 0x04;
        private static final int CONNECTION_REFUSED = 0x05;
        private static final int TTL_EXPIRED = 0x06;
        private static final int COMMAND_NOT_SUPPORTED = 0x07;
        private static final int ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
    }
}
