package net.i2p.router.transport.tcp;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.text.SimpleDateFormat;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportBid;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * TCP Transport implementation, coordinating the connections 
 * between peers and the transmission of messages across those 
 * connections.
 *
 */
public class TCPTransport extends TransportImpl {
    private final Log _log;
    /** Our local TCP address, if known */
    private TCPAddress _myAddress;
    /** How we receive connections */
    private TCPListener _listener;
    /** Coordinate the agreed connection tags */
    private ConnectionTagManager _tagManager;
    
    /** H(RouterIdentity) to TCPConnection for fully established connections */
    private Map _connectionsByIdent;
    /** TCPAddress::toString() to TCPConnection for fully established connections */
    private Map _connectionsByAddress;
    
    /** H(RouterIdentity) for not yet established connections */
    private Set _pendingConnectionsByIdent;
    /** TCPAddress::toString() for not yet established connections */
    private Set _pendingConnectionsByAddress;
    
    /** 
     * H(RouterIdentity) to List of OutNetMessage for messages targetting 
     * not yet established connections
     */
    private Map _pendingMessages;
    /** 
     * Object to lock on when touching the _connection maps or 
     * the pendingMessages map.  In addition, this lock is notified whenever
     * a brand new peer is added to the pendingMessages map
     */
    private Object _connectionLock;
    /** 
     * List of the most recent connection establishment error messages (where the 
     * message includes the time) 
     */
    private List _lastConnectionErrors;
    /** All of the operating TCPConnectionEstablisher objects */
    private List _connectionEstablishers;
    
    /** What is this transport's identifier? */
    public static final String STYLE = "TCP";
    /** Should the TCP listener bind to all interfaces? */
    public static final String BIND_ALL_INTERFACES = "i2np.tcp.bindAllInterfaces";
    /** What host/ip should we be addressed as? */
    public static final String LISTEN_ADDRESS = "i2np.tcp.hostname";
    /** What port number should we listen to? */
    public static final String LISTEN_PORT = "i2np.tcp.port";
    /** Should we allow the transport to listen on a non routable address? */
    public static final String LISTEN_ALLOW_LOCAL = "i2np.tcp.allowLocal";
    /** Keep track of the last 10 error messages wrt establishing a connection */
    public static final int MAX_ERR_MESSAGES = 10;
    public static final String PROP_ESTABLISHERS = "i2np.tcp.concurrentEstablishers";
    public static final int DEFAULT_ESTABLISHERS = 3;
    
    /** Ordered list of supported I2NP protocols */
    public static final int[] SUPPORTED_PROTOCOLS = new int[] { 1 };
    /** blah, people shouldnt use defaults... */
    public static final int DEFAULT_LISTEN_PORT = 8887;
    
    /** Creates a new instance of TCPTransport */
    public TCPTransport(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(TCPTransport.class);
        _listener = new TCPListener(context, this);
        _myAddress = null;
        _tagManager = new PersistentConnectionTagManager(context);
        _connectionsByIdent = new HashMap(16);
        _connectionsByAddress = new HashMap(16);
        _pendingConnectionsByIdent = new HashSet(16);
        _pendingConnectionsByAddress = new HashSet(16);
        _connectionLock = new Object();
        _pendingMessages = new HashMap(16);
        _lastConnectionErrors = new ArrayList();

        String str = _context.getProperty(PROP_ESTABLISHERS);
        int establishers = 0;
        if (str != null) {
            try {
                establishers = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid number of connection establishers [" + str + "]");
                establishers = DEFAULT_ESTABLISHERS;
            }
        } else {
            establishers = DEFAULT_ESTABLISHERS;
        }

        _connectionEstablishers = new ArrayList(establishers);
        for (int i = 0; i < establishers; i++) {
            TCPConnectionEstablisher est = new TCPConnectionEstablisher(_context, this);
            _connectionEstablishers.add(est);
            String name = _context.routerHash().toBase64().substring(0,6) + " Est" + i;
            I2PThread t = new I2PThread(est, name);
            t.setDaemon(true);
            t.start();
        }
    }
    
    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        RouterAddress addr = toAddress.getTargetAddress(STYLE);
        if (addr == null) 
            return null;
        
