package net.i2p.router.transport.tcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public class TCPTransport extends TransportImpl {
    private Log _log;
    public final static String STYLE = "TCP";
    private List _listeners;
    private Map _connections; // routerIdentity --> List of TCPConnection
    private String _listenHost;
    private int _listenPort;
    private RouterAddress _address;
    private boolean _listenAddressIsValid;
    private Map _msgs; // H(ident) --> PendingMessages for unestablished connections
    private boolean _running;
    
    private int _numConnectionEstablishers;
    private final static String PROP_ESTABLISHERS = "i2np.tcp.concurrentEstablishers";
    private final static int DEFAULT_ESTABLISHERS = 3;
    
    public static String PROP_LISTEN_IS_VALID = "i2np.tcp.listenAddressIsValid";
    
    /**
     * pre 1.4 java doesn't have a way to timeout the creation of sockets (which
     * can take up to 3 minutes), so we do it on a seperate thread and wait for
     * either that thread to complete, or for this timeout to be reached.
     */
    final static long SOCKET_CREATE_TIMEOUT = 10*1000;
    
    public TCPTransport(RouterContext context, RouterAddress address) {
        super(context);
        _log = context.logManager().getLog(TCPTransport.class);
        if (_context == null) throw new RuntimeException("Context is null");
        if (_context.statManager() == null) throw new RuntimeException("Stat manager is null");
        _context.statManager().createFrequencyStat("tcp.attemptFailureFrequency", "How often do we attempt to contact someone, and fail?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createFrequencyStat("tcp.attemptSuccessFrequency", "How often do we attempt to contact someone, and succeed?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createFrequencyStat("tcp.acceptFailureFrequency", "How often do we reject someone who contacts us?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createFrequencyStat("tcp.acceptSuccessFrequency", "How often do we accept someone who contacts us?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tcp.connectionLifetime", "How long do connections last (measured when they close)?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });

        _listeners = new ArrayList();
        _connections = new HashMap();
        _msgs = new HashMap();
        _address = address;
        if (address != null) {
            _listenHost = address.getOptions().getProperty(TCPAddress.PROP_HOST);
            String portStr = address.getOptions().getProperty(TCPAddress.PROP_PORT);
            try {
                _listenPort = Integer.parseInt(portStr);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid port: " + portStr + " Address: \n" + address, nfe);
            }
        }
        _listenAddressIsValid = false;
        try {
            String setting = _context.router().getConfigSetting(PROP_LISTEN_IS_VALID);
            _listenAddressIsValid = Boolean.TRUE.toString().equalsIgnoreCase(setting);
        } catch (Throwable t) {
            _listenAddressIsValid = false;
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to determine whether TCP listening address is valid, so we're assuming it isn't.  Set " + PROP_LISTEN_IS_VALID + " otherwise");
        }
        _running = false;
    }
    
    boolean getListenAddressIsValid() { return _listenAddressIsValid; }
    SigningPrivateKey getMySigningKey() { return _context.keyManager().getSigningPrivateKey(); }
    int getListenPort() { return _listenPort; }
    
    /** fetch all of our TCP listening addresses */
    TCPAddress[] getMyAddresses() {
        if (_address != null) {
            TCPAddress rv[] = new TCPAddress[1];
            rv[0] = new TCPAddress(_listenHost, _listenPort);
            return rv;
        } else {
            return new TCPAddress[0];
        }
    }
    
    /**
     * This message is called whenever a new message is added to the send pool,
     * and it should not block
     */
    protected void outboundMessageReady() {
        //_context.jobQueue().addJob(new NextJob());
        NextJob j = new NextJob();
        j.runJob();
    }
    
    private class NextJob extends JobImpl {
        public NextJob() {
            super(TCPTransport.this._context);
        }
        public void runJob() {
            OutNetMessage msg = getNextMessage();
            if (msg != null) {
                handleOutbound(msg); // this just adds to either the establish thread's queue or the conn's queue
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("OutboundMessageReady called, but none were available");
            }
        }
        public String getName() { return "TCP Message Ready to send"; }
    }
    
    /**
     * Return a random connection to the peer from the set of known connections
     *
     */
    private TCPConnection getConnection(RouterIdentity peer) {
        synchronized (_connections) {
            if (!_connections.containsKey(peer))
                return null;
            List cons = (List)_connections.get(peer);
            if (cons.size() <= 0)
                return null;
            TCPConnection first = (TCPConnection)cons.get(0);
            return first;
        }
    }
    
    protected void handleOutbound(OutNetMessage msg) {
        msg.timestamp("TCPTransport.handleOutbound before handleConnection");
        TCPConnection con = getConnection(msg.getTarget().getIdentity());
        if (con == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handling outbound message to an unestablished peer");
            msg.timestamp("TCPTransport.handleOutbound to addPending");
            addPending(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Toss the message onto an established peer's connection");
            msg.timestamp("TCPTransport.handleOutbound to con.addMessage");
            con.addMessage(msg);
        }
    }
    
    protected boolean establishConnection(RouterInfo target) {
        long startEstablish = 0;
        long socketCreated = 0;
        long conCreated = 0;
        long conEstablished = 0;
        try {
            for (Iterator iter = target.getAddresses().iterator(); iter.hasNext(); ) {
                RouterAddress addr = (RouterAddress)iter.next();
                startEstablish = _context.clock().now();
                if (getStyle().equals(addr.getTransportStyle())) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Establishing a connection with address " + addr);
                    Socket s = createSocket(addr);
                    socketCreated = _context.clock().now();
                    if (s == null) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Unable to establish a socket in time to " + addr);
                        _context.profileManager().commErrorOccurred(target.getIdentity().getHash());
                        _context.shitlist().shitlistRouter(target.getIdentity().getHash(), "Unable to contact host");
                        return false;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Socket created");
                    
                    TCPConnection con = new RestrictiveTCPConnection(_context, s, true);
                    conCreated = _context.clock().now();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("TCPConnection created");
                    boolean established = handleConnection(con, target);
                    conEstablished = _context.clock().now();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("connection handled");
                    return established;
                }
            }

            _context.shitlist().shitlistRouter(target.getIdentity().getHash(), "No addresses we can handle");
            return false;
        } catch (Throwable t) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unexpected error establishing the connection", t);
            _context.shitlist().shitlistRouter(target.getIdentity().getHash(), "Internal error connecting");
            return false;
        } finally {
            long diff = conEstablished - startEstablish;
            if ( ( (diff > 6000) || (conEstablished == 0) ) && (_log.shouldLog(Log.WARN)) )  {
                _log.warn("establishConnection took too long: socketCreate: " +
                (socketCreated-startEstablish) + "ms conCreated: " +
                (conCreated-socketCreated) + "ms conEstablished: " +
                (conEstablished - conCreated) + "ms overall: " + diff);
            }
        }        
    }
    
    protected Socket createSocket(RouterAddress addr) {
        String host = addr.getOptions().getProperty(TCPAddress.PROP_HOST);
        String portStr = addr.getOptions().getProperty(TCPAddress.PROP_PORT);
        int port = -1;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid port number in router address: " + portStr, nfe);
            return null;
        }
        
        long start = _context.clock().now();
        SocketCreator creator = new SocketCreator(host, port);
        I2PThread sockCreator = new I2PThread(creator);
        sockCreator.setDaemon(true);
        sockCreator.setName("SocketCreator_:" + _listenPort);
        //sockCreator.setPriority(I2PThread.MIN_PRIORITY);
        sockCreator.start();
        
        try {
            synchronized (creator) {
                creator.wait(SOCKET_CREATE_TIMEOUT);
            }
        } catch (InterruptedException ie) {}
        
        long finish = _context.clock().now();
        long diff = finish - start;
        if (diff > 6000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Creating a new socket took too long?  wtf?! " + diff + "ms for " + host + ':' + port);
        }
        return creator.getSocket();
    }
    
    private boolean isConnected(RouterInfo info) {
        return (null != getConnection(info.getIdentity()));
    }
    
    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        TCPConnection con = getConnection(toAddress.getIdentity());
        int latencyStartup = 0;
        if (con == null)
            latencyStartup = 2000;
        else
            latencyStartup = 0;
        
        int sendTime = (int)((dataSize)/(16*1024)); // 16K/sec
        int bytes = (int)dataSize+8;
        
        if (con != null)
            sendTime += 50000 * con.getPendingMessageCount(); // try to avoid backed up (throttled) connections
        
        TransportBid bid = new TransportBid();
        bid.setBandwidthBytes(bytes);
        bid.setExpiration(new Date(_context.clock().now()+1000*60)); // 1 minute
        bid.setLatencyMs(latencyStartup + sendTime);
        bid.setMessageSize((int)dataSize);
        bid.setRouter(toAddress);
        bid.setTransport(this);
        
        RouterAddress addr = getTargetAddress(toAddress);
        if (addr == null) {
            if (con == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("No address or connection to " + toAddress.getIdentity().getHash().toBase64());
                // don't bid if we can't send them a message
                return null;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No address, but we're connected to " + toAddress.getIdentity().getHash().toBase64());
            }
        }
        
        return bid;
    }
    
    public void rotateAddresses() {
        // noop
    }
    public void addAddressInfo(Properties infoForNewAddress) {
        // noop
    }
    
    
    public RouterAddress startListening() {
        RouterAddress address = new RouterAddress();
        
        address.setTransportStyle(getStyle());
        address.setCost(10);
        address.setExpiration(null);
        Properties options = new Properties();
        if (_address != null) {
            options.setProperty(TCPAddress.PROP_HOST, _listenHost);
            options.setProperty(TCPAddress.PROP_PORT, _listenPort+"");
        }
        address.setOptions(options);
        
        if (_address != null) {
            try {
                TCPAddress addr = new TCPAddress();
                addr.setHost(_listenHost);
                addr.setPort(_listenPort);
                TCPListener listener = new TCPListener(_context, this);
                listener.setAddress(addr);
                _listeners.add(listener);
                listener.startListening();
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error parsing port number", nfe);
            }
            
            addCurrentAddress(address);
        }
        
        String str = _context.router().getConfigSetting(PROP_ESTABLISHERS);
        if (str != null) {
            try {
                _numConnectionEstablishers = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid number of connection establishers [" + str + "]");
                _numConnectionEstablishers = DEFAULT_ESTABLISHERS;
            }
        } else {
            _numConnectionEstablishers = DEFAULT_ESTABLISHERS;
        }
        
        _running = true;
        for (int i = 0; i < _numConnectionEstablishers; i++) {
            Thread t = new I2PThread(new ConnEstablisher(i));
            t.setDaemon(true);
            t.start();
        }
        
        return address;
    }
    
    public void stopListening() {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Stop listening called!  No more TCP", new Exception("Die tcp, die"));
        _running = false;
        
        for (int i = 0; i < _listeners.size(); i++) {
            TCPListener lsnr = (TCPListener)_listeners.get(i);
            lsnr.stopListening();
        }
        Set allCons = new HashSet();
        synchronized (_connections) {
            for (Iterator iter = _connections.values().iterator(); iter.hasNext(); ) {
                List cons = (List)iter.next();
                for (Iterator citer = cons.iterator(); citer.hasNext(); ) {
                    TCPConnection con = (TCPConnection)citer.next();
                    allCons.add(con);
                }
            }
        }
        for (Iterator iter = allCons.iterator(); iter.hasNext(); ) {
            TCPConnection con = (TCPConnection)iter.next();
            con.closeConnection();
        }
    }
    
    public RouterIdentity getMyIdentity() { return _context.router().getRouterInfo().getIdentity(); }
    
    void connectionClosed(TCPConnection con) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Connection closed with " + con.getRemoteRouterIdentity());
        StringBuffer buf = new StringBuffer(256);
        buf.append("Still connected to: ");
        synchronized (_connections) {
            List cons = (List)_connections.get(con.getRemoteRouterIdentity());
            if ( (cons != null) && (cons.size() > 0) ) {
                cons.remove(con);
                long lifetime = con.getLifetime();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Connection closed (with remaining) after lifetime " + lifetime);
                _context.statManager().addRateData("tcp.connectionLifetime", lifetime, 0);
            }
            Set toRemove = new HashSet();
            for (Iterator iter = _connections.keySet().iterator(); iter.hasNext();) {
                RouterIdentity ident = (RouterIdentity)iter.next();
                List all = (List)_connections.get(ident);
                if (all.size() > 0)
                    buf.append(ident.getHash().toBase64()).append(" ");
                else
                    toRemove.add(ident);
            }
            for (Iterator iter = toRemove.iterator(); iter.hasNext(); ) {
                _connections.remove(iter.next());
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(buf.toString());
        //if (con.getRemoteRouterIdentity() != null)
    }
    
    boolean handleConnection(TCPConnection con, RouterInfo target) {
        con.setTransport(this);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Before establishing connection");
        long start = _context.clock().now();
        RouterIdentity ident = con.establishConnection();
        long afterEstablish = _context.clock().now();
        long startRunning = 0;
        
        if (ident == null) {
            _context.statManager().updateFrequency("tcp.acceptFailureFrequency");
            con.closeConnection();
            return false;
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Connection established with " + ident + " after " + (afterEstablish-start) + "ms");
        if (target != null) {
            if (!target.getIdentity().equals(ident)) {
                _context.statManager().updateFrequency("tcp.acceptFailureFrequency");
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Target changed identities!!!  was " + target.getIdentity().getHash().toBase64() + ", now is " + ident.getHash().toBase64() + "!  DROPPING CONNECTION");
                con.closeConnection();
                // remove the old ref, since they likely just created a new identity
                _context.netDb().fail(target.getIdentity().getHash());
                _context.shitlist().shitlistRouter(target.getIdentity().getHash(), "Peer changed identities");
                return false;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Target is the same as who we connected with");
            }
        }
        if (ident != null) {
            Set toClose = new HashSet(4);
            List toAdd = new LinkedList();
            synchronized (_connections) {
                if (!_connections.containsKey(ident))
                    _connections.put(ident, new ArrayList(2));
                List cons = (List)_connections.get(ident);
                if (cons.size() > 0) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Attempted to open additional connections with " + ident.getHash() + ": closing older connections", new Exception("multiple cons"));
                    while (cons.size() > 0) {
                        TCPConnection oldCon = (TCPConnection)cons.remove(0);
                        toAdd.addAll(oldCon.getPendingMessages());
                        toClose.add(oldCon);
                    }
                }
                cons.add(con);
                
                Set toRemove = new HashSet();
                for (Iterator iter = _connections.keySet().iterator(); iter.hasNext();) {
                    RouterIdentity cur = (RouterIdentity)iter.next();
                    List all = (List)_connections.get(cur);
                    if (all.size() <= 0)
                        toRemove.add(ident);
                }
                for (Iterator iter = toRemove.iterator(); iter.hasNext(); ) {
                    _connections.remove(iter.next());
                }
            }
            
            if (toAdd.size() > 0) {
                for (Iterator iter = toAdd.iterator(); iter.hasNext(); ) {
                    OutNetMessage msg = (OutNetMessage)iter.next();
                    con.addMessage(msg);
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Transferring " + toAdd.size() + " messages from old cons to the newly established con");
            }
            
            _context.shitlist().unshitlistRouter(ident.getHash());
            con.runConnection();
            startRunning = _context.clock().now();
            
            if (toClose.size() > 0) {
                for (Iterator iter = toClose.iterator(); iter.hasNext(); ) {
                    TCPConnection oldCon = (TCPConnection)iter.next();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Closing old duplicate connection " + oldCon.toString(), new Exception("Closing old con"));
                    oldCon.closeConnection();
                    _context.statManager().addRateData("tcp.connectionLifetime", oldCon.getLifetime(), 0);
                }
            }
            long done = _context.clock().now();
            
            long diff = done - start;
            if ( (diff > 3*1000) && (_log.shouldLog(Log.WARN)) ) {
                _log.warn("handleConnection took too long: " + diff + "ms with " +
                (afterEstablish-start) + "ms to establish " +
                (startRunning-afterEstablish) + "ms to start running " +
                (done-startRunning) + "ms to cleanup");
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("runConnection called on the con");
        }
        
        _context.statManager().updateFrequency("tcp.acceptSuccessFrequency");
        return true;
    }
    
    public String getStyle() { return STYLE; }
    
    public String renderStatusHTML() {
        StringBuffer buf = new StringBuffer();
        Map cons = new HashMap();
        synchronized (_connections) {
            cons.putAll(_connections);
        }
        int established = 0;
        buf.append("<b>TCP Transport</b> <i>(").append(cons.size()).append(" connections)</i><br />\n");
        buf.append("<ul>");
        for (Iterator iter = cons.keySet().iterator(); iter.hasNext(); ) {
            buf.append("<li>");
            RouterIdentity ident = (RouterIdentity)iter.next();
            List curCons = (List)cons.get(ident);
            buf.append("Connection to ").append(ident.getHash().toBase64()).append(": ");
            String lifetime = null;
            for (int i = 0; i < curCons.size(); i++) {
                TCPConnection con = (TCPConnection)curCons.get(i);
                if (con.getLifetime() > 30*1000) {
                    established++;
                    lifetime = DataHelper.formatDuration(con.getLifetime());
                }
            }
            if (lifetime != null)
                buf.append(lifetime);
            else
                buf.append("[pending]");
            
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        
        if (established == 0) {
            buf.append("<b><font color=\"red\">No TCP connections</font></b><ul>");
            buf.append("<li>Is your publicly reachable IP address / hostname <b>").append(_listenHost).append("</b>?</li>\n");
            buf.append("<li>Is your firewall / NAT open to receive connections on port <b>").append(_listenPort).append("</b>?</li>\n");
            buf.append("<li>Do you have any reachable peer references (see down below for \"Routers\", ");
            buf.append("    or check your netDb directory - you want at least two routers, since one of them is your own)</li>\n");
            buf.append("</ul>\n");
        }
        return buf.toString();
    }
    
    /**
     * only establish one connection at a time, and if multiple requests are pooled
     * for the same one, once one is established send all the messages through
     *
     */
    private class ConnEstablisher implements Runnable {
        private int _id;
        
        public ConnEstablisher(int id) {
            _id = id;
        }
        
        public int getId() { return _id; }
        
        public void run() {
            Thread.currentThread().setName("Conn Establisher" + _id + ':' + _listenPort);
            
            while (_running) {
                try {
                    PendingMessages pending = nextPeer(this);
                    
                    long start = _context.clock().now();
                    
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Beginning establishment with " + pending.getPeer().toBase64() + " [not error]");
                    
                    TCPConnection con = getConnection(pending.getPeerInfo().getIdentity());
                    long conFetched = _context.clock().now();
                    long sentPending = 0;
                    long establishedCon = 0;
                    long refetchedCon = 0;
                    long sentRefetched = 0;
                    long failedPending = 0;
                    
                    if (con != null) {
                        sendPending(con, pending);
                        sentPending = _context.clock().now();
                    } else {
                        boolean established = establishConnection(pending.getPeerInfo());
                        establishedCon = _context.clock().now();
                        if (established) {
                            _context.statManager().updateFrequency("tcp.attemptSuccessFrequency");
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Connection established");
                            con = getConnection(pending.getPeerInfo().getIdentity());
                            refetchedCon = _context.clock().now();
                            if (con == null) {
                                if (_log.shouldLog(Log.ERROR))
                                    _log.error("Connection established but we can't find the connection? wtf!  peer = " + pending.getPeer());
                            } else {
                                _context.shitlist().unshitlistRouter(pending.getPeer());
                                sendPending(con, pending);
                                sentRefetched = _context.clock().now();
                            }
                        } else {
                            _context.statManager().updateFrequency("tcp.attemptFailureFrequency");
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Unable to establish a connection to " + pending.getPeer());
                            failPending(pending);
                            
                            // shitlisted by establishConnection with a more detailed reason
                            //_context.shitlist().shitlistRouter(pending.getPeer(), "Unable to contact host");
                            //ProfileManager.getInstance().commErrorOccurred(pending.getPeer());
                            failedPending = _context.clock().now();
                        }
                    }
                    
                    long end = _context.clock().now();
                    long diff = end - start;
                    
                    StringBuffer buf = new StringBuffer(128);
                    buf.append("Time to establish with ").append(pending.getPeer().toBase64()).append(": ").append(diff).append("ms");
                    buf.append(" fetched: ").append(conFetched-start).append(" ms");
                    if (sentPending != 0)
                        buf.append(" sendPending: ").append(sentPending - conFetched).append("ms");
                    if (establishedCon != 0) {
                        buf.append(" established: ").append(establishedCon - conFetched).append("ms");
                        if (refetchedCon != 0) {
                            buf.append(" refetched: ").append(refetchedCon - establishedCon).append("ms");
                            if (sentRefetched != 0) {
                                buf.append(" sentRefetched: ").append(sentRefetched - refetchedCon).append("ms");
                            }
                        } else {
                            buf.append(" failedPending: ").append(failedPending - establishedCon).append("ms");
                        }
                    }
                    if (diff > 6000) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(buf.toString());
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info(buf.toString());
                    }
                } catch (Throwable t) {
                    if (_log.shouldLog(Log.CRIT))
                        _log.log(Log.CRIT, "Error in connection establisher thread - NO MORE CONNECTIONS", t);
                }
            }
        }
    }
    
    /**
     * Add a new message to the outbound pool to be established asap (may be sent
     * along existing connections if they appear later)
     *
     */
    public void addPending(OutNetMessage msg) {
        synchronized (_msgs) {
            Hash target = msg.getTarget().getIdentity().getHash();
            PendingMessages msgs = (PendingMessages)_msgs.get(target);
            if (msgs == null) {
                msgs = new PendingMessages(msg.getTarget());
                msgs.addPending(msg);
                _msgs.put(target, msgs);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Adding a pending to new " + target.toBase64());
            } else {
                msgs.addPending(msg);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Adding a pending to existing " + target.toBase64());
            }
            int level = Log.INFO;
            if (msgs.getMessageCount() > 1)
                level = Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "Add message to " + target.toBase64() + ", making a total of " + msgs.getMessageCount() + " for them, with another " + (_msgs.size() -1) + " peers pending establishment");
            _msgs.notifyAll();
        }
        msg.timestamp("TCPTransport.addPending finished and notified");
    }
    
    /**
     * blocking call to claim the next available targeted peer.   does a wait on
     * the _msgs pool which should be notified from addPending.
     *
     */
    private PendingMessages nextPeer(ConnEstablisher establisher) {
        PendingMessages rv = null;
        while (true) {
            synchronized (_msgs) {
                if (_msgs.size() <= 0) {
                    try { _msgs.wait(); } catch (InterruptedException ie) {}
                } 
                if (_msgs.size() > 0) {
                    for (Iterator iter = _msgs.keySet().iterator(); iter.hasNext(); ) {
                        Object key = iter.next();
                        rv = (PendingMessages)_msgs.get(key);
                        if (!rv.setEstablisher(establisher)) {
                            // unable to claim this peer
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Peer is still in process: " + rv.getPeer() + " on establisher " + rv.getEstablisher().getId());
                            rv = null;
                        } else {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Returning next peer " + rv.getPeer().toBase64());
                            return rv;
                        }
                    }
                }
            }
        }
        
    }
    
    /**
     * Send all the messages targetting the given location
     * over the established connection
     *
     */
    private void sendPending(TCPConnection con, PendingMessages pending) {
        if (con == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Send pending to null con?", new Exception("Hmm"));
            return;
        }
        if (pending == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null pending, 'eh?", new Exception("Hmm.."));
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Connection established, now queueing up " + pending.getMessageCount() + " messages to be sent");
        synchronized (_msgs) {
            _msgs.remove(pending.getPeer());
            
            OutNetMessage msg = null;
            while ( (msg = pending.getNextMessage()) != null) {
                msg.timestamp("TCPTransport.sendPending to con.addMessage");
                con.addMessage(msg);
            }
        }
    }
    
    /**
     * Fail out all messages pending to the specified peer
     */
    private void failPending(PendingMessages pending) {
        if (pending != null) {
            synchronized (_msgs) {
                _msgs.remove(pending.getPeer());
            }
            
            OutNetMessage msg = null;
            while ( (msg = pending.getNextMessage()) != null) {
                afterSend(msg, false);
            }
        }
    }
    
    /**
     * Coordinate messages for a particular peer that hasn't been established yet
     *
     */
    private static class PendingMessages {
        private List _messages;
        private Hash _peer;
        private RouterInfo _peerInfo;
        private ConnEstablisher _establisher;
        
        public PendingMessages(RouterInfo peer) {
            _messages = new LinkedList();
            _peerInfo = peer;
            _peer = peer.getIdentity().getHash();
            _establisher = null;
        }
        
        /**
         * Claim a peer for a specific establisher
         *
         * @return true if the claim was successful, false if someone beat us to it
         */
        public boolean setEstablisher(ConnEstablisher establisher) {
            synchronized (PendingMessages.this) {
                if (_establisher == null) {
                    _establisher = establisher;
                    return true;
                } else {
                    return false;
                }
            }
        }
        public ConnEstablisher getEstablisher() {
            return _establisher;
        }
        
        /**
         * Add a new message to this to-be-established connection
         */
        public void addPending(OutNetMessage msg) {
            synchronized (_messages) {
                _messages.add(msg);
            }
        }
        
        /**
         * Get the next message queued up for delivery on this connection being established
         *
         */
        public OutNetMessage getNextMessage() {
            synchronized (_messages) {
                if (_messages.size() <= 0)
                    return null;
                else
                    return (OutNetMessage)_messages.remove(0);
            }
        }
        
        /**
         * Get the number of messages queued up for this to be established connection
         *
         */
        public int getMessageCount() {
            synchronized (_messages) {
                return _messages.size();
            }
        }
        
        /** who are we going to establish with? */
        public Hash getPeer() { return _peer; }
        /** who are we going to establish with? */
        public RouterInfo getPeerInfo() { return _peerInfo; }
    }
}
