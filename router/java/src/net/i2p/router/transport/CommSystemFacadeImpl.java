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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Vector;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.util.Addresses;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Translate;

public class CommSystemFacadeImpl extends CommSystemFacade {
    private final Log _log;
    private final RouterContext _context;
    private final TransportManager _manager;
    private final GeoIP _geoIP;
    private volatile boolean _netMonitorStatus;
    private boolean _wasStarted;

    /**
     *  Disable connections for testing
     *  @since IPv6
     */
    private static final String PROP_DISABLED = "i2np.disable";
    
    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        //_context.statManager().createRateStat("transport.getBidsJobTime", "How long does it take?", "Transport", new long[] { 10*60*1000l });
        _netMonitorStatus = true;
        _geoIP = new GeoIP(_context);
        _manager = new TransportManager(_context);
        startGeoIP();
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
        if (!_wasStarted)
            startup();
        else
            _manager.restart();
    }
    
    @Override
    public int countActivePeers() { return _manager.countActivePeers(); }
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
     * Todo: change Vectors to milliseconds
     */
    @Override
    public long getFramedAveragePeerClockSkew(int percentToInclude) {
        Vector<Long> skews = _manager.getClockSkews();
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
    public boolean isBacklogged(Hash dest) { 
        return _manager.isBacklogged(dest); 
    }
    
    @Override
    public boolean isEstablished(Hash dest) { 
        return _manager.isEstablished(dest); 
    }
    
    @Override
    public boolean wasUnreachable(Hash dest) { 
        return _manager.wasUnreachable(dest); 
    }
    
    @Override
    public byte[] getIP(Hash dest) { 
        return _manager.getIP(dest); 
    }
    
    @Override
    public List<String> getMostRecentErrorMessages() { 
        return _manager.getMostRecentErrorMessages(); 
    }

    @Override
    public short getReachabilityStatus() { 
        if (!_netMonitorStatus)
            return STATUS_DISCONNECTED;
        short rv = _manager.getReachabilityStatus(); 
        if (rv != STATUS_HOSED && _context.router().isHidden())
            return STATUS_OK;
        return rv; 
    }

    /**
     * @deprecated unused
     */
    @Override
    public void recheckReachability() { _manager.recheckReachability(); }

    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { 
        _manager.renderStatusHTML(out, urlBase, sortFlags); 
    }
    
    /** @return non-null, possibly empty */
    @Override
    public List<RouterAddress> createAddresses() {
        // No, don't do this, it makes it almost impossible to build inbound tunnels
        //if (_context.router().isHidden())
        //    return Collections.EMPTY_SET;
        List<RouterAddress> addresses = new ArrayList<RouterAddress>(_manager.getAddresses());
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating addresses: " + addresses, new Exception("creator"));
        return addresses;
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
        if (udpAddr != null && udpAddr.getOption("ihost0") == null) {
            ip = udpAddr.getIP();
            port = udpAddr.getPort();
        }
        if (port < 0) {
            Transport udp = _manager.getTransport(UDPTransport.STYLE);
            if (udp != null)
                port = udp.getRequestedPort();
        }
        _manager.externalAddressReceived(Transport.AddressSource.SOURCE_SSU, ip, port);
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
     *  Hook for pluggable transport creation.
     *
     *  @since 0.9.16
     */
    @Override
    public DHSessionKeyBuilder.Factory getDHFactory() {
        return _manager.getDHFactory();
    }
    
    /*
     * GeoIP stuff
     *
     * This is only used in the router console for now, but we put it here because
     * 1) it's a lot easier, and 2) we could use it in the future for peer selection,
     * tunnel selection, banlisting, etc.
     */

    /* We hope the routerinfos are read in and things have settled down by now, but it's not required to be so */
    private static final int START_DELAY = 5*60*1000;
    private static final int LOOKUP_TIME = 30*60*1000;

    private void startGeoIP() {
        _context.simpleScheduler().addEvent(new QueueAll(), START_DELAY);
    }

    /**
     * Collect the IPs for all routers in the DB, and queue them for lookup,
     * then fire off the periodic lookup task for the first time.
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
            _context.simpleScheduler().addPeriodicEvent(new Lookup(), 5000, LOOKUP_TIME);
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
     *  Are we in a bad place
     *  @since 0.8.13
     */
    @Override
    public boolean isInBadCountry() {
        String us = getOurCountry();
        return (us != null && BadCountries.contains(us)) || _context.getBooleanProperty("router.forceBadCountry");
    }

    /**
     *  Are they in a bad place
     *  @param peer non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInBadCountry(Hash peer) {
        String c = getCountry(peer);
        return c != null && BadCountries.contains(c);
    }

    /**
     *  Are they in a bad place
     *  @param ri non-null
     *  @since 0.9.16
     */
    @Override
    public boolean isInBadCountry(RouterInfo ri) {
        byte[] ip = getIP(ri);
        if (ip == null)
            return false;
        String c = _geoIP.get(ip);
        return c != null && BadCountries.contains(c);
    }

    /**
     *  Uses the transport IP first because that lookup is fast,
     *  then the IP from the netDb.
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
        ip = getIP(ri);
        if (ip != null)
            return _geoIP.get(ip);
        return null;
    }

    private static byte[] getIP(RouterInfo ri) {
        // Return first IP (v4 or v6) we find, any transport
        // Assume IPv6 doesn't have geoIP for now
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            //if (rv != null && rv.length == 4)
            if (rv != null)
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

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";

    /** Provide a consistent "look" for displaying router IDs in the console */
    @Override
    public String renderPeerHTML(Hash peer) {
        String h = peer.toBase64().substring(0, 4);
        StringBuilder buf = new StringBuilder(128);
        String c = getCountry(peer);
        if (c != null) {
            String countryName = getCountryName(c);
            if (countryName.length() > 2)
                countryName = Translate.getString(countryName, _context, COUNTRY_BUNDLE_NAME);
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(c.toUpperCase(Locale.US)).append("\" title=\"");
            buf.append(countryName);
            buf.append("\" src=\"/flags.jsp?c=").append(c).append("\"> ");
        }
        buf.append("<tt>");
        boolean found = _context.netDb().lookupRouterInfoLocally(peer) != null;
        if (found)
            buf.append("<a title=\"").append(_("NetDb entry")).append("\" href=\"netdb?r=").append(h).append("\">");
        buf.append(h);
        if (found)
            buf.append("</a>");
        buf.append("</tt>");
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
    private final String _(String s) {
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
        _context.simpleScheduler().addPeriodicEvent(new Timestamper(), TIME_START_DELAY,  TIME_REPEAT_DELAY);
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
             long peerOffset = getFramedAveragePeerClockSkew(50);
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
             boolean good = Addresses.isConnected();
             if (_netMonitorStatus != good) {
                 _context.router().eventLog().addEvent(EventLog.NETWORK, good ? "connected" : "disconnected");
                 _netMonitorStatus = good;
             }
             reschedule(good ? LONG_DELAY : SHORT_DELAY);
        }
    }
}
