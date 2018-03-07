package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SigType;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import static net.i2p.router.transport.Transport.AddressSource.*;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

/**
 *  Keeps track of the enabled transports.
 *  Starts UPnP.
 *  Pluggable transport support incomplete.
 *
 *  Public only for a couple things in the console and Android.
 *  To be made package private.
 *  Everything external should go through CommSystemFacadeImpl.
 *  Not a public API, not for external use.
 *
 */
public class TransportManager implements TransportEventListener {
    private final Log _log;
    /**
     * Converted from List to prevent concurrent modification exceptions.
     * If we want more than one transport with the same style we will have to change this.
     */
    private final Map<String, Transport> _transports;
    /** locking: this */
    private final Map<String, Transport> _pluggableTransports;
    private final RouterContext _context;
    private final UPnPManager _upnpManager;
    private final DHSessionKeyBuilder.PrecalcRunner _dhThread;

    /** default true */
    public final static String PROP_ENABLE_UDP = "i2np.udp.enable";
    /** default true */
    public final static String PROP_ENABLE_NTCP = "i2np.ntcp.enable";
    /** default true */
    public final static String PROP_ENABLE_UPNP = "i2np.upnp.enable";

    private static final String PROP_ADVANCED = "routerconsole.advanced";
    
    /** not forever, since they may update */
    private static final long SIGTYPE_BANLIST_DURATION = 36*60*60*1000L;

