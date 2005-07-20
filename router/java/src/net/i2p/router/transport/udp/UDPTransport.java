package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.io.IOException;
import java.io.Writer;

import java.text.DecimalFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportBid;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 *
 */
public class UDPTransport extends TransportImpl implements TimedWeightedPriorityMessageQueue.FailedListener {
    private RouterContext _context;
    private Log _log;
    private UDPEndpoint _endpoint;
    /** Peer (Hash) to PeerState */
    private Map _peersByIdent;
    /** RemoteHostId to PeerState */
    private Map _peersByRemoteHost;
    /** Relay tag (base64 String) to PeerState */
    private Map _peersByRelayTag;
    private PacketHandler _handler;
    private EstablishmentManager _establisher;
    private MessageQueue _outboundMessages;
    private OutboundMessageFragments _fragments;
    private OutboundMessageFragments.ActiveThrottle _activeThrottle;
    private OutboundRefiller _refiller;
    private PacketPusher _pusher;
    private InboundMessageFragments _inboundFragments;
    private UDPFlooder _flooder;
    private ExpirePeerEvent _expireEvent;
    
    /** list of RelayPeer objects for people who will relay to us */
    private List _relayPeers;

    /** summary info to distribute */
    private RouterAddress _externalAddress;
    /** port number on which we can be reached, or -1 */
    private int _externalListenPort;
    /** IP address of externally reachable host, or null */
    private InetAddress _externalListenHost;
    /** introduction key */
    private SessionKey _introKey;
    
    /** shared fast bid for connected peers */
    private TransportBid _fastBid;
    /** shared slow bid for unconnected peers */
    private TransportBid _slowBid;
    /** shared slow bid for unconnected peers when we want to prefer UDP */
    private TransportBid _slowPreferredBid;

    public static final String STYLE = "SSUv1";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";

    /** define this to explicitly set an external IP address */
    public static final String PROP_EXTERNAL_HOST = "i2np.udp.host";
    /** define this to explicitly set an external port */
    public static final String PROP_EXTERNAL_PORT = "i2np.udp.port";
    /** 
     * If i2np.udp.alwaysPreferred is set, the UDP bids will always be under 
     * the bid from the TCP transport - even if a TCP connection already 
     * exists.  The default is to prefer UDP unless no UDP session exists and 
     * a TCP connection already exists.
     */
    public static final String PROP_ALWAYS_PREFER_UDP = "i2np.udp.alwaysPreferred";
    
    
    /** how many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;
    
    /** configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };

    /** should we flood all UDP peers with the configured rate? */
    private static final boolean SHOULD_FLOOD_PEERS = false;
    
    private static final int MAX_CONSECUTIVE_FAILED = 5;
    