        TCPAddress tcpAddr = new TCPAddress(addr);
        if ( (_myAddress != null) && (tcpAddr.equals(_myAddress)) )
            return null; // dont talk to yourself
        
        TransportBid bid = new TransportBid();
        bid.setBandwidthBytes((int)dataSize);
        bid.setExpiration(_context.clock().now() + 30*1000);
        bid.setMessageSize((int)dataSize);
        bid.setRouter(toAddress);
        bid.setTransport(this);
        int latency = 200;
        if (!getIsConnected(toAddress.getIdentity()))
            latency += 5000;
        bid.setLatencyMs(latency);
        
        return bid;
    }
    
    private boolean getIsConnected(RouterIdentity ident) {
        Hash peer = ident.calculateHash();
        synchronized (_connectionLock) {
            return _connectionsByIdent.containsKey(peer);
        }
    }
    
    /**
     * Called whenever a new message is ready to be sent.  This should
     * not block.
     *
     */
    protected void outboundMessageReady() {
        OutNetMessage msg = getNextMessage();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Outbound message ready: " + msg);
        
        if (msg != null) {
            if (msg.getTarget() == null) 
                throw new IllegalStateException("Null target for a ready message?");
            
            msg.timestamp("TCPTransport.outboundMessageReady");
            
            TCPConnection con = null;
            boolean newPeer = false;
            synchronized (_connectionLock) {
                Hash peer = msg.getTarget().getIdentity().calculateHash();
                con = (TCPConnection)_connectionsByIdent.get(peer);
                if (con == null) {
                    if (_log.shouldLog(Log.DEBUG)) {
                        StringBuffer buf = new StringBuffer(128);
                        buf.append("No connections to ");
                        buf.append(peer.toBase64().substring(0,6));
                        buf.append(", but we are connected to ");
                        for (Iterator iter = _connectionsByIdent.keySet().iterator(); iter.hasNext(); ) {
                            Hash cur = (Hash)iter.next();
                            buf.append(cur.toBase64().substring(0,6)).append(", ");
                        }
                        _log.debug(buf.toString());
                    }
                    List msgs = (List)_pendingMessages.get(peer);
                    if (msgs == null) {
                        msgs = new ArrayList(4);
                        _pendingMessages.put(peer, msgs);
                        newPeer = true;
                    }
                    msgs.add(msg);
                }
                
                if (newPeer)
                    _connectionLock.notifyAll();
            }
            
            if (con != null)
                con.addMessage(msg);
        }
    }
    
    
    /**
     * The connection specified has been fully built
     */
    void connectionEstablished(TCPConnection con) {
        TCPAddress remAddr = con.getRemoteAddress();
        RouterIdentity ident = con.getRemoteRouterIdentity();
        if ( (remAddr == null) || (ident == null) ) {
            con.closeConnection();
            return;
        }
        
        List waitingMsgs = null;
        List changedMsgs = null;
        boolean alreadyConnected = false;
        boolean changedIdents = false;
        synchronized (_connectionLock) {
            if (_connectionsByAddress.containsKey(remAddr.toString())) {
                alreadyConnected = true;
            } else {
                _connectionsByAddress.put(remAddr.toString(), con);
            }
            
            if (_connectionsByIdent.containsKey(ident.calculateHash())) {
                alreadyConnected = true;
            } else {
                _connectionsByIdent.put(ident.calculateHash(), con);
            }
            
            // just drop the _pending connections - the establisher should fail
            // them accordingly.
            _pendingConnectionsByAddress.remove(remAddr.toString());
            _pendingConnectionsByIdent.remove(ident.calculateHash());
            if ( (con.getAttemptedPeer() != null) && (!ident.getHash().equals(con.getAttemptedPeer())) ) {
                changedIdents = true;
                _pendingConnectionsByIdent.remove(con.getAttemptedPeer());
                changedMsgs = (List)_pendingMessages.remove(con.getAttemptedPeer());
            }
            
            if (!alreadyConnected)
                waitingMsgs = (List)_pendingMessages.remove(ident.calculateHash());
            
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuffer buf = new StringBuffer(256);
                buf.append("\nConnection to ").append(ident.getHash().toBase64().substring(0,6));
                buf.append(" built.  Already connected? ");
                buf.append(alreadyConnected);
                buf.append("\nconnectionsByAddress: (cur=").append(remAddr.toString()).append(") ");
                for (Iterator iter = _connectionsByAddress.keySet().iterator(); iter.hasNext(); ) {
                    String addr = (String)iter.next();
                    buf.append(addr).append(" ");
                }
                buf.append("\nconnectionsByIdent: ");
                for (Iterator iter = _connectionsByIdent.keySet().iterator(); iter.hasNext(); ) {
                    Hash h = (Hash)iter.next();
                    buf.append(h.toBase64().substring(0,6)).append(" ");
                }
                
                _log.debug(buf.toString());
            }
        }
        
        if (changedIdents) {
            _context.shitlist().shitlistRouter(con.getAttemptedPeer(), "Changed identities");
            if (changedMsgs != null) {
                for (int i = 0; i < changedMsgs.size(); i++) {
                    OutNetMessage cur = (OutNetMessage)changedMsgs.get(i);
                    cur.timestamp("changedIdents");
                    afterSend(cur, false, false, 0);
                }
            }
        }
        
        if (alreadyConnected) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Closing new duplicate");
            con.setTransport(this);
            con.closeConnection();
        } else {
            con.setTransport(this);
        
            if (waitingMsgs != null) {
                for (int i = 0; i < waitingMsgs.size(); i++) {
                    con.addMessage((OutNetMessage)waitingMsgs.get(i));
                }
            }

            _context.shitlist().unshitlistRouter(ident.calculateHash());

            con.runConnection();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connection set to run");
        }
    }
    
    void connectionClosed(TCPConnection con) {
        synchronized (_connectionLock) {
            TCPConnection cur = (TCPConnection)_connectionsByIdent.remove(con.getRemoteRouterIdentity().getHash());
            if (cur != con)
                _connectionsByIdent.put(cur.getRemoteRouterIdentity().getHash(), cur);
            cur = (TCPConnection)_connectionsByAddress.remove(con.getRemoteAddress().toString());
            if (cur != con)
                _connectionsByAddress.put(cur.getRemoteAddress().toString(), cur);
            
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuffer buf = new StringBuffer(256);
                buf.append("\nCLOSING ").append(con.getRemoteRouterIdentity().getHash().toBase64().substring(0,6));
                buf.append(".");
                buf.append("\nconnectionsByAddress: (cur=").append(con.getRemoteAddress().toString()).append(") ");
                for (Iterator iter = _connectionsByAddress.keySet().iterator(); iter.hasNext(); ) {
                    String addr = (String)iter.next();
                    buf.append(addr).append(" ");
                }
                buf.append("\nconnectionsByIdent: ");
                for (Iterator iter = _connectionsByIdent.keySet().iterator(); iter.hasNext(); ) {
                    Hash h = (Hash)iter.next();
                    buf.append(h.toBase64().substring(0,6)).append(" ");
                }
                
                _log.debug(buf.toString(), new Exception("Closed by"));
            }
        }
    }
    
    /**
     * Blocking call from when a remote peer tells us what they think our 
     * IP address is.  This may do absolutely nothing, or it may fire up a 
     * new socket listener after stopping an existing one.
     *
     * @param address address that the remote host said was ours
     */
    void ourAddressReceived(String address) {
        synchronized (_listener) { // no need to lock on the whole TCPTransport
            if (allowAddressUpdate()) {
                int port = getPort();
                TCPAddress addr = new TCPAddress(address, port);
                if (addr.getPort() > 0) {
                    if (allowAddress(addr)) {
                        if (_myAddress != null) {
                            if (addr.getAddress().equals(_myAddress.getAddress())) {
                                // ignore, since there is no change
                                return;
                            }
                        }
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Update our local address to " + address);
                        updateAddress(addr);
                    }
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Address specified is not valid [" + address + ":" + port + "]");
                }
            } else {
                // either we have explicitly specified our IP address, or 
                // we are already connected to some people.
            }
        }
    }
    
    public RouterAddress startListening() { 
        configureLocalAddress();
        _listener.startListening();
        if (_myAddress != null) {
            return _myAddress.toRouterAddress();
        } else {
            return null;
        }
    }
    
    public void stopListening() {
        _listener.stopListening();
    }
    
    /**
     * Should we listen to all interfaces, or just the one specified in
     * our TCPAddress?
     *
     */
    boolean shouldListenToAllInterfaces() { 
        String val = getContext().getProperty(BIND_ALL_INTERFACES, "TRUE");
        return Boolean.valueOf(val).booleanValue();
    }
    
    private SimpleDateFormat _fmt = new SimpleDateFormat("dd MMM HH:mm:ss");
    
    /**
     * Add the given message to the list of most recent connection 
     * establishment error messages.  A timestamp is prefixed to it before
     * being rendered on the router console.
     *
     */
    void addConnectionErrorMessage(String msg) {
        synchronized (_fmt) {
            msg = _fmt.format(new Date(_context.clock().now())) + ": " + msg;
        }
        synchronized (_lastConnectionErrors) {
            while (_lastConnectionErrors.size() >= MAX_ERR_MESSAGES)
                _lastConnectionErrors.remove(0);
            _lastConnectionErrors.add(msg);
        }
    }
    
    String getMyHost() { 
        if (_myAddress != null) 
            return _myAddress.getHost();
        else
            return null;
    }
    public String getStyle() { return STYLE; }
    ConnectionTagManager getTagManager() { return _tagManager; }
    
    /**
     * Initialize the _myAddress var with our local address (if possible)
     *
     */
    private void configureLocalAddress() {
        String addr = _context.getProperty(LISTEN_ADDRESS);
        int port = getPort();
        if ( (addr == null) || (addr.trim().length() <= 0) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("External address is not specified - autodetecting IP (be sure to forward port " + port + ")");
            return;
        }
        if (port != -1) {
            TCPAddress address = new TCPAddress(addr, port);
            boolean ok = allowAddress(address);
            if (ok) {
                _myAddress = address;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("External address " + addr + " is not valid");
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("External port is not valid");
        }   
    }
    
    /**
     * Is the given address a valid one that we could listen to or contact? 
     *
     */
    boolean allowAddress(TCPAddress address) {
        if (address == null) return false;
        if ( (address.getPort() <= 0) || (address.getPort() > 65535) )
            return false;
        if (!address.isPubliclyRoutable()) {
            String allowLocal = _context.getProperty(LISTEN_ALLOW_LOCAL, "false");
            if (Boolean.valueOf(allowLocal).booleanValue()) {
                return true;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("External address " + address + " is not publicly routable");
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Blocking call to unconditionally update our listening address to the 
     * one specified, updating the routerInfo, etc.
     *
     */
    private void updateAddress(TCPAddress addr) {
        boolean restartListener = true;
        if ( (addr.getPort() == getPort()) && (shouldListenToAllInterfaces()) )
            restartListener = false;
        
        RouterAddress routerAddr = addr.toRouterAddress();
        _myAddress = addr;
        
        if (restartListener)
            _listener.stopListening();
        
        replaceAddress(routerAddr);
        
        _context.router().rebuildRouterInfo();
      
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating our local address to include " + addr.toString() 
                      + " and modified our routerInfo to have: " 
                      + _context.router().getRouterInfo().getAddresses());
        
        // safe to do multiple times
        _listener.startListening();
    }
    
    /**
     * Determine whether we should listen to the peer when they give us what they
     * say our IP address is.  We should allow a peer to specify our IP address 
     * if and only if we have not configured our own address explicitly and we 
     * have no fully established connections.
     *
     */
    private boolean allowAddressUpdate() {
        boolean addressSpecified = (null != _context.getProperty(LISTEN_ADDRESS));
        if (addressSpecified) 
            return false;
        int connectedPeers = countActivePeers();
        return (connectedPeers == 0);
    }
        
    /**
     * What port should we be reachable on?  
     *
     * @return the port number, or -1 if there is no valid port
     */
    int getPort() {
        if ( (_myAddress != null) && (_myAddress.getPort() > 0) )
            return _myAddress.getPort();
        
        String port = _context.getProperty(LISTEN_PORT, DEFAULT_LISTEN_PORT+"");
        if (port != null) {
            try {
                int portNum = Integer.parseInt(port);
                if ( (portNum >= 1) && (portNum < 65535) ) 
                    return portNum;
            } catch (NumberFormatException nfe) {
                // fallthrough
            }
        } 
          
        return -1;
    }

    public List getMostRecentErrorMessages() { 
        return _lastConnectionErrors; 
    }
    
    /**
     * How many peers can we talk to right now?
     *
     */
    public int countActivePeers() { 
        synchronized (_connectionLock) {
            return _connectionsByIdent.size();
        }
    }
    
    /**
     * The transport is done sending this message.  This exposes the 
     * superclass's protected method to the current package.
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param msToSend how long it took to transfer the data to the peer
     * @param allowRequeue true if we should try other transports if available
     */
    public void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        super.afterSend(msg, sendSuccessful, allowRequeue, msToSend);
    }
    
    /**
     * Blocking call to retrieve the next peer that we want to establish a 
     * connection with.
     *
     */
    RouterInfo getNextPeer() {
        while (_context.router().isAlive()) {
            synchronized (_connectionLock) {
                for (Iterator iter = _pendingMessages.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    List msgs = (List)_pendingMessages.get(peer);
                    if (_pendingConnectionsByIdent.contains(peer))
                        continue; // we're already trying to talk to them

                    if (msgs.size() <= 0) 
                        continue; // uh...
                    OutNetMessage msg = (OutNetMessage)msgs.get(0);
                    RouterAddress addr = msg.getTarget().getTargetAddress(STYLE);
                    if (addr == null) {
                        _log.error("Message target has no TCP addresses! "  + msg.getTarget());
                        iter.remove();
                        _context.shitlist().shitlistRouter(peer, "Peer " 
                                                           + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) 
                                                           + " has no addresses");
                        _context.netDb().fail(peer);
                        for (int i = 0; i < msgs.size(); i++) {
                            OutNetMessage cur = (OutNetMessage)msgs.get(i);
                            cur.timestamp("no TCP addresses");
                            afterSend(cur, false, false, 0);
                        }
                        continue;
                    }
                    TCPAddress tcpAddr = new TCPAddress(addr);
                    if (tcpAddr.getPort() <= 0) {
                        iter.remove();
                        _context.shitlist().shitlistRouter(peer, "Peer " 
                                                           + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) 
                                                           + " has only invalid addresses");
                        _context.netDb().fail(peer);
                        for (int i = 0; i < msgs.size(); i++) {
                            OutNetMessage cur = (OutNetMessage)msgs.get(i);
                            cur.timestamp("invalid addresses");
                            afterSend(cur, false, false, 0);
                        }
                        continue; // invalid
                    }
                    if (_pendingConnectionsByAddress.contains(tcpAddr.toString()))
                        continue; // we're already trying to talk to someone at their address
                    
                    if (_context.routerHash().equals(peer)) {
                        _log.error("Message points at us! "  + msg.getTarget());
                        iter.remove();
                        _context.netDb().fail(peer);
                        for (int i = 0; i < msgs.size(); i++) {
                            OutNetMessage cur = (OutNetMessage)msgs.get(i);
                            cur.timestamp("points at us");
                            afterSend(cur, false, false, 0);
                        }
                        continue;
                    }
                    if ( (_myAddress != null) && (_myAddress.equals(tcpAddr)) ) {
                        _log.error("Message points at our old TCP addresses! "  + msg.getTarget());
                        iter.remove();
                        _context.shitlist().shitlistRouter(peer, "This is our old address...");
                        _context.netDb().fail(peer);
                        for (int i = 0; i < msgs.size(); i++) {
                            OutNetMessage cur = (OutNetMessage)msgs.get(i);
                            cur.timestamp("points at our ip");
                            afterSend(cur, false, false, 0);
                        }
                        continue;
                    }
                    if (!allowAddress(tcpAddr)) {
                        _log.error("Message points at illegal address! "  
                                   + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6));
                        
                        iter.remove();
                        _context.shitlist().shitlistRouter(peer, "Invalid addressaddress...");
                        _context.netDb().fail(peer);
                        for (int i = 0; i < msgs.size(); i++) {
                            OutNetMessage cur = (OutNetMessage)msgs.get(i);
                            cur.timestamp("points at an illegal address");
                            afterSend(cur, false, false, 0);
                        }
                        continue;
                    }

                    // ok, this is someone we can try to contact.  mark it as ours.
                    _pendingConnectionsByIdent.add(peer);
                    _pendingConnectionsByAddress.add(tcpAddr.toString());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Add pending connection to: " + peer.toBase64().substring(0,6));
                    return msg.getTarget();
                }
                
                try {
                    _connectionLock.wait();
                } catch (InterruptedException ie) {}
            }
        }
        return null;
    }
    
    /** Called after an establisher finished (or failed) connecting to the peer */
    void establishmentComplete(RouterInfo info) {
        TCPAddress addr = new TCPAddress(info.getTargetAddress(STYLE));
        Hash peer = info.getIdentity().calculateHash();
        List msgs = null;
        synchronized (_connectionLock) {
            _pendingConnectionsByAddress.remove(addr.toString());
            _pendingConnectionsByIdent.remove(peer);
            
            msgs = (List)_pendingMessages.remove(peer);
        }
        
        if (msgs != null) {
            // messages are only available if the connection failed (since 
            // connectionEstablished clears them otherwise)
            for (int i = 0; i < msgs.size(); i++) {
                OutNetMessage msg = (OutNetMessage)msgs.get(i);
                msg.timestamp("establishmentComplete(failed)");
                afterSend(msg, false);
            }
        }
    }
    
    /** Make this stuff pretty (only used in the old console) */
    public String renderStatusHTML() { 
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<b>Connections:</b><ul>\n");
        synchronized (_connectionLock) {
            for (Iterator iter = _connectionsByIdent.values().iterator(); iter.hasNext(); ) {
                TCPConnection con = (TCPConnection)iter.next();
                buf.append("<li>");
                buf.append(con.getRemoteRouterIdentity().getHash().toBase64().substring(0,6));
                buf.append(": up for ").append(DataHelper.formatDuration(con.getLifetime()));
                buf.append(" transferring at ");
                long bps = con.getSendRate();
                if (bps < 1024)
                    buf.append(bps).append("Bps");
                else 
                    buf.append((int)(bps/1024)).append("KBps");
                buf.append("</li>\n");
            }
            buf.append("</ul>\n");
            
            buf.append("<b>Connections being built:</b><ul>\n");
            for (Iterator iter = _pendingConnectionsByIdent.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append("<li>");
                buf.append(peer.toBase64().substring(0,6));
                buf.append("</li>\n");
            }
            buf.append("</ul>\n");
        }
        
        buf.append("<b>Most recent connection errors:</b><ul>");
        synchronized (_lastConnectionErrors) {
            for (int i = _lastConnectionErrors.size()-1; i >= 0; i--) {
                String msg = (String)_lastConnectionErrors.get(i);
                buf.append("<li>").append(msg).append("</li>\n");
            }
        }
        buf.append("</ul>");
        
        return buf.toString();
    }
    
}