    public TransportManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportManager.class);
        _context.statManager().createRateStat("transport.banlistOnUnreachable", "Add a peer to the banlist since none of the transports can reach them", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.banlistOnUsupportedSigType", "Add a peer to the banlist since signature type is unsupported", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.noBidsYetNotAllUnreachable", "Add a peer to the banlist since none of the transports can reach them", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailBanlisted", "Could not attempt to bid on message, as they were banlisted", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailSelf", "Could not attempt to bid on message, as it targeted ourselves", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailNoTransports", "Could not attempt to bid on message, as none of the transports could attempt it", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailAllTransports", "Could not attempt to bid on message, as all of the transports had failed", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _transports = new ConcurrentHashMap<String, Transport>(2);
        _pluggableTransports = new HashMap<String, Transport>(2);
        if (_context.getBooleanPropertyDefaultTrue(PROP_ENABLE_UPNP))
            _upnpManager = new UPnPManager(context, this);
        else
            _upnpManager = null;
        _dhThread = new DHSessionKeyBuilder.PrecalcRunner(context);
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  @since 0.9.16
     */
    synchronized void registerAndStart(Transport t) {
        String style = t.getStyle();
        if (style.equals(NTCPTransport.STYLE) || style.equals(UDPTransport.STYLE))
            throw new IllegalArgumentException("Builtin transport");
        if (_transports.containsKey(style) || _pluggableTransports.containsKey(style))
            throw new IllegalStateException("Dup transport");
        boolean shouldStart = !_transports.isEmpty();
        _pluggableTransports.put(style, t);
        addTransport(t);
        t.setListener(this);
        if (shouldStart) {
            initializeAddress(t);
            t.startListening();
            _context.router().rebuildRouterInfo();
        } // else will be started by configTransports() (unlikely)
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  @since 0.9.16
     */
    synchronized void stopAndUnregister(Transport t) {
        String style = t.getStyle();
        if (style.equals(NTCPTransport.STYLE) || style.equals(UDPTransport.STYLE))
            throw new IllegalArgumentException("Builtin transport");
        t.setListener(null);
        _pluggableTransports.remove(style);
        removeTransport(t);
        t.stopListening();
        _context.router().rebuildRouterInfo();
    }

    /**
     *  Hook for pluggable transport creation.
     *
     *  @since 0.9.16
     */
    DHSessionKeyBuilder.Factory getDHFactory() {
        return _dhThread;
    }
    
    private void addTransport(Transport transport) {
        if (transport == null) return;
        Transport old = _transports.put(transport.getStyle(), transport);
        if (old != null && old != transport && _log.shouldLog(Log.WARN))
            _log.warn("Replacing transport " + transport.getStyle());
        transport.setListener(this);
    }
    
    private void removeTransport(Transport transport) {
        if (transport == null) return;
        transport.setListener(null);
        Transport old = _transports.remove(transport.getStyle());
        if (old != null && _log.shouldLog(Log.WARN))
            _log.warn("Removing transport " + transport.getStyle());
    }

    private void configTransports() {
        boolean enableUDP = _context.getBooleanPropertyDefaultTrue(PROP_ENABLE_UDP);
        Transport udp = null;
        if (enableUDP) {
            udp = new UDPTransport(_context, _dhThread);
            addTransport(udp);
            initializeAddress(udp);
        }
        if (isNTCPEnabled(_context)) {
            Transport ntcp = new NTCPTransport(_context, _dhThread);
            addTransport(ntcp);
            initializeAddress(ntcp);
            if (udp != null) {
                // pass along the port SSU is probably going to use
                // so that NTCP may bind early
                int port = udp.getRequestedPort();
                if (port > 0)
                    ntcp.externalAddressReceived(SOURCE_CONFIG, (byte[]) null, port);
            }
        }
        if (_transports.isEmpty())
            _log.log(Log.CRIT, "No transports are enabled");
    }
    
    public static boolean isNTCPEnabled(RouterContext ctx) {
        return ctx.getBooleanPropertyDefaultTrue(PROP_ENABLE_NTCP);
    }
    
    /**
     *  Notify transport of ALL routable interface addresses, including IPv6.
     *  It's the transport's job to ignore what it can't handle.
     */
    private void initializeAddress(Transport t) {
        initializeAddress(Collections.singleton(t));
    }
    
    /**
     *  Notify all transports of ALL routable interface addresses, including IPv6.
     *  It's the transport's job to ignore what it can't handle.
     *  @since 0.9.34
     */
    void initializeAddress() {
        initializeAddress(_transports.values());
    }
    
    /**
     *  Notify transports of ALL routable interface addresses, including IPv6.
     *  It's the transport's job to ignore what it can't handle.
     *  @since 0.9.34
     */
    private void initializeAddress(Collection<Transport> ts) {
        if (ts.isEmpty())
            return;
        Set<String> ipset = Addresses.getAddresses(false, true);  // non-local, include IPv6
        //
        // Avoid IPv6 temporary addresses if we have a non-temporary one
        //
        boolean hasNonTempV6Address = false;
        List<InetAddress> addresses = new ArrayList<InetAddress>(4);
        List<Inet6Address> tempV6Addresses = new ArrayList<Inet6Address>(4);
        for (String ips : ipset) {
            try {
                InetAddress addr = InetAddress.getByName(ips);
                if (ips.contains(":") && (addr instanceof Inet6Address)) {
                    Inet6Address v6addr = (Inet6Address) addr;
                    // getAddresses(false, true) will not return deprecated addresses
                    //if (Addresses.isDeprecated(v6addr)) {
                    //    if (_log.shouldWarn())
                    //        _log.warn("Not binding to deprecated temporary address " + bt);
                    //    continue;
                    //}
                    if (Addresses.isTemporary(v6addr)) {
                        // Save temporary addresses
                        // we only use these if we don't have a non-temporary adress
                        tempV6Addresses.add(v6addr);
                        continue;
                    }
                    hasNonTempV6Address = true;
                }
                addresses.add(addr);
            } catch (UnknownHostException e) {
                _log.error("UDP failed to bind to local address", e);
            }
        }
        // we only use these if we don't have a non-temporary adress
        if (!tempV6Addresses.isEmpty()) {
            if (hasNonTempV6Address) {
                if (_log.shouldWarn()) {
                    for (Inet6Address addr : tempV6Addresses) {
                        _log.warn("Not binding to temporary address " + addr.getHostAddress());
                    }
                }
            } else {
                addresses.addAll(tempV6Addresses);
            }
        }
        for (Transport t : ts) {
            for (InetAddress ia : addresses) {
                byte[] ip = ia.getAddress();
                t.externalAddressReceived(SOURCE_INTERFACE, ip, 0);
            }
        }
    }

    /**
     * Initialize from interfaces, and callback from UPnP or SSU.
     * See CSFI.notifyReplaceAddress().
     * Tell all transports... but don't loop.
     *
     */
    void externalAddressReceived(Transport.AddressSource source, byte[] ip, int port) {
        for (Transport t : _transports.values()) {
            // don't loop
            if (!(source == SOURCE_SSU && t.getStyle().equals(UDPTransport.STYLE)))
                t.externalAddressReceived(source, ip, port);
        }
    }

    /**
     *  Remove all ipv4 or ipv6 addresses.
     *  See CSFI.notifyRemoveAddress().
     *  Tell all transports... but don't loop.
     *
     *  @since 0.9.20
     */
    void externalAddressRemoved(Transport.AddressSource source, boolean ipv6) {
        for (Transport t : _transports.values()) {
            // don't loop
            if (!(source == SOURCE_SSU && t.getStyle().equals(UDPTransport.STYLE)))
                t.externalAddressRemoved(source, ipv6);
        }
    }

    /**
     * callback from UPnP
     *
     */
    void forwardPortStatus(String style, byte[] ip, int port, int externalPort, boolean success, String reason) {
        Transport t = getTransport(style);
        if (t != null)
            t.forwardPortStatus(ip, port, externalPort, success, reason);
    }

    synchronized void startListening() {
        if (_dhThread.getState() == Thread.State.NEW)
            _dhThread.start();
        // For now, only start UPnP if we have no publicly-routable addresses
        // so we don't open the listener ports to the world.
        // Maybe we need a config option to force on? Probably not.
        // What firewall supports UPnP and is configured with a public address on the LAN side?
        // Unlikely.
        if (_upnpManager != null && Addresses.getAnyAddress() == null)
            _upnpManager.start();
        configTransports();
        _log.debug("Starting up the transport manager");
        // Let's do this in a predictable order to make testing easier
        // Start NTCP first so it can get notified from SSU
        List<Transport> tps = new ArrayList<Transport>();
        Transport tp = getTransport(NTCPTransport.STYLE);
        if (tp != null)
            tps.add(tp);
        tp = getTransport(UDPTransport.STYLE);
        if (tp != null)
            tps.add(tp);
        // now add any others (pluggable)
        for (Transport t : _pluggableTransports.values()) {
             tps.add(t);
        }
        for (Transport t : tps) {
            t.startListening();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Transport " + t.getStyle() + " started");
        }
        // kick UPnP - Do this to get the ports opened even before UDP registers an address
        transportAddressChanged();
        _log.debug("Done start listening on transports");
        _context.router().rebuildRouterInfo();
    }
    
    synchronized void restart() {
        stopListening();
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        startListening();
    }
    
    /**
     *  Can be restarted.
     */
    synchronized void stopListening() {
        if (_upnpManager != null)
            _upnpManager.stop();
        for (Transport t : _transports.values()) {
            t.stopListening();
        }
        _transports.clear();
    }
    
    
    /**
     *  Cannot be restarted.
     *  @since 0.9
     */
    synchronized void shutdown() {
        stopListening();
        _dhThread.shutdown();
        Addresses.clearCaches();
        TransportImpl.clearCaches();
    }
    
    Transport getTransport(String style) {
        return _transports.get(style);
    }
    
    int getTransportCount() { return _transports.size(); }
    
    /**
     *  @return SortedMap of style to Transport (a copy)
     *  @since 0.9.31
     */
    SortedMap<String, Transport> getTransports() {
        TreeMap<String, Transport> rv = new TreeMap<String, Transport>();
        rv.putAll(_transports);
        // TODO (also synch)
        //rv.putAll(_pluggableTransports);
        return rv;
    }

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last five minutes.
     */
    int countActivePeers() { 
        int peers = 0;
        for (Transport t : _transports.values()) {
            peers += t.countActivePeers();
        }
        return peers;
    }
    
    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    int countActiveSendPeers() { 
        int peers = 0;
        for (Transport t : _transports.values()) {
            peers += t.countActiveSendPeers();
        }
        return peers;
    }
    
    /**
      * Is at least one transport below its outbound connection limit + some margin
      * Use for throttling in the router.
      *
      * @param pct percent of limit 0-100
      */
    boolean haveOutboundCapacity(int pct) { 
        for (Transport t : _transports.values()) {
            if (t.haveCapacity(pct))
                return true;
        }
        return false;
    }
    
    private static final int HIGH_CAPACITY_PCT = 50;
    /**
      * Are all transports well below their outbound connection limit
      * Use for throttling in the router.
      */
    boolean haveHighOutboundCapacity() { 
        if (_transports.isEmpty())
            return false;
        for (Transport t : _transports.values()) {
            if (!t.haveCapacity(HIGH_CAPACITY_PCT))
                return false;
        }
        return true;
    }
    
    /**
      * Is at least one transport below its inbound connection limit + some margin
      * Use for throttling in the router.
      *
      * @param pct percent of limit 0-100
      */
    boolean haveInboundCapacity(int pct) { 
        for (Transport t : _transports.values()) {
            if (t.hasCurrentAddress() && t.haveCapacity(pct))
                return true;
        }
        return false;
    }
    
    /**
     * Return our peer clock skews on all transports.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * A positive number means our clock is ahead of theirs.
     * Note: this method returns them in whimsical order.
     */
    Vector<Long> getClockSkews() {
        Vector<Long> skews = new Vector<Long>();
        for (Transport t : _transports.values()) {
            Vector<Long> tempSkews = t.getClockSkews();
            if ((tempSkews == null) || (tempSkews.isEmpty())) continue;
            skews.addAll(tempSkews);
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Transport manager returning " + skews.size() + " peer clock skews.");
        return skews;
    }
    
    /**
     *  Previously returned short, now enum as of 0.9.20
     *  @return the best status of any transport
     */
    Status getReachabilityStatus() { 
        Status rv = Status.UNKNOWN;
        for (Transport t : _transports.values()) {
            Status s = t.getReachabilityStatus();
            if (s.getCode() < rv.getCode())
                rv = s;
        }
        return rv;
    }

    /**
     * @deprecated unused
     */
    @Deprecated
    void recheckReachability() { 
        for (Transport t : _transports.values())
            t.recheckReachability();
    }

    boolean isBacklogged(Hash peer) {
        for (Transport t : _transports.values()) {
            if (t.isBacklogged(peer))
                return true;
        }
        return false;
    }    
    
    boolean isEstablished(Hash peer) {
        for (Transport t : _transports.values()) {
            if (t.isEstablished(peer))
                return true;
        }
        return false;
    }    
    
    /**
     *  @return a new set, may be modified
     *  @since 0.9.34
     */
    public Set<Hash> getEstablished() {
        // for efficiency. NTCP is modifiable, SSU isn't
        Transport t = _transports.get("NTCP");
        Set<Hash> rv;
        if (t != null)
            rv = t.getEstablished();
        else
            rv = new HashSet<Hash>(256);
        t = _transports.get("SSU");
        if (t != null)
            rv.addAll(t.getEstablished());
        return rv;
    }
    
    /**
     * Tell the transports that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    void mayDisconnect(Hash peer) {
        for (Transport t : _transports.values()) {
             t.mayDisconnect(peer);
        }
    }
    
    /**
     * Was the peer UNreachable (outbound only) on any transport,
     * based on the last time we tried it for each transport?
     * This is NOT reset if the peer contacts us.
     */
    boolean wasUnreachable(Hash peer) {
        for (Transport t : _transports.values()) {
            if (!t.wasUnreachable(peer))
                return false;
        }
        return true;
    }    
    
    /**
     * IP of the peer from the last connection (in or out, any transport).
     * This may be different from that advertised in the netDb,
     * as the peer may be hidden, or connect from a different IP, or
     * change his netDb later, in an attempt to avoid restrictions.
     *
     * For blocking purposes, etc. it's worth checking both
     * the netDb addresses and this address.
     *
     * @return IPv4 or IPv6 or null
     */
    byte[] getIP(Hash peer) {
        return TransportImpl.getIP(peer);
    }    
    
    /**
     *  This forces a rebuild
     */
    List<RouterAddress> getAddresses() {
        List<RouterAddress> rv = new ArrayList<RouterAddress>(4);
        // do this first since SSU may force a NTCP change
        for (Transport t : _transports.values())
            t.updateAddress();
        for (Transport t : _transports.values()) {
            rv.addAll(t.getCurrentAddresses());
        }
        return rv;
    }
    
    /**
     *  @since IPv6
     */
    static class Port {
        public final String style;
        public final int port;

        public Port(String style, int port) {
            this.style = style;
            this.port = port;
        }

        @Override
        public int hashCode() {
            return style.hashCode() ^ port;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (! (o instanceof Port))
                return false;
            Port p = (Port) o;
            return port == p.port && style.equals(p.style);
        }
    }

    /**
     * Include the published port, or the requested port, for each transport
     * which we will pass along to UPnP
     */
    private Set<Port> getPorts() {
        Set<Port> rv = new HashSet<Port>(4);
        for (Transport t : _transports.values()) {
            int port = t.getRequestedPort();
            // Use UDP port for NTCP too - see comment in NTCPTransport.getRequestedPort() for why this is here
            if (t.getStyle().equals(NTCPTransport.STYLE) && port <= 0 &&
                _context.getBooleanProperty(NTCPTransport.PROP_I2NP_NTCP_AUTO_PORT)) {
                Transport udp = getTransport(UDPTransport.STYLE);
                if (udp != null)
                    port = t.getRequestedPort();
            }
            if (port > 0)
                rv.add(new Port(t.getStyle(), port));
        }
        return rv;
    }
    
    TransportBid getBid(OutNetMessage msg) {
        List<TransportBid> bids = getBids(msg);
        if ( (bids == null) || (bids.isEmpty()) )
            return null;
        else
            return bids.get(0);
    }

    List<TransportBid> getBids(OutNetMessage msg) {
        if (msg == null)
            throw new IllegalArgumentException("Null message?  no bidding on a null outNetMessage!");
        if (_context.router().getRouterInfo().equals(msg.getTarget()))
            throw new IllegalArgumentException("Bids for a message bound to ourselves?");

        List<TransportBid> rv = new ArrayList<TransportBid>(_transports.size());
        Set<String> failedTransports = msg.getFailedTransports();
        for (Transport t : _transports.values()) {
            if (failedTransports.contains(t.getStyle())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Skipping transport " + t.getStyle() + " as it already failed");
                continue;
            }
            // we always want to try all transports, in case there is a faster bidirectional one
            // already connected (e.g. peer only has a public PHTTP address, but they've connected
            // to us via TCP, send via TCP)
            TransportBid bid = t.bid(msg.getTarget(), msg.getMessageSize());
            if (bid != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " bid: " + bid);
                rv.add(bid);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " did not produce a bid");
            }
        }
        return rv;
    }
    
    TransportBid getNextBid(OutNetMessage msg) {
        int unreachableTransports = 0;
        Hash peer = msg.getTarget().getIdentity().calculateHash();
        Set<String> failedTransports = msg.getFailedTransports();
        TransportBid rv = null;
        for (Transport t : _transports.values()) {
            if (t.isUnreachable(peer)) {
                unreachableTransports++;
                // this keeps GetBids() from banlisting for "no common transports"
                // right after we banlisted for "unreachable on any transport" below...
                msg.transportFailed(t.getStyle());
                continue;
            }
            if (failedTransports.contains(t.getStyle())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Skipping transport " + t.getStyle() + " as it already failed");
                continue;
            }
            // we always want to try all transports, in case there is a faster bidirectional one
            // already connected (e.g. peer only has a public PHTTP address, but they've connected
            // to us via TCP, send via TCP)
            TransportBid bid = t.bid(msg.getTarget(), msg.getMessageSize());
            if (bid != null) {
                if (bid.getLatencyMs() == TransportBid.TRANSIENT_FAIL)
                    // this keeps GetBids() from banlisting for "no common transports"
                    msg.transportFailed(t.getStyle());
                else if ( (rv == null) || (rv.getLatencyMs() > bid.getLatencyMs()) )
                    rv = bid;    
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " bid: " + bid + " currently winning? " + (rv == bid) 
                               + " (winning latency: " + rv.getLatencyMs() + " / " + rv + ")");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Transport " + t.getStyle() + " did not produce a bid");
                if (t.isUnreachable(peer))
                    unreachableTransports++;
            }
        }
        if (unreachableTransports >= _transports.size()) {
            if (msg.getTarget().getIdentity().getSigningPublicKey().getType() == null) {
                // we don't support his crypto
                _context.statManager().addRateData("transport.banlistOnUnsupportedSigType", 1);
                _context.banlist().banlistRouterForever(peer, _x("Unsupported signature type"));
            } else if (unreachableTransports >= _transports.size() && countActivePeers() > 0) {
                // Don't banlist if we aren't talking to anybody, as we may have a network connection issue
                boolean incompat = false;
                RouterInfo us = _context.router().getRouterInfo();
                if (us != null) {
                    RouterIdentity id = us.getIdentity();
                    if (id.getSigType() != SigType.DSA_SHA1) {
                        String v = msg.getTarget().getVersion();
                        // NTCP is earlier than SSU, use that one
                        if (VersionComparator.comp(v, NTCPTransport.MIN_SIGTYPE_VERSION) < 0)
                            incompat = true;
                    }
                }
                if (incompat) {
                    // they don't support our crypto
                    _context.statManager().addRateData("transport.banlistOnUnsupportedSigType", 1);
                    _context.banlist().banlistRouter(peer, _x("No support for our signature type"), null, null,
                                                     _context.clock().now() + SIGTYPE_BANLIST_DURATION);
                } else {
                    _context.statManager().addRateData("transport.banlistOnUnreachable", msg.getLifetime(), msg.getLifetime());
                    _context.banlist().banlistRouter(peer, _x("Unreachable on any transport"));
                }
            }
        } else if (rv == null) {
            _context.statManager().addRateData("transport.noBidsYetNotAllUnreachable", unreachableTransports, msg.getLifetime());
        }
        return rv;
    }
    
    /**
     * Message received
     *
     * @param message non-null
     * @param fromRouter may be null
     * @param fromRouterHash may be null, calculated from fromRouter if null
     */
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("I2NPMessage received: " + message.getClass().getSimpleName() /*, new Exception("Where did I come from again?") */ );
        try {
            _context.inNetMessagePool().add(message, fromRouter, fromRouterHash);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Added to in pool");
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving message", iae);
        }
    }

    /**
     *  TransportEventListener
     *  calls UPnPManager rescan() and update()
     */
    public void transportAddressChanged() {
        if (_upnpManager != null) {
            _upnpManager.rescan();
            // should really delay the following by 5 seconds?
            _upnpManager.update(getPorts());
        }
    }

    List<String> getMostRecentErrorMessages() { 
        List<String> rv = new ArrayList<String>(16);
        for (Transport t : _transports.values()) {
            rv.addAll(t.getMostRecentErrorMessages());
        }
        return rv;
    }
    
    /**
     *  As of 0.9.31, only outputs UPnP status
     *
     *  Warning - blocking, very slow, queries the active UPnP router,
     *  will take many seconds if it has vanished.
     */
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        if (SystemVersion.isAndroid()) {
            // newer androids crash w/ network on IO thread
        } else if (_upnpManager != null) {
            out.write(_upnpManager.renderStatusHTML());
        } else {
            out.write("<h3 id=\"upnpstatus\"><a name=\"upnp\"></a>" + _t("UPnP is not enabled") + "</h3>\n");
        }
    }
    
    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  Translate
     */
    private final String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }
}
