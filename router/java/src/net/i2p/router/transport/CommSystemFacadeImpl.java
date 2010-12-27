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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.ntcp.NTCPAddress;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.UDPAddress;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Translate;

public class CommSystemFacadeImpl extends CommSystemFacade {
    private Log _log;
    private RouterContext _context;
    private TransportManager _manager;
    private GeoIP _geoIP;
    
    public CommSystemFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(CommSystemFacadeImpl.class);
        _manager = null;
        _context.statManager().createRateStat("transport.getBidsJobTime", "How long does it take?", "Transport", new long[] { 10*60*1000l });
        startGeoIP();
    }
    
    public void startup() {
        _log.info("Starting up the comm system");
        _manager = new TransportManager(_context);
        _manager.startListening();
        startTimestamper();
    }
    
    public void shutdown() {
        if (_manager != null)
            _manager.stopListening();
    }
    
    public void restart() {
        if (_manager == null)
            startup();
        else
            _manager.restart();
    }
    
    @Override
    public int countActivePeers() { return (_manager == null ? 0 : _manager.countActivePeers()); }
    @Override
    public int countActiveSendPeers() { return (_manager == null ? 0 : _manager.countActiveSendPeers()); } 
    @Override
    public boolean haveInboundCapacity(int pct) { return (_manager == null ? false : _manager.haveInboundCapacity(pct)); } 
    @Override
    public boolean haveOutboundCapacity(int pct) { return (_manager == null ? false : _manager.haveOutboundCapacity(pct)); } 
    @Override
    public boolean haveHighOutboundCapacity() { return (_manager == null ? false : _manager.haveHighOutboundCapacity()); } 
    
    /**
     * @param percentToInclude 1-100
     * @return Framed average clock skew of connected peers in milliseconds, or the clock offset if we cannot answer.
     * Average is calculated over the middle "percentToInclude" peers.
     * Todo: change Vectors to milliseconds
     */
    @Override
    public long getFramedAveragePeerClockSkew(int percentToInclude) {
        if (_manager == null) {
            return _context.clock().getOffset();
        }
        Vector skews = _manager.getClockSkews();
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
            long value = ((Long) (skews.get(i))).longValue();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Adding clock skew " + i + " valued " + value + " s.");
            sum = sum + value;
        }
        // Calculate average
        return sum * 1000 / frameSize;
    }
    
    public List<TransportBid> getBids(OutNetMessage msg) {
        return _manager.getBids(msg);
    }
    public TransportBid getBid(OutNetMessage msg) {
        return _manager.getBid(msg);
    }
    public TransportBid getNextBid(OutNetMessage msg) {
        return _manager.getNextBid(msg);
    }
    int getTransportCount() { return _manager.getTransportCount(); }
    
    /** Send the message out */
    public void processMessage(OutNetMessage msg) {	
        //GetBidsJob j = new GetBidsJob(_context, this, msg);
        //j.runJob();
        long before = _context.clock().now();
        GetBidsJob.getBids(_context, this, msg);
        _context.statManager().addRateData("transport.getBidsJobTime", _context.clock().now() - before, 0);
    }
    
    @Override
    public boolean isBacklogged(Hash dest) { 
        return _manager != null && _manager.isBacklogged(dest); 
    }
    
    @Override
    public boolean isEstablished(Hash dest) { 
        return _manager != null && _manager.isEstablished(dest); 
    }
    
    @Override
    public boolean wasUnreachable(Hash dest) { 
        return _manager != null && _manager.wasUnreachable(dest); 
    }
    
    @Override
    public byte[] getIP(Hash dest) { 
        return _manager.getIP(dest); 
    }
    
    @Override
    public List getMostRecentErrorMessages() { 
        return _manager.getMostRecentErrorMessages(); 
    }

    @Override
    public short getReachabilityStatus() { 
        if (_manager == null) return STATUS_UNKNOWN;
        if (_context.router().isHidden()) return STATUS_OK;
        return _manager.getReachabilityStatus(); 
    }
    @Override
    public void recheckReachability() { _manager.recheckReachability(); }

    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { 
        _manager.renderStatusHTML(out, urlBase, sortFlags); 
    }
    
    @Override
    public Set<RouterAddress> createAddresses() {
        Map<String, RouterAddress> addresses = null;
        boolean newCreated = false;
        
        if (_manager != null) {
            addresses = _manager.getAddresses();
        } else {
            addresses = new HashMap(1);
            newCreated = true;
        }
        
        if (!addresses.containsKey(NTCPTransport.STYLE)) {
            RouterAddress addr = createNTCPAddress(_context);
            if (_log.shouldLog(Log.INFO))
                _log.info("NTCP address: " + addr);
            if (addr != null)
                addresses.put(NTCPTransport.STYLE, addr);
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Creating addresses: " + addresses + " isNew? " + newCreated, new Exception("creator"));
        return new HashSet(addresses.values());
    }
    
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    
    /**
     * This only creates an address if the hostname AND port are set in router.config,
     * which should be rare.
     * Otherwise, notifyReplaceAddress() below takes care of it.
     * Note this is called both from above and from NTCPTransport.startListening()
     *
     * This should really be moved to ntcp/NTCPTransport.java, why is it here?
     */
    public static RouterAddress createNTCPAddress(RouterContext ctx) {
        if (!TransportManager.enableNTCP(ctx)) return null;
        String name = ctx.router().getConfigSetting(PROP_I2NP_NTCP_HOSTNAME);
        String port = ctx.router().getConfigSetting(PROP_I2NP_NTCP_PORT);
        /*
        boolean isNew = false;
        if (name == null) {
            name = "localhost";
            isNew = true;
        }
        if (port == null) {
            port = String.valueOf(ctx.random().nextInt(10240)+1024);
            isNew = true;
        }
         */
        if ( (name == null) || (port == null) || (name.trim().length() <= 0) || ("null".equals(name)) )
            return null;
        try {
            int p = Integer.parseInt(port);
            if ( (p <= 0) || (p > 64*1024) )
                return null;
        } catch (NumberFormatException nfe) {
            return null;
        }
        Properties props = new Properties();
        props.setProperty(NTCPAddress.PROP_HOST, name);
        props.setProperty(NTCPAddress.PROP_PORT, port);
        RouterAddress addr = new RouterAddress();
        addr.setCost(NTCPAddress.DEFAULT_COST);
        addr.setExpiration(null);
        addr.setOptions(props);
        addr.setTransportStyle(NTCPTransport.STYLE);
        //if (isNew) {
            // why save the same thing?
            ctx.router().setConfigSetting(PROP_I2NP_NTCP_HOSTNAME, name);
            ctx.router().setConfigSetting(PROP_I2NP_NTCP_PORT, port);
            ctx.router().saveConfig();
        //}
        return addr;
    }

    /**
     * UDP changed addresses, tell NTCP and restart
     * This should really be moved to ntcp/NTCPTransport.java, why is it here?
     */
    @Override
    public synchronized void notifyReplaceAddress(RouterAddress UDPAddr) {
        if (UDPAddr == null)
            return;
        NTCPTransport t = (NTCPTransport) _manager.getTransport(NTCPTransport.STYLE);
        if (t == null)
            return;
        Properties UDPProps = UDPAddr.getOptions();
        if (UDPProps == null)
            return;
        Properties newProps;
        RouterAddress oldAddr = t.getCurrentAddress();
        if (_log.shouldLog(Log.INFO))
            _log.info("Changing NTCP Address? was " + oldAddr);
        RouterAddress newAddr = oldAddr;
        if (newAddr == null) {
            newAddr = new RouterAddress();
            newAddr.setCost(NTCPAddress.DEFAULT_COST);
            newAddr.setExpiration(null);
            newAddr.setTransportStyle(NTCPTransport.STYLE);
            newProps = new Properties();
        } else {
            newProps = newAddr.getOptions();
            if (newProps == null)
                newProps = new Properties();
        }

        boolean changed = false;

        // Auto Port Setting
        // old behavior (<= 0.7.3): auto-port defaults to false, and true trumps explicit setting
        // new behavior (>= 0.7.4): auto-port defaults to true, but explicit setting trumps auto
        String oport = newProps.getProperty(NTCPAddress.PROP_PORT);
        String nport = null;
        String cport = _context.getProperty(PROP_I2NP_NTCP_PORT);
        if (cport != null && cport.length() > 0) {
            nport = cport;
        } else if (Boolean.valueOf(_context.getProperty(PROP_I2NP_NTCP_AUTO_PORT, "true")).booleanValue()) {
            nport = UDPProps.getProperty(UDPAddress.PROP_PORT);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("old: " + oport + " config: " + cport + " new: " + nport);
        if (nport == null || nport.length() <= 0)
            return;
        if (oport == null || ! oport.equals(nport)) {
            newProps.setProperty(NTCPAddress.PROP_PORT, nport);
            changed = true;
        }

        // Auto IP Setting
        // old behavior (<= 0.7.3): auto-ip defaults to false, and trumps configured hostname,
        //                          and ignores reachability status - leading to
        //                          "firewalled with inbound TCP enabled" warnings.
        // new behavior (>= 0.7.4): auto-ip defaults to true, and explicit setting trumps auto,
        //                          and only takes effect if reachability is OK.
        //                          And new "always" setting ignores reachability status, like
        //                          "true" was in 0.7.3
        String ohost = newProps.getProperty(NTCPAddress.PROP_HOST);
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true");
        String name = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME);
        // hostname config trumps auto config
        if (name != null && name.length() > 0)
            enabled = "false";
        Transport udp = _manager.getTransport(UDPTransport.STYLE);
        short status = STATUS_UNKNOWN;
        if (udp != null)
            status = udp.getReachabilityStatus();
        if (_log.shouldLog(Log.INFO))
            _log.info("old: " + ohost + " config: " + name + " auto: " + enabled + " status: " + status);
        if (enabled.equalsIgnoreCase("always") ||
            (enabled.equalsIgnoreCase("true") && status == STATUS_OK)) {
            String nhost = UDPProps.getProperty(UDPAddress.PROP_HOST);
            if (_log.shouldLog(Log.INFO))
                _log.info("old: " + ohost + " config: " + name + " new: " + nhost);
            if (nhost == null || nhost.length() <= 0)
                return;
            if (ohost == null || ! ohost.equalsIgnoreCase(nhost)) {
                newProps.setProperty(NTCPAddress.PROP_HOST, nhost);
                changed = true;
            }
        } else if (enabled.equalsIgnoreCase("false") &&
                   name != null && name.length() > 0 &&
                   !name.equals(ohost) &&
                   nport != null) {
            // Host name is configured, and we have a port (either auto or configured)
            // but we probably only get here if the port is auto,
            // otherwise createNTCPAddress() would have done it already
            if (_log.shouldLog(Log.INFO))
                _log.info("old: " + ohost + " config: " + name + " new: " + name);
            newProps.setProperty(NTCPAddress.PROP_HOST, name);
            changed = true;
        } else if (ohost == null || ohost.length() <= 0) {
            return;
        } else if (enabled.equalsIgnoreCase("true") && status != STATUS_OK) {
            // UDP transitioned to not-OK, turn off NTCP address
            // This will commonly happen at startup if we were initially OK
            // because UPnP was successful, but a subsequent SSU Peer Test determines
            // we are still firewalled (SW firewall, bad UPnP indication, etc.)
            if (_log.shouldLog(Log.INFO))
                _log.info("old: " + ohost + " config: " + name + " new: null");
            newAddr = null;
            changed = true;
        }

        if (!changed) {
            if (oldAddr != null) {
                int oldCost = oldAddr.getCost();
                int newCost = NTCPAddress.DEFAULT_COST;
                if (TransportImpl.ADJUST_COST && !t.haveCapacity())
                    newCost++;
                if (newCost != oldCost) {
                    oldAddr.setCost(newCost);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Changing NTCP cost from " + oldCost + " to " + newCost);
                } else {
                    _log.info("No change to NTCP Address");
                }
            } else {
                _log.info("No change to NTCP Address");
            }
            return;
        }

        // stopListening stops the pumper, readers, and writers, so required even if
        // oldAddr == null since startListening starts them all again
        //
        // really need to fix this so that we can change or create an inbound address
        // without tearing down everything
        // Especially on disabling the address, we shouldn't tear everything down.
        //
        _log.warn("Halting NTCP to change address");
        t.stopListening();
        if (newAddr != null)
            newAddr.setOptions(newProps);
        // Wait for NTCP Pumper to stop so we don't end up with two...
        while (t.isAlive()) {
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        }
        t.restartListening(newAddr);
        _log.warn("Changed NTCP Address and started up, address is now " + newAddr);
        return;     	
    }
    
    /*
     * GeoIP stuff
     *
     * This is only used in the router console for now, but we put it here because
     * 1) it's a lot easier, and 2) we could use it in the future for peer selection,
     * tunnel selection, shitlisting, etc.
     */

    /* We hope the routerinfos are read in and things have settled down by now, but it's not required to be so */
    private static final int START_DELAY = 5*60*1000;
    private static final int LOOKUP_TIME = 30*60*1000;
    private void startGeoIP() {
        _geoIP = new GeoIP(_context);
        SimpleScheduler.getInstance().addEvent(new QueueAll(), START_DELAY);
    }

    /**
     * Collect the IPs for all routers in the DB, and queue them for lookup,
     * then fire off the periodic lookup task for the first time.
     */
    private class QueueAll implements SimpleTimer.TimedEvent {
        public void timeReached() {
            for (Iterator<Hash> iter = _context.netDb().getAllRouters().iterator(); iter.hasNext(); ) {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(iter.next());
                if (ri == null)
                    continue;
                String host = getIPString(ri);
                if (host == null)
                    continue;
                _geoIP.add(host);
            }
            SimpleScheduler.getInstance().addPeriodicEvent(new Lookup(), 5000, LOOKUP_TIME);
        }
    }

    private class Lookup implements SimpleTimer.TimedEvent {
        public void timeReached() {
            _geoIP.blockingLookup();
        }
    }

    @Override
    public void queueLookup(byte[] ip) {
        _geoIP.add(ip);
    }

    /**
     *  Uses the transport IP first because that lookup is fast,
     *  then the SSU IP from the netDb.
     *
     *  @return two-letter lower-case country code or null
     */
    @Override
    public String getCountry(Hash peer) {
        byte[] ip = TransportImpl.getIP(peer);
        if (ip != null)
            return _geoIP.get(ip);
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return null;
        String s = getIPString(ri);
        if (s != null)
            return _geoIP.get(s);
        return null;
    }

    private String getIPString(RouterInfo ri) {
        // use SSU only, it is likely to be an IP not a hostname,
        // we don't want to generate a lot of DNS queries at startup
        RouterAddress ra = ri.getTargetAddress("SSU");
        if (ra == null)
            return null;
        Properties props = ra.getOptions();
        if (props == null)
            return null;
        return props.getProperty("host");
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

    /** Provide a consistent "look" for displaying router IDs in the console */
    @Override
    public String renderPeerHTML(Hash peer) {
        String h = peer.toBase64().substring(0, 4);
        StringBuilder buf = new StringBuilder(128);
        String c = getCountry(peer);
        if (c != null) {
            String countryName = getCountryName(c);
            if (countryName.length() > 2)
                countryName = Translate.getString(countryName, _context, BUNDLE_NAME);
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(c.toUpperCase()).append("\" title=\"");
            buf.append(countryName);
            buf.append("\" src=\"/flags.jsp?c=").append(c).append("\"> ");
        }
        buf.append("<tt>");
        boolean found = _context.netDb().lookupRouterInfoLocally(peer) != null;
        if (found)
            buf.append("<a title=\"").append(_("NetDb entry")).append("\" href=\"netdb.jsp?r=").append(h).append("\">");
        buf.append(h);
        if (found)
            buf.append("</a>");
        buf.append("</tt>");
        return buf.toString();
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
        SimpleScheduler.getInstance().addPeriodicEvent(new Timestamper(), TIME_START_DELAY,  TIME_REPEAT_DELAY);
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
}
