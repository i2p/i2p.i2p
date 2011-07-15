package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.irc.DCCClientManager;
import net.i2p.i2ptunnel.irc.DCCHelper;
import net.i2p.i2ptunnel.irc.I2PTunnelDCCServer;
import net.i2p.i2ptunnel.irc.IrcInboundFilter;
import net.i2p.i2ptunnel.irc.IrcOutboundFilter;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Todo: Can we extend I2PTunnelClient instead and remove some duplicated code?
 */
public class I2PTunnelIRCClient extends I2PTunnelClientBase implements DCCHelper {

    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;
    
    /** list of Destination objects that we point at */
    protected List<Destination> dests;
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
              "IRC Client on " + tunnel.listenHost + ':' + localPort + " #" + (++__clientId), tunnel, pkf);
        
        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        dests = new ArrayList(2);
        while (tok.hasMoreTokens()) {
            String destination = tok.nextToken();
            Destination destN = _context.namingService().lookup(destination);
            if (destN == null)
                l.log("Could not resolve " + destination);
            else
                dests.add(destN);
        }

        if (dests.isEmpty()) {
            l.log("No target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way
            // Note that b32 dests will often not be resolvable at instantiation time;
            // a delayed resolution system would be even better.

            // Don't close() here, because it does a removeSession() and then
            // TunnelController can't acquire() it to release() it.
            //close(true);
            // Unfortunately, super() built the whole tunnel before we get here.
            throw new IllegalArgumentException("No valid target destinations found");
            //return;
        }
        
        setName("IRC Client on " + tunnel.listenHost + ':' + localPort);

        _dccEnabled = Boolean.valueOf(tunnel.getClientOptions().getProperty(PROP_DCC)).booleanValue();
        // TODO add some prudent tunnel options (or is it too late?)

        startRunning();

        notifyEvent("openIRCClientResult", "ok");
    }
    
    protected void clientConnectionRun(Socket s) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("got a connection.");
        Destination clientDest = pickDestination();
        I2PSocket i2ps = null;
        try {
            i2ps = createI2PSocket(clientDest);
            i2ps.setReadTimeout(readTimeout);
            StringBuffer expectedPong = new StringBuffer();
            Thread in = new I2PAppThread(new IrcInboundFilter(s,i2ps, expectedPong, _log, this), "IRC Client " + __clientId + " in", true);
            in.start();
            Thread out = new I2PAppThread(new IrcOutboundFilter(s,i2ps, expectedPong, _log, this), "IRC Client " + __clientId + " out", true);
            out.start();
        } catch (Exception ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting", ex);
            //l.log("Error connecting: " + ex.getMessage());
            closeSocket(s);
            if (i2ps != null) {
                synchronized (sockLock) {
                    mySockets.remove(sockLock);
                }
            }
        }

    }
    
    private final Destination pickDestination() {
        int size = dests.size();
        if (size <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No client targets?!");
            return null;
        }
        if (size == 1) // skip the rand in the most common case
            return dests.get(0);
        int index = _context.random().nextInt(size);
        return dests.get(index);
    }

    @Override
    public boolean close(boolean forced) {
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

    public boolean isEnabled() {
        return _dccEnabled;
    }

    public String getB32Hostname() {
        return Base32.encode(sockMgr.getSession().getMyDestination().calculateHash().getData()) + ".b32.i2p";
    }


    public int newOutgoing(byte[] ip, int port, String type) {
        I2PTunnelDCCServer server;
        synchronized(this) {
            if (_DCCServer == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Starting DCC Server");
                _DCCServer = new I2PTunnelDCCServer(sockMgr, l, this, getTunnel());
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
                _DCCClientManager = new DCCClientManager(sockMgr, l, this, getTunnel());
            }
            tracker = _DCCClientManager;
        }
        // The tracker starts our client
        int rv = tracker.newIncoming(b32, port, type);
        if (_log.shouldLog(Log.INFO))
            _log.info("New incoming " + type + ' ' + b32 + ' ' + port + " returns " + rv);
        return rv;
    }
}
