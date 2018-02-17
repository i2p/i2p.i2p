package net.i2p.i2ptunnel;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.irc.DCCClientManager;
import net.i2p.i2ptunnel.irc.DCCHelper;
import net.i2p.i2ptunnel.irc.I2PTunnelDCCServer;
import net.i2p.i2ptunnel.irc.IrcInboundFilter;
import net.i2p.i2ptunnel.irc.IrcOutboundFilter;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

/**
 * Todo: Can we extend I2PTunnelClient instead and remove some duplicated code?
 */
public class I2PTunnelIRCClient extends I2PTunnelClientBase {

    /** list of Destination objects that we point at */
    private final List<I2PSocketAddress> _addrs;
    private static final long DEFAULT_READ_TIMEOUT = 5*60*1000; // -1
    protected long readTimeout = DEFAULT_READ_TIMEOUT;
    private final boolean _dccEnabled;
    private I2PTunnelDCCServer _DCCServer;
    private DCCClientManager _DCCClientManager;

    /**
     *  @since 0.8.9
     */
    public static final String PROP_DCC = "i2ptunnel.ircclient.enableDCC";

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     * @param destinations peers we target, comma- or space-separated. Since 0.9.9, each dest may be appended with :port
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelIRCClient(
                              int localPort,
                              String destinations,
                              Logging l, 
                              boolean ownDest,
                              EventDispatcher notifyThis,
                              I2PTunnel tunnel, String pkf) throws IllegalArgumentException {
        super(localPort, 
              ownDest, 
              l, 
              notifyThis, 
              "IRC Client on " + tunnel.listenHost + ':' + localPort, tunnel, pkf);
        
        _addrs = new ArrayList<I2PSocketAddress>(4);
        buildAddresses(destinations);

        if (_addrs.isEmpty()) {
            l.log("No target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way

            // Don't close() here, because it does a removeSession() and then
            // TunnelController can't acquire() it to release() it.
            //close(true);
            // Unfortunately, super() built the whole tunnel before we get here.
            throw new IllegalArgumentException("No valid target destinations found");
            //return;
        }
        
        setName("IRC Client on " + tunnel.listenHost + ':' + localPort);

        _dccEnabled = Boolean.parseBoolean(tunnel.getClientOptions().getProperty(PROP_DCC));
        // TODO add some prudent tunnel options (or is it too late?)

        notifyEvent("openIRCClientResult", "ok");
    }
    
    /** @since 0.9.9 moved from constructor */
    private void buildAddresses(String destinations) {
        if (destinations == null)
            return;
        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        synchronized(_addrs) {
            _addrs.clear();
            while (tok.hasMoreTokens()) {
                String destination = tok.nextToken();
                try {
                    // Try to resolve here but only log if it doesn't.
                    // Note that b32 _addrs will often not be resolvable at instantiation time.
                    // We will try again to resolve in clientConnectionRun()
                    I2PSocketAddress addr = new I2PSocketAddress(destination);
                    _addrs.add(addr);
                    if (addr.isUnresolved()) {
                        String name = addr.getHostName();
                        if (name.length() == 60 && name.endsWith(".b32.i2p"))
                            l.log("Warning - Could not resolve " + name +
                                  ", perhaps it is not up, will retry when connecting.");
                        else
                            l.log("Warning - Could not resolve " + name +
                                  ", you must add it to your address book for it to work.");
                    }
                } catch (IllegalArgumentException iae) {
                     l.log("Bad destination " + destination + " - " + iae);
                }
            }
        }
    }

    protected void clientConnectionRun(Socket s) {
        if (_log.shouldLog(Log.INFO))
            _log.info("New connection local addr is: " + s.getLocalAddress() +
                      " from: " + s.getInetAddress());
        I2PSocket i2ps = null;
        I2PSocketAddress addr = pickDestination();
        try {
            if (addr == null)
                throw new UnknownHostException("No valid destination configured");
            Destination clientDest = addr.getAddress();
            if (clientDest == null)
                throw new UnknownHostException("Could not resolve " + addr.getHostName());
            int port = addr.getPort();
            i2ps = createI2PSocket(clientDest, port);
            i2ps.setReadTimeout(readTimeout);
            StringBuffer expectedPong = new StringBuffer();
            DCCHelper dcc = _dccEnabled ? new DCC(s.getLocalAddress().getAddress()) : null;
            Thread in = new I2PAppThread(new IrcInboundFilter(s,i2ps, expectedPong, _log, dcc), "IRC Client " + _clientId + " in", true);
            in.start();
            //Thread out = new I2PAppThread(new IrcOutboundFilter(s,i2ps, expectedPong, _log, dcc), "IRC Client " + _clientId + " out", true);
            Runnable out = new IrcOutboundFilter(s,i2ps, expectedPong, _log, dcc);
            // we are called from an unlimited thread pool, so run inline
            //out.start();
            out.run();
        } catch (IOException ex) {
            // generally NoRouteToHostException
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error connecting", ex);
            //l.log("Error connecting: " + ex.getMessage());
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                String name = addr != null ? addr.getHostName() : "undefined";
                String msg = ":" + name + " 499 you :" + ex + "\r\n";
                s.getOutputStream().write(DataHelper.getUTF8(msg));
            } catch (IOException ioe) {}
        } catch (I2PException ex) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error connecting", ex);
            //l.log("Error connecting: " + ex.getMessage());
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                String name = addr != null ? addr.getHostName() : "undefined";
                String msg = ":" + name + " 499 you :" + ex + "\r\n";
                s.getOutputStream().write(DataHelper.getUTF8(msg));
            } catch (IOException ioe) {}
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (i2ps != null) {
                try { i2ps.close(); } catch (IOException ioe) {}
                synchronized (sockLock) {
                    mySockets.remove(i2ps);
                }
            }
        }

    }
    
    private final I2PSocketAddress pickDestination() {
        synchronized(_addrs) {
            int size = _addrs.size();
            if (size <= 0) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("No client targets?!");
                return null;
            }
            if (size == 1) // skip the rand in the most common case
                return _addrs.get(0);
            int index = _context.random().nextInt(size);
            return _addrs.get(index);
        }
    }

    /**
     *  Update the dests then call super.
     *
     *  @since 0.9.9
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String targets = props.getProperty("targetDestination");
        buildAddresses(targets);
        super.optionsUpdated(tunnel);
    }

    @Override
    public void startRunning() {
        super.startRunning();
        if (open)
            _context.portMapper().register(PortMapper.SVC_IRC, getTunnel().listenHost, getLocalPort());
    }

    @Override
    public boolean close(boolean forced) {
        int reg = _context.portMapper().getPort(PortMapper.SVC_IRC);
        if (reg == getLocalPort())
            _context.portMapper().unregister(PortMapper.SVC_IRC);
        synchronized(this) {
            if (_DCCServer != null) {
                _DCCServer.close(forced);
                _DCCServer = null;
            }
            if (_DCCClientManager != null) {
                _DCCClientManager.close(forced);
                _DCCClientManager = null;
            }
        }
        return super.close(forced);
    }

    //
    //  Start of the DCCHelper interface
    //

  private class DCC implements DCCHelper {

    private final byte[] _localAddr;

    /**
     *  @param local Our IP address, from the IRC client's perspective
     */
    public DCC(byte[] local) {
        if (local.length == 4)
            _localAddr = local;
        else
            _localAddr = new byte[] {127, 0, 0, 1};
    }

    public boolean isEnabled() {
        return _dccEnabled;
    }

    public String getB32Hostname() {
        return sockMgr.getSession().getMyDestination().toBase32();
    }

    public byte[] getLocalAddress() {
        return _localAddr;
    }

    public int newOutgoing(byte[] ip, int port, String type) {
        I2PTunnelDCCServer server;
        synchronized(this) {
            if (_DCCServer == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Starting DCC Server");
                _DCCServer = new I2PTunnelDCCServer(sockMgr, l, I2PTunnelIRCClient.this, getTunnel());
                // TODO add some prudent tunnel options (or is it too late?)
                _DCCServer.startRunning();
            }
            server = _DCCServer;
        }
        int rv = server.newOutgoing(ip, port, type);
        if (_log.shouldLog(Log.INFO))
            _log.info("New outgoing " + type + ' ' + port + " returns " + rv);
        return rv;
    }

    public int newIncoming(String b32, int port, String type) {
        DCCClientManager tracker;
        synchronized(this) {
            if (_DCCClientManager == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Starting DCC Client");
                _DCCClientManager = new DCCClientManager(sockMgr, l, I2PTunnelIRCClient.this, getTunnel());
            }
            tracker = _DCCClientManager;
        }
        // The tracker starts our client
        int rv = tracker.newIncoming(b32, port, type);
        if (_log.shouldLog(Log.INFO))
            _log.info("New incoming " + type + ' ' + b32 + ' ' + port + " returns " + rv);
        return rv;
    }

    public int resumeOutgoing(int port) {
        DCCClientManager tracker = _DCCClientManager;
        if (tracker != null)
            return tracker.resumeOutgoing(port);
        return -1;
    }

    public int resumeIncoming(int port) {
        I2PTunnelDCCServer server = _DCCServer;
        if (server != null)
            return server.resumeIncoming(port);
        return -1;
    }

    public int acceptOutgoing(int port) {
        I2PTunnelDCCServer server = _DCCServer;
        if (server != null)
            return server.acceptOutgoing(port);
        return -1;
    }

    public int acceptIncoming(int port) {
        DCCClientManager tracker = _DCCClientManager;
        if (tracker != null)
            return tracker.acceptIncoming(port);
        return -1;
    }
  }
}
