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
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.util.Addresses;
import net.i2p.util.AddressType;
import net.i2p.util.ArraySet;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

public class CommSystemFacadeImpl extends CommSystemFacade {
    private final Log _log;
    private final RouterContext _context;
    private final TransportManager _manager;
    private final GeoIP _geoIP;
    private final Map<String, Object> _exemptIncoming;
    private volatile boolean _netMonitorStatus;
    private boolean _wasStarted;

    /**
     *  Disable connections for testing
     *  @since IPv6
     */
    private static final String PROP_DISABLED = "i2np.disable";

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";
    private static final Object DUMMY = Integer.valueOf(0);
    
    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        //_context.statManager().createRateStat("transport.getBidsJobTime", "How long does it take?", "Transport", new long[] { 10*60*1000l });
        _netMonitorStatus = true;
        _geoIP = new GeoIP(_context);
        _manager = new TransportManager(_context);
        _exemptIncoming = new LHMCache<String, Object>(128);
    }
    
    public synchronized void startup() {
        _log.info("Starting up the comm system");
        _manager.startListening();
        startTimestamper();
        startNetMonitor();
        _wasStarted = true;
    }
    
    /**
     *  Cannot be restarted after calling this. Use restart() for that.
     */
    public synchronized void shutdown() {
        _manager.shutdown();
        _geoIP.shutdown();
    }
    
    public synchronized void restart() {
        if (!_wasStarted) {
            startup();
        } else {
            _wasStarted = false;
            _manager.restart();
            _wasStarted = true;
        }
    }

    /**
     * @since 0.9.53
     */
    @Override
    public synchronized boolean isRunning() { return _wasStarted; }
    
    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last five minutes.
     */
    @Override
    public int countActivePeers() { return _manager.countActivePeers(); }

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    @Override
    public int countActiveSendPeers() { return _manager.countActiveSendPeers(); } 

    @Override
    public boolean haveInboundCapacity(int pct) { return _manager.haveInboundCapacity(pct); } 
    @Override
    public boolean haveOutboundCapacity(int pct) { return _manager.haveOutboundCapacity(pct); } 
    @Override
    public boolean haveHighOutboundCapacity() { return _manager.haveHighOutboundCapacity(); } 
    
    /**
     * @param percentToInclude 1-100
     * @return Framed average clock skew of connected peers in milliseconds, or the clock offset if we cannot answer.
     * Average is calculated over the middle "percentToInclude" peers.
     *
     * A positive number means our clock is ahead of theirs.
     *
     * Todo: change List to milliseconds
     */
    @Override
    public long getFramedAveragePeerClockSkew(int percentToInclude) {
        List<Long> skews = _manager.getClockSkews();
        if (skews == null ||
            skews.isEmpty() ||
            (skews.size() < 5 && _context.clock().getUpdatedSuccessfully())) {
            return _context.clock().getOffset();
        }

        // Going to calculate, sort them
        Collections.sort(skews);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Clock skews: " + skews);
        // Calculate frame size
        int frameSize = Math.max((skews.size() * percentToInclude / 100), 1);
        int first = (skews.size() / 2) - (frameSize / 2);
        int last = Math.min((skews.size() / 2) + (frameSize / 2), skews.size() - 1);
        // Sum skew values
        long sum = 0;
        for (int i = first; i <= last; i++) {
            long value = skews.get(i).longValue();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Adding clock skew " + i + " valued " + value + " s.");
            sum = sum + value;
        }
        // Calculate average
        return sum * 1000 / frameSize;
    }
    
    /** Send the message out */
    public void processMessage(OutNetMessage msg) {	
        if (isDummy()) {
            // testing
            GetBidsJob.fail(_context, msg);
            return;
        }
        //GetBidsJob j = new GetBidsJob(_context, this, msg);
        //j.runJob();
        //long before = _context.clock().now();
        GetBidsJob.getBids(_context, _manager, msg);
        // < 0.4 ms
        //_context.statManager().addRateData("transport.getBidsJobTime", _context.clock().now() - before);
    }
    
    @Override
    public boolean isBacklogged(Hash peer) { 
        return _manager.isBacklogged(peer); 
    }
    
    @Override
    public boolean isEstablished(Hash peer) { 
        return _manager.isEstablished(peer); 
    }

    /**
     *  @return a new list, may be modified
     *  @since 0.9.34
     */    
    public List<Hash> getEstablished() {
        return _manager.getEstablished();
    }

    @Override
    public boolean wasUnreachable(Hash peer) { 
        return _manager.wasUnreachable(peer); 
    }
    
    @Override
    public byte[] getIP(Hash peer) { 
        return _manager.getIP(peer); 
    }
    
    /**
     * Tell the comm system that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(Hash peer) {
        _manager.mayDisconnect(peer); 
    }
    
    /**
     * Tell the comm system to disconnect from this peer.
     *
     * @since 0.9.38
     */
    @Override
    public void forceDisconnect(Hash peer) {
        _manager.forceDisconnect(peer); 
    }
    
    @Override
    public List<String> getMostRecentErrorMessages() { 
        return _manager.getMostRecentErrorMessages(); 
    }

    /**
     *  @since 0.9.20
     */
    @Override
    public Status getStatus() { 
        if (!_netMonitorStatus)
            return Status.DISCONNECTED;
        Status rv = _manager.getReachabilityStatus(); 
        if (rv != Status.HOSED && _context.router().isHidden())
            return Status.OK;
        return rv; 
    }

    /**
     * getStatus().toStatusString(), translated if available.
     * @since 0.9.45
     */
    @Override
    public String getLocalizedStatusString() {
        return Translate.getString(getStatus().toStatusString(), _context, ROUTER_BUNDLE_NAME);
    }

    /**
     * @deprecated unused
     */
    @Override
    @Deprecated
    public void recheckReachability() { _manager.recheckReachability(); }

    /**
     *  As of 0.9.31, only outputs UPnP status
     *
     *  Warning - blocking, very slow, queries the active UPnP router,
     *  will take many seconds if it has vanished.
     */
    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { 
        _manager.renderStatusHTML(out, urlBase, sortFlags); 
    }
    
    /**
     *  @return SortedMap of style to Transport (a copy)
     *  @since 0.9.31
     */
    public SortedMap<String, Transport> getTransports() {
        return _manager.getTransports();
    }
    
    /** @return non-null, possibly empty */
    @Override
    public List<RouterAddress> createAddresses() {
        // No, don't do this, it makes it almost impossible to build inbound tunnels
        //if (_context.router().isHidden())
        //    return Collections.EMPTY_SET;
        List<RouterAddress> addresses = new ArrayList<RouterAddress>(_manager.getAddresses());
        if (addresses.size() > 1)
            Collections.sort(addresses, new AddrComparator());
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating addresses: " + addresses, new Exception("creator"));
        return addresses;
    }

    /**
     *  Arbitrary sort for consistency.
     *  Note that the console UI has its own sorter.
     *  @since 0.9.50
     */
    private static class AddrComparator implements Comparator<RouterAddress>, Serializable {
        public int compare(RouterAddress l, RouterAddress r) {
            int rv = l.getCost() - r.getCost();
            if (rv != 0)
                return rv;
            int lh = l.hashCode();
            int rh = l.hashCode();
            if (lh > rh)
                return 1;
            if (lh < rh)
                return -1;
            return 0;
        }
    }
    
    /**
     * UDP changed addresses, tell NTCP and restart
     *
     * All the work moved to NTCPTransport.externalAddressReceived()
     *
     * @param udpAddr may be null; or udpAddr's host/IP may be null
     */
    @Override
    public void notifyReplaceAddress(RouterAddress udpAddr) {
        byte[] ip = null;
        int port = 0;
        // Don't pass IP along if address has introducers
        // Right now we publish the direct UDP address, even if publishing introducers,
        // we probably shouldn't, see UDPTransport rebuildExternalAddress() TODO
        if (udpAddr != null && udpAddr.getOption("itag0") == null) {
            ip = udpAddr.getIP();
            port = udpAddr.getPort();
        }
        if (port < 0) {
            Transport udp = _manager.getTransport(UDPTransport.STYLE);
            if (udp != null)
                port = udp.getRequestedPort();
        }
        if (ip != null || port > 0)
            _manager.externalAddressReceived(Transport.AddressSource.SOURCE_SSU, ip, port);
        else
            notifyRemoveAddress(udpAddr);
    }

    /** 
     *  Tell other transports our address changed
     *
     *  @param address may be null; or address's host/IP may be null
     *  @since 0.9.20
     */
    @Override
    public void notifyRemoveAddress(RouterAddress address) {
        // just keep this simple for now, multiple v4 or v6 addresses not yet supported
        notifyRemoveAddress(address != null && TransportUtil.isIPv6(address));
    }

    /** 
     *  Tell other transports our address changed
     *
     *  @since 0.9.20
     */
    @Override
    public void notifyRemoveAddress(boolean ipv6) {
        _manager.externalAddressRemoved(Transport.AddressSource.SOURCE_SSU, ipv6);
    }

    /**
     *  Exempt this router hash from any incoming throttles or rejections
     *
     *  @since 0.9.58
     */
    @Override
    public void exemptIncoming(Hash peer) {
        if (_manager.isEstablished(peer))
            return;
        RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (ri == null)
            return;
        Collection<RouterAddress> addrs = ri.getAddresses();
        ArraySet<String> ips = new ArraySet<String>(addrs.size());
        for (RouterAddress addr : addrs) {
            String ip = addr.getHost();
            if (ip == null)
                continue;
            // Add IPv6 even if we don't have an address, not worth the check
            ips.add(Addresses.toCanonicalString(ip));
        }
        int sz = ips.size();
        if (sz > 0) {
            synchronized(_exemptIncoming) {
                for (int i = 0; i < sz; i++) {
                    _exemptIncoming.put(ips.get(i), DUMMY);
                }
            }
        }
    }

    /**
     *  Is this IP exempt from any incoming throttles or rejections
     *
     *  @param ip canonical string
     *  @since 0.9.58
     */
    @Override
    public boolean isExemptIncoming(String ip) {
        synchronized(_exemptIncoming) {
            return _exemptIncoming.containsKey(ip);
        }
    }

    /**
     *  Remove this IP from the exemptions
     *
     *  @param ip canonical string
     *  @since 0.9.58
     */
    public void removeExemption(String ip) {
        synchronized(_exemptIncoming) {
            _exemptIncoming.remove(ip);
        }
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  Do not call from transport constructor. Transport must be ready to be started.
     *
     *  Following transport methods will be called:
     *    setListener()
     *    externalAddressReceived() (zero or more times, one for each known address)
     *    startListening();
     *
     *  @since 0.9.16
     */
    @Override
    public void registerTransport(Transport t) {
        _manager.registerAndStart(t);
    }

    /**
     *  Pluggable transports. Not for NTCP or SSU.
     *
     *  Following transport methods will be called:
     *    setListener(null)
     *    stoptListening();
     *
     *  @since 0.9.16
     */
    @Override
    public void unregisterTransport(Transport t) {
        _manager.stopAndUnregister(t);
    }

    /**
     *  Factory for making X25519 key pairs.
     *  @since 0.9.46
     */
    @Override
    public X25519KeyFactory getXDHFactory() {
        return _manager.getXDHFactory();
    }
    
    /*
     * GeoIP stuff
     *
     * This is only used in the router console for now, but we put it here because
     * 1) it's a lot easier, and 2) we could use it in the future for peer selection,
     * tunnel selection, banlisting, etc.
     */

    /**
     *  Router must call after netdb is initialized
     *  @since 0.9.41
     */
    @Override
    public void initGeoIP() {
        startGeoIP();
    }

    /* We hope the routerinfos are read in and things have settled down by now, but it's not required to be so */
    private static final int START_DELAY = SystemVersion.isSlow() ? 5*60*1000 : 5*1000;
    private static final int LOOKUP_TIME = 30*60*1000;

    private void startGeoIP() {
        _context.simpleTimer2().addEvent(new QueueAll(), START_DELAY);
    }

    /**
     * Collect the IPs for all routers in the DB, and queue them for lookup,
     * then fire off the periodic lookup task for the first time.
     *
     *  As of 0.9.32, works only for literal IPs, ignores hostnames.
     */
    private class QueueAll implements SimpleTimer.TimedEvent {
        public void timeReached() {
            for (Hash h : _context.netDb().getAllRouters()) {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
                if (ri == null)
                    continue;
                byte[] ip = getIP(ri);
                if (ip == null)
                    continue;
                _geoIP.add(ip);
            }
            _context.simpleTimer2().addPeriodicEvent(new Lookup(), 5000, LOOKUP_TIME);
        }
    }

    private class Lookup implements SimpleTimer.TimedEvent {
        public void timeReached() {
            (new LookupThread()).start();
        }
    }

    /**
     *  This takes too long to run on the SimpleTimer2 queue
     *  @since 0.9.10
     */
    private class LookupThread extends I2PThread {

        public LookupThread() {
            super("GeoIP Lookup");
            setDaemon(true);
        }

        public void run() {
            long start = System.currentTimeMillis();
            _geoIP.blockingLookup();
            if (_log.shouldLog(Log.INFO))
                _log.info("GeoIP lookup took " + (System.currentTimeMillis() - start));
        }
    }

    /**
     *  @param ip ipv4 or ipv6
     */
    @Override
    public void queueLookup(byte[] ip) {
        _geoIP.add(ip);
    }

    /**
     *  @return two-letter lower-case country code or null
     *  @since 0.8.11
     */
    @Override
    public String getOurCountry() {
        return _context.getProperty(GeoIP.PROP_IP_COUNTRY);
    }

    /**
     *  Are we in a strict country
     *  @since 0.8.13
     */
    @Override
    public boolean isInStrictCountry() {
        String us = getOurCountry();
        return (us != null && StrictCountries.contains(us)) || _context.getBooleanProperty("router.forceStrictCountry");
    }

    /**
     *  Are they in a strict country.
     *  Not recommended for our local router hash, as we may not be either in the cache or netdb,
     *  or may not be publishing an IP.
     *
     *  @param peer non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(Hash peer) {
        String c = getCountry(peer);
        return c != null && StrictCountries.contains(c);
    }

    /**
     *  Are they in a strict country
     *  @param ri non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInStrictCountry(RouterInfo ri) {
        byte[] ip = getIP(ri);
        if (ip == null)
            return false;
        String c = _geoIP.get(ip);
        return c != null && StrictCountries.contains(c);
    }

    /**
     *  Uses the transport IP first because that lookup is fast,
     *  then the IP from the netDb.
     *  Not recommended for our local router hash, as we may not be either in the cache or netdb,
     *  or may not be publishing an IP.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for hostnames.
     *
     *  @param peer not ourselves - use getOurCountry() for that
     *  @return two-letter lower-case country code or null
     */
    @Override
    public String getCountry(Hash peer) {
        byte[] ip = TransportImpl.getIP(peer);
        //if (ip != null && ip.length == 4)
        if (ip != null)
            return _geoIP.get(ip);
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return null;
        ip = getValidIP(ri);
        if (ip != null)
            return _geoIP.get(ip);
        return null;
    }

    /**
     *  Return first IP (v4 or v6) we find, any transport.
     *  Not validated, may be local, etc.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for hostnames.
     *
     *  @return IP or null
     */
    private static byte[] getIP(RouterInfo ri) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null)
                return rv;
        }
        return null;
    }

    /**
     *  Return first valid IP (v4 or v6) we find, any transport.
     *  Local and other invalid IPs will not be returned.
     *
     *  As of 0.9.32, works only for literal IPs, returns null for hostnames.
     *
     *  @return IP or null
     *  @since 0.9.18
     */
    private static byte[] getValidIP(RouterInfo ri) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null && TransportUtil.isPubliclyRoutable(rv, true))
                return rv;
        }
        return null;
    }

    /** full name for a country code, or the code if we don't know the name */
    @Override
    public String getCountryName(String c) {
        if (_geoIP == null)
            return c;
        String n = _geoIP.fullName(c);
        if (n == null)
            return c;
        return n;
    }

    /**
     * Get the country code map
     *
     * @return Map of two-letter lower case code to untranslated country name, unmodifiable
     * @since 0.9.53
     */
    public Map<String, String> getCountries() {
        if (_geoIP == null)
            return Collections.emptyMap();
        return _geoIP.getCountries();
    }

    /** Provide a consistent "look" for displaying router IDs in the console */
    @Override
    public String renderPeerHTML(Hash peer) {
        String h = peer.toBase64();
        StringBuilder buf = new StringBuilder(128);
        String c = getCountry(peer);
        if (c != null) {
            String countryName = getCountryName(c);
            if (countryName.length() > 2)
                countryName = Translate.getString(countryName, _context, COUNTRY_BUNDLE_NAME);
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(c.toUpperCase(Locale.US)).append("\" title=\"");
            buf.append(countryName);
            buf.append("\" src=\"/flags.jsp?c=").append(c).append("\"> ");
        } else {
            buf.append("<img class=\"unknownflag\" height=\"11\" width=\"16\" alt=\"??\" src=\"/flags.jsp?c=a0\" title=\"").append(_t("unknown")).append("\"> ");
        }
        buf.append("<tt><a title=\"").append(_t("NetDb entry")).append("\" href=\"netdb?r=").append(h).append("\">");
        buf.append(h, 0, 4);
        buf.append("</a></tt>");
        return buf.toString();
    }

    /**
     *  Is everything disabled for testing?
     *  @since 0.8.13
     */
    @Override
    public boolean isDummy() {
        return _context.getBooleanProperty(PROP_DISABLED);
    }

    /**
     *  Translate
     */
    private final String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

    /*
     * Timestamper stuff
     *
     * This is used as a backup to NTP over UDP.
     * @since 0.7.12
     */

    private static final int TIME_START_DELAY = 5*60*1000;
    private static final int TIME_REPEAT_DELAY = 10*60*1000;

    /** @since 0.7.12 */
    private void startTimestamper() {
        _context.simpleTimer2().addPeriodicEvent(new Timestamper(), TIME_START_DELAY,  TIME_REPEAT_DELAY);
    }

    /**
     * Update the clock offset based on the average of the peers.
     * This uses the default stratum which is lower than any reasonable
     * NTP source, so it will be ignored unless NTP is broken.
     * @since 0.7.12
     */
    private class Timestamper implements SimpleTimer.TimedEvent {
        public void timeReached() {
             // use the same % as in RouterClock so that check will never fail
             // This is their our offset w.r.t. them...
             long peerOffset = getFramedAveragePeerClockSkew(10);
             if (peerOffset == 0)
                 return;
             long currentOffset = _context.clock().getOffset();
             // ... so we subtract it to get in sync with them
             long newOffset = currentOffset - peerOffset;
             _context.clock().setOffset(newOffset);
        }
    }

    /** @since 0.9.4 */
    private void startNetMonitor() {
        new NetMonitor();
    }

    /**
     * Simple check to see if we have a network connection
     * @since 0.9.4
     */
    private class NetMonitor extends SimpleTimer2.TimedEvent {
        private static final long SHORT_DELAY = 15*1000;
        private static final long LONG_DELAY = 3*60*1000;

        public NetMonitor() {
            super(_context.simpleTimer2(), 0);
        }

        public void timeReached() {
            Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
            boolean good = addrs.contains(AddressType.IPV4) || addrs.contains(AddressType.IPV6);
             if (_netMonitorStatus != good) {
                 if (good)
                     _log.logAlways(Log.INFO, "Network reconnected");
                 else
                     _log.error("Network disconnected");
                 _context.router().eventLog().addEvent(EventLog.NETWORK, good ? "connected" : "disconnected");
                 _netMonitorStatus = good;
                 if (good) {
                     // Check local addresses
                     _manager.initializeAddress();
                     // fire UPnP
                     _manager.transportAddressChanged();
                 }
             }
             reschedule(good ? LONG_DELAY : SHORT_DELAY);
        }
    }
}
