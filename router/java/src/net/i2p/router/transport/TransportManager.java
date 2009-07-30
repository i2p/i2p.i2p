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
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.util.Log;

public class TransportManager implements TransportEventListener {
    private Log _log;
    private List<Transport> _transports;
    private RouterContext _context;
    private UPnPManager _upnpManager;

    public final static String PROP_ENABLE_UDP = "i2np.udp.enable";
    public final static String PROP_ENABLE_NTCP = "i2np.ntcp.enable";
    public final static String DEFAULT_ENABLE_NTCP = "true";
    public final static String DEFAULT_ENABLE_UDP = "true";
    /** default true */
    public final static String PROP_ENABLE_UPNP = "i2np.upnp.enable";
    
    public TransportManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportManager.class);
        _context.statManager().createRateStat("transport.shitlistOnUnreachable", "Add a peer to the shitlist since none of the transports can reach them", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.noBidsYetNotAllUnreachable", "Add a peer to the shitlist since none of the transports can reach them", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailShitlisted", "Could not attempt to bid on message, as they were shitlisted", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailSelf", "Could not attempt to bid on message, as it targeted ourselves", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailNoTransports", "Could not attempt to bid on message, as none of the transports could attempt it", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("transport.bidFailAllTransports", "Could not attempt to bid on message, as all of the transports had failed", "Transport", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _transports = new ArrayList();
        if (Boolean.valueOf(_context.getProperty(PROP_ENABLE_UPNP, "true")).booleanValue())
            _upnpManager = new UPnPManager(context, this);
    }
    
    public void addTransport(Transport transport) {
        if (transport == null) return;
        _transports.add(transport);
        transport.setListener(this);
    }
    
    public void removeTransport(Transport transport) {
        if (transport == null) return;
        _transports.remove(transport);
        transport.setListener(null);
    }

    private void configTransports() {
        String enableUDP = _context.router().getConfigSetting(PROP_ENABLE_UDP);
        if (enableUDP == null)
            enableUDP = DEFAULT_ENABLE_UDP;
        if ("true".equalsIgnoreCase(enableUDP)) {
            UDPTransport udp = new UDPTransport(_context);
            addTransport(udp);
            initializeAddress(udp);
        }
        if (enableNTCP(_context))
            addTransport(new NTCPTransport(_context));
        if (_transports.size() <= 0)
            _log.log(Log.CRIT, "No transports are enabled");
    }
    
    public static boolean enableNTCP(RouterContext ctx) {
        String enableNTCP = ctx.router().getConfigSetting(PROP_ENABLE_NTCP);
        if (enableNTCP == null)
            enableNTCP = DEFAULT_ENABLE_NTCP;
        return "true".equalsIgnoreCase(enableNTCP);
    }
    
    private static void initializeAddress(Transport t) {
        String ips = Addresses.getAnyAddress();
        if (ips == null)
            return;
        InetAddress ia = null;
        try {
            ia = InetAddress.getByName(ips);
        } catch (UnknownHostException e) {return;}
        if (ia == null)
            return;
        byte[] ip = ia.getAddress();
        t.externalAddressReceived(Transport.SOURCE_INTERFACE, ip, 0);
    }

    /**
     * callback from UPnP
     * Only tell SSU, it will tell NTCP
     *
     */
    public void externalAddressReceived(String source, byte[] ip, int port) {
        Transport t = getTransport(UDPTransport.STYLE);
        if (t != null)
            t.externalAddressReceived(source, ip, port);
    }

    /**
     * callback from UPnP
     *
     */
    public void forwardPortStatus(String style, int port, boolean success, String reason) {
        Transport t = getTransport(style);
        if (t != null)
            t.forwardPortStatus(port, success, reason);
    }

    public void startListening() {
        // For now, only start UPnP if we have no publicly-routable addresses
        // so we don't open the listener ports to the world.
        // Maybe we need a config option to force on? Probably not.
        // What firewall supports UPnP and is configured with a public address on the LAN side?
        // Unlikely.
        if (_upnpManager != null && Addresses.getAnyAddress() == null)
            _upnpManager.start();
        configTransports();
        _log.debug("Starting up the transport manager");
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            RouterAddress addr = t.startListening();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Transport " + i + " (" + t.getStyle() + ") started");
        }
        // kick UPnP - Do this to get the ports opened even before UDP registers an address
        transportAddressChanged();
        _log.debug("Done start listening on transports");
        _context.router().rebuildRouterInfo();
    }
    
    public void restart() {
        stopListening();
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        startListening();
    }
    
    public void stopListening() {
        if (_upnpManager != null)
            _upnpManager.stop();
        for (int i = 0; i < _transports.size(); i++) {
            ((Transport)_transports.get(i)).stopListening();
        }
        _transports.clear();
    }
    
    public Transport getTransport(String style) {
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if(style.equals(t.getStyle()))
                return t;
        }
        return null;
    }
    
    int getTransportCount() { return _transports.size(); }
    
    private boolean isSupported(Set addresses, Transport t) {
        for (Iterator iter = addresses.iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            if (addr.getTransportStyle().equals(t.getStyle()))
                return true;
        }
        return false;
    }
    
    public int countActivePeers() { 
        int peers = 0;
        for (int i = 0; i < _transports.size(); i++) {
            peers += ((Transport)_transports.get(i)).countActivePeers();
        }
        return peers;
    }
    
    public int countActiveSendPeers() { 
        int peers = 0;
        for (int i = 0; i < _transports.size(); i++) {
            peers += ((Transport)_transports.get(i)).countActiveSendPeers();
        }
        return peers;
    }
    
    /**
      * Is at least one transport below its outbound connection limit + some margin
      * Use for throttling in the router.
      *
      * @param pct percent of limit 0-100
      */
    public boolean haveOutboundCapacity(int pct) { 
        for (int i = 0; i < _transports.size(); i++) {
            if (((Transport)_transports.get(i)).haveCapacity(pct))
                return true;
        }
        return false;
    }
    
    private static final int HIGH_CAPACITY_PCT = 50;
    /**
      * Are all transports well below their outbound connection limit
      * Use for throttling in the router.
      */
    public boolean haveHighOutboundCapacity() { 
        if (_transports.size() <= 0)
            return false;
        for (int i = 0; i < _transports.size(); i++) {
            if (!((Transport)_transports.get(i)).haveCapacity(HIGH_CAPACITY_PCT))
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
    public boolean haveInboundCapacity(int pct) { 
        for (int i = 0; i < _transports.size(); i++) {
            if (_transports.get(i).getCurrentAddress() != null && _transports.get(i).haveCapacity(pct))
                return true;
        }
        return false;
    }
    
    /**
     * Return our peer clock skews on all transports.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * Note: this method returns them in whimsical order.
     */
    public Vector getClockSkews() {
        Vector skews = new Vector();
        for (int i = 0; i < _transports.size(); i++) {
            Vector tempSkews = ((Transport)_transports.get(i)).getClockSkews();
            if ((tempSkews == null) || (tempSkews.size() <= 0)) continue;
            skews.addAll(tempSkews);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Transport manager returning " + skews.size() + " peer clock skews.");
        return skews;
    }
    
    /** @return the best status of any transport */
    public short getReachabilityStatus() { 
        short rv = CommSystemFacade.STATUS_UNKNOWN;
        for (Transport t : _transports) {
            short s = t.getReachabilityStatus();
            if (s < rv)
                rv = s;
        }
        return rv;
    }

    public void recheckReachability() { 
        for (int i = 0; i < _transports.size(); i++)
            ((Transport)_transports.get(i)).recheckReachability();
    }

    public boolean isBacklogged(Hash dest) {
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.isBacklogged(dest))
                return true;
        }
        return false;
    }    
    
    public boolean isEstablished(Hash dest) {
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.isEstablished(dest))
                return true;
        }
        return false;
    }    
    
    /**
     * Was the peer UNreachable (outbound only) on any transport,
     * based on the last time we tried it for each transport?
     * This is NOT reset if the peer contacts us.
     */
    public boolean wasUnreachable(Hash dest) {
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (!t.wasUnreachable(dest))
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
     */
    public byte[] getIP(Hash dest) {
        return TransportImpl.getIP(dest);
    }    
    
    public Map<String, RouterAddress> getAddresses() {
        Map<String, RouterAddress> rv = new HashMap(_transports.size());
        for (Transport t : _transports) {
            if (t.getCurrentAddress() != null)
                rv.put(t.getStyle(), t.getCurrentAddress());
        }
        return rv;
    }
    
    /**
     * Include the published port, or the requested port, for each transport
     * which we will pass along to UPnP
     */
    private Map<String, Integer> getPorts() {
        Map<String, Integer> rv = new HashMap(_transports.size());
        for (Transport t : _transports) {
            int port = t.getRequestedPort();
            if (t.getCurrentAddress() != null) {
                Properties opts = t.getCurrentAddress().getOptions();
                if (opts != null) {
                    String s = opts.getProperty("port");
                    if (s != null) {
                        try {
                            port = Integer.parseInt(s);
                        } catch (NumberFormatException nfe) {}
                    }
                }
            }
            // Use UDP port for NTCP too - see comment in NTCPTransport.getRequestedPort() for why this is here
            if (t.getStyle().equals(NTCPTransport.STYLE) && port <= 0 &&
                Boolean.valueOf(_context.getProperty(CommSystemFacadeImpl.PROP_I2NP_NTCP_AUTO_PORT)).booleanValue()) {
                Transport udp = getTransport(UDPTransport.STYLE);
                if (udp != null)
                    port = t.getRequestedPort();
            }
            if (port > 0)
                rv.put(t.getStyle(), Integer.valueOf(port));
        }
        return rv;
    }
    
    public TransportBid getBid(OutNetMessage msg) {
        List bids = getBids(msg);
        if ( (bids == null) || (bids.size() <= 0) )
            return null;
        else
            return (TransportBid)bids.get(0);
    }
    public List getBids(OutNetMessage msg) {
        if (msg == null)
            throw new IllegalArgumentException("Null message?  no bidding on a null outNetMessage!");
        if (_context.router().getRouterInfo().equals(msg.getTarget()))
            throw new IllegalArgumentException("WTF, bids for a message bound to ourselves?");

        List rv = new ArrayList(_transports.size());
        Set failedTransports = msg.getFailedTransports();
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
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
    
    public TransportBid getNextBid(OutNetMessage msg) {
        int unreachableTransports = 0;
        Hash peer = msg.getTarget().getIdentity().calculateHash();
        Set failedTransports = msg.getFailedTransports();
        TransportBid rv = null;
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.isUnreachable(peer)) {
                unreachableTransports++;
                // this keeps GetBids() from shitlisting for "no common transports"
                // right after we shitlisted for "unreachable on any transport" below...
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
                if (bid.getLatencyMs() == bid.TRANSIENT_FAIL)
                    // this keeps GetBids() from shitlisting for "no common transports"
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
            // Don't shitlist if we aren't talking to anybody, as we may have a network connection issue
            if (unreachableTransports >= _transports.size() && countActivePeers() > 0) {
                _context.statManager().addRateData("transport.shitlistOnUnreachable", msg.getLifetime(), msg.getLifetime());
                _context.shitlist().shitlistRouter(peer, "Unreachable on any transport");
            }
        } else if (rv == null) {
            _context.statManager().addRateData("transport.noBidsYetNotAllUnreachable", unreachableTransports, msg.getLifetime());
        }
        return rv;
    }
    
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("I2NPMessage received: " + message.getClass().getName(), new Exception("Where did I come from again?"));
        try {
            int num = _context.inNetMessagePool().add(message, fromRouter, fromRouterHash);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added to in pool: "+ num);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving message", iae);
        }
    }
    
    public void transportAddressChanged() {
        if (_upnpManager != null)
            _upnpManager.update(getPorts());
    }

    public List getMostRecentErrorMessages() { 
        List rv = new ArrayList(16);
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            rv.addAll(t.getMostRecentErrorMessages());
        }
        return rv;
    }
    
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        TreeMap transports = new TreeMap();
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            transports.put(t.getStyle(), t);
        }
        for (Iterator iter = transports.values().iterator(); iter.hasNext(); ) {
            Transport t= (Transport)iter.next();
            t.renderStatusHTML(out, urlBase, sortFlags);
        }
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h3>Router Transport Addresses:</h3><pre>\n");
        for (int i = 0; i < _transports.size(); i++) {
            Transport t = (Transport)_transports.get(i);
            if (t.getCurrentAddress() != null)
                buf.append(t.getCurrentAddress()).append("\n\n");
            else
                buf.append(t.getStyle()).append(" is used for outbound connections only\n\n");
        }
        buf.append("</pre>\n");
        out.write(buf.toString());
        if (_upnpManager != null)
            out.write(_upnpManager.renderStatusHTML());
        buf.append("</p>\n");
        out.flush();
    }
}