    public UDPTransport(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new HashMap(128);
        _peersByRemoteHost = new HashMap(128);
        _peersByRelayTag = new HashMap(128);
        _endpoint = null;
        
        TimedWeightedPriorityMessageQueue mq = new TimedWeightedPriorityMessageQueue(ctx, PRIORITY_LIMITS, PRIORITY_WEIGHT, this);
        _outboundMessages = mq;
        _activeThrottle = mq;
        _relayPeers = new ArrayList(1);

        _fastBid = new SharedBid(50);
        _slowBid = new SharedBid(1000);
        _slowPreferredBid = new SharedBid(75);
        
        _fragments = new OutboundMessageFragments(_context, this, _activeThrottle);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        _flooder = new UDPFlooder(_context, this);
        _expireEvent = new ExpirePeerEvent();
        
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago did we receive from a dropped peer (duration == session lifetime", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    public void startup() {
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_handler != null) 
            _handler.shutdown();
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
        if (_flooder != null)
            _flooder.shutdown();
        
        _introKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(_context.routerHash().getData(), 0, _introKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        
        rebuildExternalAddress();
        
        if (_endpoint == null) {
            int port = -1;
            if (_externalListenPort <= 0) {
                // no explicit external port, so lets try an internal one
                String portStr = _context.getProperty(PROP_INTERNAL_PORT);
                if (portStr != null) {
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException nfe) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Invalid port specified [" + portStr + "]");
                    }
                }
                if (port <= 0) {
                    port = 1024 + _context.random().nextInt(31*1024);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Selecting a random port to bind to: " + port);
                }
            } else {
                port = _externalListenPort;
                if (_log.shouldLog(Log.INFO))
                    _log.info("Binding to the explicitly specified external port: " + port);
            }
            try {
                _endpoint = new UDPEndpoint(_context, port);
            } catch (SocketException se) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "Unable to listen on the UDP port (" + port + ")", se);
                return;
            }
        }
        
        if (_establisher == null)
            _establisher = new EstablishmentManager(_context, this);
        
        if (_handler == null)
            _handler = new PacketHandler(_context, this, _endpoint, _establisher, _inboundFragments);
        
        if (_refiller == null)
            _refiller = new OutboundRefiller(_context, _fragments, _outboundMessages);
        
        if (_flooder == null)
            _flooder = new UDPFlooder(_context, this);
        
        _endpoint.startup();
        _establisher.startup();
        _handler.startup();
        _fragments.startup();
        _inboundFragments.startup();
        _pusher = new PacketPusher(_context, _fragments, _endpoint.getSender());
        _pusher.startup();
        _refiller.startup();
        _flooder.startup();
        _expireEvent.setIsAlive(true);
    }
    
    public void shutdown() {
        if (_flooder != null)
            _flooder.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_handler != null)
            _handler.shutdown();
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
        _expireEvent.setIsAlive(false);
    }
    
    /**
     * Introduction key that people should use to contact us
     *
     */
    public SessionKey getIntroKey() { return _introKey; }
    public int getLocalPort() { return _externalListenPort; }
    public InetAddress getLocalAddress() { return _externalListenHost; }
    public int getExternalPort() { return _externalListenPort; }
    
    /**
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     */
    void externalAddressReceived(byte ourIP[], int ourPort) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("External address received: " + Base64.encode(ourIP) + ":" + ourPort);
        
        if (explicitAddressSpecified()) 
            return;
            
        synchronized (this) {
            if ( (_externalListenHost == null) ||
                 (!eq(_externalListenHost.getAddress(), _externalListenPort, ourIP, ourPort)) ) {
                try {
                    _externalListenHost = InetAddress.getByAddress(ourIP);
                    _externalListenPort = ourPort;
                    rebuildExternalAddress();
                    replaceAddress(_externalAddress);
                } catch (UnknownHostException uhe) {
                    _externalListenHost = null;
                }
            }
        }
    }
    
    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }
    
    /** 
     * get the state for the peer at the given remote host/port, or null 
     * if no state exists
     */
    public PeerState getPeerState(InetAddress remoteHost, int remotePort) {
        RemoteHostId hostInfo = new RemoteHostId(remoteHost.getAddress(), remotePort);
        synchronized (_peersByRemoteHost) {
            return (PeerState)_peersByRemoteHost.get(hostInfo);
        }
    }
    
    /** 
     * get the state for the peer with the given ident, or null 
     * if no state exists
     */
    public PeerState getPeerState(Hash remotePeer) { 
        synchronized (_peersByIdent) {
            return (PeerState)_peersByIdent.get(remotePeer);
        }
    }
    
    /**
     * get the state for the peer being introduced, or null if we aren't
     * offering to introduce anyone with that tag.
     */
    public PeerState getPeerState(String relayTag) {
        synchronized (_peersByRelayTag) {
            return (PeerState)_peersByRelayTag.get(relayTag);
        }
    }
    
    /** 
     * add the peer info, returning true if it went in properly, false if
     * it was rejected (causes include peer ident already connected, or no
     * remote host info known
     *
     */
    boolean addRemotePeerState(PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Add remote peer state: " + peer);
        PeerState oldPeer = null;
        if (peer.getRemotePeer() != null) {
            synchronized (_peersByIdent) {
                oldPeer = (PeerState)_peersByIdent.put(peer.getRemotePeer(), peer);
                if ( (oldPeer != null) && (oldPeer != peer) ) {
                    // should we transfer the oldPeer's RTT/RTO/etc? nah
                    // or perhaps reject the new session?  nah, 
                    // using the new one allow easier reconnect
                }
            }
        }
        
        if ( (oldPeer != null) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Peer already connected: old=" + oldPeer + " new=" + peer, new Exception("dup"));
        oldPeer = null;
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId == null) return false;
        
        synchronized (_peersByRemoteHost) {
            oldPeer = (PeerState)_peersByRemoteHost.put(remoteId, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                //_peersByRemoteHost.put(remoteString, oldPeer);
                //return false;
            }
        }
        
        if ( (oldPeer != null) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Peer already connected: old=" + oldPeer + " new=" + peer, new Exception("dup"));
        
        _activeThrottle.unchoke(peer.getRemotePeer());
        _context.shitlist().unshitlistRouter(peer.getRemotePeer());

        if (SHOULD_FLOOD_PEERS)
            _flooder.addPeer(peer);
        
        _expireEvent.add(peer);
        
        return true;
    }
    
    private void dropPeer(PeerState peer) {
        dropPeer(peer, true);
    }
    private void dropPeer(PeerState peer, boolean shouldShitlist) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Dropping remote peer: " + peer + " shitlist? " + shouldShitlist, new Exception("Dropped by"));
        if (peer.getRemotePeer() != null) {
            if (shouldShitlist) {
                long now = _context.clock().now();
                _context.statManager().addRateData("udp.droppedPeer", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
                _context.shitlist().shitlistRouter(peer.getRemotePeer(), "dropped after too many retries");
            }
            synchronized (_peersByIdent) {
                _peersByIdent.remove(peer.getRemotePeer());
            }
        }
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId != null) {
            synchronized (_peersByRemoteHost) {
                _peersByRemoteHost.remove(remoteId);
            }
        }
        
        // unchoke 'em, but just because we'll never talk again...
        _activeThrottle.unchoke(peer.getRemotePeer());
        
        if (SHOULD_FLOOD_PEERS)
            _flooder.removePeer(peer);
        _expireEvent.remove(peer);
    }
    
    int send(UDPPacket packet) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending packet " + packet);
        return _endpoint.send(packet); 
    }
    
    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        Hash to = toAddress.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an established peer: " + peer);
            return _fastBid;
        } else {
            if (null == toAddress.getTargetAddress(STYLE))
                return null;

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an unestablished peer: " + to.toBase64());
            if (alwaysPreferUDP())
                return _slowPreferredBid;
            else
                return _slowBid;
        }
    }

    private boolean alwaysPreferUDP() {
        String pref = _context.getProperty(PROP_ALWAYS_PREFER_UDP);
        return (pref != null) && "true".equals(pref);
    }
    
    public String getStyle() { return STYLE; }
    public void send(OutNetMessage msg) { 
        if (msg == null) return;
        if (msg.getTarget() == null) return;
        if (msg.getTarget().getIdentity() == null) return;
        
        Hash to = msg.getTarget().getIdentity().calculateHash();
        if (getPeerState(to) != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an established peer: " + to.toBase64());
            _outboundMessages.add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an unestablished peer: " + to.toBase64());
            _establisher.establish(msg);
        }
    }
    void send(I2NPMessage msg, PeerState peer) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Injecting a data message to a new peer: " + peer);
        OutboundMessageState state = new OutboundMessageState(_context);
        state.initialize(msg, peer);
        _fragments.add(state);
    }

    public OutNetMessage getNextMessage() { return getNextMessage(-1); }
    /**
     * Get the next message, blocking until one is found or the expiration
     * reached.
     *
     * @param blockUntil expiration, or -1 if indefinite
     */
    public OutNetMessage getNextMessage(long blockUntil) {
        return _outboundMessages.getNext(blockUntil);
    }

    
    // we don't need the following, since we have our own queueing
    protected void outboundMessageReady() { throw new UnsupportedOperationException("Not used for UDP"); }
    
    public RouterAddress startListening() {
        startup();
        return _externalAddress;
    }
    
    public void stopListening() {
        shutdown();
    }
    
    void setExternalListenPort(int port) { _externalListenPort = port; }
    void setExternalListenHost(InetAddress addr) { _externalListenHost = addr; }
    void setExternalListenHost(byte addr[]) throws UnknownHostException { 
        _externalListenHost = InetAddress.getByAddress(addr); 
    }
    void addRelayPeer(String host, int port, byte tag[], SessionKey relayIntroKey) {
        if ( (_externalListenPort > 0) && (_externalListenHost != null) ) 
            return; // no need for relay peers, as we are reachable
        
        RelayPeer peer = new RelayPeer(host, port, tag, relayIntroKey);
        synchronized (_relayPeers) {
            _relayPeers.add(peer);
        }
    }

    private boolean explicitAddressSpecified() {
        return (_context.getProperty(PROP_EXTERNAL_HOST) != null);
    }
    
    void rebuildExternalAddress() {
        // if the external port is specified, we want to use that to bind to even
        // if we don't know the external host.
        String port = _context.getProperty(PROP_EXTERNAL_PORT);
        if (port != null) { 
            try {
                _externalListenPort = Integer.parseInt(port);    
            } catch (NumberFormatException nfe) {
                _externalListenPort = -1;
            }
        }
        
        if (explicitAddressSpecified()) {
            try {
                String host = _context.getProperty(PROP_EXTERNAL_HOST);
                _externalListenHost = InetAddress.getByName(host);
            } catch (UnknownHostException uhe) {
                _externalListenHost = null;
            }
        }
            
        Properties options = new Properties();
        if ( (_externalListenPort > 0) && (_externalListenHost != null) ) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(_externalListenPort));
            options.setProperty(UDPAddress.PROP_HOST, _externalListenHost.getHostAddress());
        } else {
            // grab 3 relays randomly
            synchronized (_relayPeers) {
                Collections.shuffle(_relayPeers);
                int numPeers = PUBLIC_RELAY_COUNT;
                if (numPeers > _relayPeers.size())
                    numPeers = _relayPeers.size();
                for (int i = 0; i < numPeers; i++) {
                    RelayPeer peer = (RelayPeer)_relayPeers.get(i);
                    options.setProperty("relay." + i + ".host", peer.getHost());
                    options.setProperty("relay." + i + ".port", String.valueOf(peer.getPort()));
                    options.setProperty("relay." + i + ".tag", Base64.encode(peer.getTag()));
                    options.setProperty("relay." + i + ".key", peer.getIntroKey().toBase64());
                }
            }
            if (options.size() <= 0)
                return;
        }
        options.setProperty(UDPAddress.PROP_INTRO_KEY, _introKey.toBase64());
        
        RouterAddress addr = new RouterAddress();
        addr.setCost(5);
        addr.setExpiration(null);
        addr.setTransportStyle(STYLE);
        addr.setOptions(options);
        
        _externalAddress = addr;
        if (_log.shouldLog(Log.INFO))
            _log.info("Address rebuilt: " + addr);
        replaceAddress(addr);
    }
    
    public void failed(OutboundMessageState msg) {
        if (msg == null) return;
        int consecutive = 0;
        if ( (msg.getPeer() != null) && 
             ( (msg.getMaxSends() >= OutboundMessageFragments.MAX_VOLLEYS) ||
               (msg.isExpired())) ) {
            consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Consecutive failure #" + consecutive + " sending to " + msg.getPeer());
            if (consecutive > MAX_CONSECUTIVE_FAILED)
                dropPeer(msg.getPeer());
        }
        failed(msg.getMessage());
    }
    
    public void failed(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.WARN))
            _log.warn("Sending message failed: " + msg, new Exception("failed from"));
        super.afterSend(msg, false);
    }
    public void succeeded(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Sending message succeeded: " + msg);
        super.afterSend(msg, true);
    }

    public int countActivePeers() {
        long now = _context.clock().now();
        int active = 0;
        int inactive = 0;
        synchronized (_peersByIdent) {
            for (Iterator iter = _peersByIdent.values().iterator(); iter.hasNext(); ) {
                PeerState peer = (PeerState)iter.next();
                if (now-peer.getLastReceiveTime() > 5*60*1000)
                    inactive++;
                else
                    active++;
            }
        }
        return active;
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        List peers = null;
        synchronized (_peersByIdent) {
            peers = new ArrayList(_peersByIdent.values());
        }
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("<b>UDP connections: ").append(peers.size()).append("</b><br />\n");
        buf.append("<table border=\"1\">\n");
        buf.append(" <tr><td><b>peer</b></td><td><b>activity (in/out)</b></td>");
        buf.append("     <td><b>transfer (in/out)</b></td>\n");
        buf.append("     <td><b>uptime</b></td><td><b>skew</b></td>\n");
        buf.append("     <td><b>cwnd</b></td><td><b>ssthresh</b></td>\n");
        buf.append("     <td><b>rtt</b></td><td><b>dev</b></td><td><b>rto</b></td>\n");
        buf.append("     <td><b>send</b></td><td><b>recv</b></td>\n");
        buf.append("     <td><b>resent</b></td><td><b>dupRecv</b></td>\n");
        buf.append(" </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (int i = 0; i < peers.size(); i++) {
            PeerState peer = (PeerState)peers.get(i);
            if (now-peer.getLastReceiveTime() > 60*60*1000)
                continue; // don't include old peers
            
            buf.append("<tr>");
            
            String name = peer.getRemotePeer().toBase64().substring(0,6);
            buf.append("<td nowrap>");
            buf.append("<a href=\"netdb.jsp#");
            buf.append(name);
            buf.append("\">");
            buf.append(name).append("@");
            byte ip[] = peer.getRemoteIP();
            for (int j = 0; j < ip.length; j++) {
                buf.append(ip[j] & 0xFF);
                if (j + 1 < ip.length)
                    buf.append('.');
            }
            buf.append(':').append(peer.getRemotePort());
            buf.append("</a>");
            if (_activeThrottle.isChoked(peer.getRemotePeer()))
                buf.append(" [choked]");
            if (peer.getConsecutiveFailedSends() > 0)
                buf.append(" [").append(peer.getConsecutiveFailedSends()).append(" failures]");
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append((now-peer.getLastReceiveTime())/1000);
            buf.append("s/");
            buf.append((now-peer.getLastSendTime())/1000);
            buf.append("s</td>");
    
            buf.append("<td>");
            buf.append(formatKBps(peer.getReceiveBps()));
            buf.append("KBps/");
            buf.append(formatKBps(peer.getSendBps()));
            buf.append("KBps</td>");

            buf.append("<td>");
            buf.append(DataHelper.formatDuration(now-peer.getKeyEstablishedTime()));
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getClockSkew()/1000);
            buf.append("s</td>");

            buf.append("<td>");
            buf.append(peer.getSendWindowBytes()/1024);
            buf.append("K</td>");

            buf.append("<td>");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</td>");

            buf.append("<td>");
            buf.append(peer.getRTT());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getRTTDeviation());
            buf.append("</td>");

            buf.append("<td>");
            buf.append(peer.getRTO());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getPacketsTransmitted());
            buf.append("</td>");
            
            buf.append("<td>");
            buf.append(peer.getPacketsReceived());
            buf.append("</td>");
            
            double sendLostPct = (double)peer.getPacketsRetransmitted()/(double)PeerState.RETRANSMISSION_PERIOD_WIDTH;
            buf.append("<td>");
            //buf.append(formatPct(sendLostPct));
            buf.append(peer.getPacketRetransmissionRate());
            buf.append("</td>");
            
            double recvDupPct = (double)peer.getPacketsReceivedDuplicate()/(double)peer.getPacketsReceived();
            buf.append("<td>");
            buf.append(formatPct(recvDupPct));
            buf.append("</td>");

            buf.append("</tr>");
            out.write(buf.toString());
            buf.setLength(0);
        }
        
        out.write("</table>\n");
    }
    
    private static final DecimalFormat _fmt = new DecimalFormat("#,##0.00");
    private static final String formatKBps(int bps) {
        synchronized (_fmt) {
            return _fmt.format((float)bps/1024);
        }
    }
    private static final DecimalFormat _pctFmt = new DecimalFormat("#0.0%");
    private static final String formatPct(double pct) {
        synchronized (_pctFmt) {
            return _pctFmt.format(pct);
        }
    }
    
    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        public Transport getTransport() { return UDPTransport.this; }
        public String toString() { return "UDP bid @ " + getLatencyMs(); }
    }
    
    private class ExpirePeerEvent implements SimpleTimer.TimedEvent {
        private List _peers;
        // toAdd and toRemove are kept separate from _peers so that add and
        // remove calls won't block packet handling while the big iteration is
        // in process
        private List _toAdd;
        private List _toRemove;
        private boolean _alive;
        public ExpirePeerEvent() {
            _peers = new ArrayList(128);
            _toAdd = new ArrayList(4);
            _toRemove = new ArrayList(4);
        }
        public void timeReached() {
            long inactivityCutoff = _context.clock().now() - 10*60*1000;
            for (int i = 0; i < _peers.size(); i++) {
                PeerState peer = (PeerState)_peers.get(i);
                if (peer.getLastReceiveTime() < inactivityCutoff) {
                    dropPeer(peer, false);
                    _peers.remove(i);
                    i--;
                }
            }
            synchronized (_toAdd) {
                for (int i = 0; i < _toAdd.size(); i++) {
                    PeerState peer = (PeerState)_toAdd.get(i);
                    if (!_peers.contains(peer))
                        _peers.add(peer);
                }
                _toAdd.clear();
            }
            synchronized (_toRemove) {
                for (int i = 0; i < _toRemove.size(); i++) {
                    PeerState peer = (PeerState)_toRemove.get(i);
                    _peers.remove(peer);
                }
                _toRemove.clear();
            }
            if (_alive)
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 5*60*1000);
        }
        public void add(PeerState peer) {
            synchronized (_toAdd) {
                _toAdd.add(peer);
            }
        }
        public void remove(PeerState peer) {
            synchronized (_toRemove) {
                _toRemove.add(peer);
            }
        }
        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 5*60*1000);
            } else {
                SimpleTimer.getInstance().removeEvent(ExpirePeerEvent.this);
                synchronized (_toAdd) {
                    _toAdd.clear();
                }
                synchronized (_peers) {
                    _peers.clear();
                }
            }
        }
    }
}
