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
            TCPConnection con = null;
            boolean newPeer = false;
            synchronized (_connectionLock) {
                Hash peer = msg.getTarget().getIdentity().calculateHash();
                con = (TCPConnection)_connectionsByIdent.get(peer);
                if (con == null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No connections to " + peer.toBase64() 
                                   + ", request one");
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
        List oldCons = null;
        synchronized (_connectionLock) {
            if (_connectionsByAddress.containsKey(remAddr.toString())) {
                if (oldCons == null)
                    oldCons = new ArrayList(1);
                oldCons.add(_connectionsByAddress.remove(remAddr.toString()));
            }
            _connectionsByAddress.put(remAddr.toString(), con);
            
            if (_connectionsByIdent.containsKey(ident.calculateHash())) {
                if (oldCons == null)
                    oldCons = new ArrayList(1);
                oldCons.add(_connectionsByIdent.remove(ident.calculateHash()));
            }
            _connectionsByIdent.put(ident.calculateHash(), con);
            
            // just drop the _pending connections - the establisher should fail
            // them accordingly.
            _pendingConnectionsByAddress.remove(remAddr.toString());
            _pendingConnectionsByIdent.remove(ident.calculateHash());
            
            waitingMsgs = (List)_pendingMessages.remove(ident.calculateHash());
        }
        
        // close any old connections, moving any queued messages to the new one
        if (oldCons != null) {
            for (int i = 0; i < oldCons.size(); i++) {
                TCPConnection cur = (TCPConnection)oldCons.get(i);
                List msgs = cur.clearPendingMessages();
                for (int j = 0; j < msgs.size(); j++) {
                    con.addMessage((OutNetMessage)msgs.get(j));
                }
                cur.closeConnection();
            }
        }
        
        if (waitingMsgs != null) {
            for (int i = 0; i < waitingMsgs.size(); i++) {
                con.addMessage((OutNetMessage)waitingMsgs.get(i));
            }
        }
        
        _context.shitlist().unshitlistRouter(ident.calculateHash());
        
        con.setTransport(this);
        con.runConnection();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Connection set to run");
    }
    
    void connectionClosed(TCPConnection con) {
        synchronized (_connectionLock) {
            _connectionsByIdent.remove(con.getRemoteRouterIdentity().getHash());
            _connectionsByAddress.remove(con.getRemoteAddress().toString());
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
                    updateAddress(addr);
                }
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Address specified is not valid [" + address + ":" + port + "]");
            }
        }
    }
    
    public RouterAddress startListening() { 
        configureLocalAddress();
        if (_myAddress != null) {
            _listener.startListening();
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
     * establishment error messages.  This should include a timestamp of 
     * some sort in it.
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
    
    TCPAddress getMyAddress() { return _myAddress; }
    public String getStyle() { return STYLE; }
    ConnectionTagManager getTagManager() { return _tagManager; }
    
    /**
     * Initialize the _myAddress var with our local address (if possible)
     *
     */
    private void configureLocalAddress() {
        String addr = _context.getProperty(LISTEN_ADDRESS);
        int port = getPort();
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
     * Is the given address a valid one that we could listen to? 
     *
     */
    private boolean allowAddress(TCPAddress address) {
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
        RouterAddress routerAddr = addr.toRouterAddress();
        _myAddress = addr;
        _listener.stopListening();
        _listener.startListening();
        
        Set addresses = getCurrentAddresses();
        List toRemove = null;
        for (Iterator iter = addresses.iterator(); iter.hasNext(); ) {
            RouterAddress cur = (RouterAddress)iter.next();
            if (STYLE.equals(cur.getTransportStyle())) {
                if (toRemove == null)
                    toRemove = new ArrayList(1);
                toRemove.add(cur);
            }
        }
        if (toRemove != null) {
            for (int i = 0; i < toRemove.size(); i++) {
                addresses.remove(toRemove.get(i));
            }
        }
        addresses.add(routerAddr);
        
        _context.router().rebuildRouterInfo();
        
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
    private int getPort() {
        if ( (_myAddress != null) && (_myAddress.getPort() > 0) )
            return _myAddress.getPort();
        
        String port = _context.getProperty(LISTEN_PORT);
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
        while (true) {
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
                        continue;
                    }
                    TCPAddress tcpAddr = new TCPAddress(addr);
                    if (tcpAddr.getPort() <= 0)
                        continue; // invalid
                    if (_pendingConnectionsByAddress.contains(tcpAddr.toString()))
                        continue; // we're already trying to talk to someone at their address

                    // ok, this is someone we can try to contact.  mark it as ours.
                    _pendingConnectionsByIdent.add(peer);
                    _pendingConnectionsByAddress.add(tcpAddr.toString());
                    return msg.getTarget();
                }
                
                try {
                    _connectionLock.wait();
                } catch (InterruptedException ie) {}
            }
        }
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
