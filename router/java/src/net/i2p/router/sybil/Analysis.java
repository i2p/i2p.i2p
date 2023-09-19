package net.i2p.router.sybil;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.Banlist;
import net.i2p.router.Blocklist;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.app.RouterApp;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.util.HashDistance;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 *
 *  @since 0.9.38 split out from SybilRenderer
 *
 */
public class Analysis extends JobImpl implements RouterApp, Runnable {

    private final RouterContext _context;
    private final Log _log;
    private final ClientAppManager _cmgr;
    private final PersistSybil _persister;
    private volatile ClientAppState _state = UNINITIALIZED;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");
    private boolean _wasRun;
    private final List<String> _familyExemptPoints24 = new ArrayList<String>(2);

    /**
     *  The name we register with the ClientAppManager
     */
    public static final String APP_NAME = "sybil";
    public static final String PROP_FREQUENCY = "router.sybilFrequency";
    public static final String PROP_THRESHOLD = "router.sybilThreshold";
    public static final String PROP_BLOCK = "router.sybilEnableBlocking";
    public static final String PROP_NONFF = "router.sybilAnalyzeAll";
    public static final String PROP_BLOCKTIME = "router.sybilBlockPeriod";
    public static final String PROP_REMOVETIME = "router.sybilDeleteOld";
    private static final long MIN_FREQUENCY = 60*60*1000L;
    private static final long MIN_UPTIME = 75*60*1000L;

    public static final int PAIRMAX = 20;
    public static final int MAX = 10;
    // multiplied by size - 1, will also get POINTS24 added
    private static final double POINTS32 = 5.0;
    // multiplied by size - 1, will also get POINTS16 added
    private static final double POINTS24 = 4.0;
    // multiplied by size - 1
    private static final double POINTS16 = 0.25;
    private static final double POINTS_US32 = 25.0;
    private static final double POINTS_US24 = 20.0;
    private static final double POINTS_US16 = 10.0;

    // IPv6 since 0.9.57, likely to be on top of IPv4, so make lower
    private static final double POINTS_V6_US64 = 12.5;
    private static final double POINTS_V6_US48 = 5.0;
    private static final double POINTS64 = 2.0;
    private static final double POINTS48 = 0.5;

    private static final double POINTS_FAMILY = -10.0;
    private static final double POINTS_FAMILY_VERIFIED = POINTS_FAMILY * 4;
    private static final double POINTS_NONFF = -5.0;
    private static final double POINTS_BAD_FAMILY = 20.0;
    private static final double POINTS_BAD_OUR_FAMILY = 100.0;
    private static final double POINTS_OUR_FAMILY = -100.0;
    public static final double MIN_CLOSE = 242.0;
    private static final double PAIR_DISTANCE_FACTOR = 2.0;
    private static final double OUR_KEY_FACTOR = 4.0;
    private static final double VERSION_FACTOR = 1.0;
    private static final double POINTS_BAD_VERSION = 20.0;
    private static final double POINTS_UNREACHABLE = 4.0;
    private static final double POINTS_NEW = 4.0;
    // since we're blocking by default now, don't make this too high,
    // so we don't always turn a temporary block into a permanent one.
    private static final double POINTS_BANLIST = 10.0;
    public static final boolean DEFAULT_BLOCK = true;
    public static final double DEFAULT_BLOCK_THRESHOLD = 50.0;
    public static final long DEFAULT_BLOCK_TIME = 7*24*60*60*1000L;
    public static final long DEFAULT_REMOVE_TIME = 10*24*60*60*1000L;
    public static final long SHORT_REMOVE_TIME = 2*24*60*60*1000L;
    public static final long DEFAULT_FREQUENCY = 24*60*60*1000L;
    public static final float MIN_BLOCK_POINTS = 12.01f;
    private static final byte[] IPV6_LOCALHOST = new byte[16];
    static { IPV6_LOCALHOST[15] = 1; }

    /** Get via getInstance() */
    private Analysis(RouterContext ctx, ClientAppManager mgr, String[] args) {
        super(ctx);
        _context = ctx;
        _log = ctx.logManager().getLog(Analysis.class);
        _cmgr = mgr;
        _persister = new PersistSybil(ctx);
        _familyExemptPoints24.add("SUNYSB");
        _familyExemptPoints24.add("stormycloud");
    }

    /**
     *  @return non-null, creates new if not already registered
     */
    public synchronized static Analysis getInstance(RouterContext ctx) {
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr == null)
            return null;
        Analysis rv = (Analysis) cmgr.getRegisteredApp(APP_NAME);
        if (rv == null) {
            rv = new Analysis(ctx, cmgr, null);
            rv.startup();
        }
        return rv;
    }

    public PersistSybil getPersister() { return _persister; }

    /**
     *  Load the persisted blocklist and tell the router
     *
     *  @since 0.9.50
     */
    private class InitJob extends JobImpl {
        public InitJob() { super(_context); }

        public String getName() { return "Load Sybil Blocklist"; }

        public void runJob() {
            Map<String, Long> map = _persister.readBlocklist();
            if (map == null || map.isEmpty())
                return;
            Blocklist bl = _context.blocklist();
            Banlist ban = _context.banlist();
            String source = _persister.getBlocklistFile().toString();
            for (Map.Entry<String, Long> e : map.entrySet()) {
                String s = e.getKey();
                if (s.contains(".") || s.contains(":")) {
                    bl.add(s, source);
                } else {
                    byte[] b = Base64.decode(s);
                    if (b != null && b.length == Hash.HASH_LENGTH) {
                        Hash h = Hash.create(b);
                        long until = e.getValue().longValue();
                        ban.banlistRouter(h, "Sybil analysis", null, ban.BANLIST_CODE_HARD, null, until);
                    }
                }
            }
        }
    }

    /////// begin Job methods

    public void runJob() {
        Thread t = new I2PAppThread(this, getDisplayName());
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        schedule();
    }

    /**
     *  @since 0.9.58
     */
    public void run() {
        long now = _context.clock().now();
        _log.info("Running analysis");
        Map<Hash, Points> points = backgroundAnalysis(_context.getBooleanProperty(PROP_NONFF));
        if (!points.isEmpty()) {
            try {
                _log.info("Storing analysis");
                _persister.store(now, points);
                _persister.removeOld();
                _log.info("Store complete");
            } catch (IOException ioe) {
                _log.error("Failed to store analysis", ioe);
            }
        }
    }

    /////// end Job methods
    /////// begin ClientApp methods

    /**
     *  ClientApp interface
     */
    public synchronized void startup() {
        changeState(STARTING);
        changeState(RUNNING);
        _cmgr.register(this);
        _persister.removeOld();
        InitJob init = new InitJob();
        long start = _context.clock().now() + 5*1000;
        init.getTiming().setStartAfter(start);
        _context.jobQueue().addJob(init);
        schedule();
    }

    /**
     *  ClientApp interface
     *  @param args ignored
     */
    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED)
            return;
        changeState(STOPPING);
        changeState(STOPPED);
    }

    public ClientAppState getState() {
        return _state;
    }

    public String getName() {
        return APP_NAME;
    }

    public String getDisplayName() {
        return "Sybil Analyzer";
    }

    /////// end ClientApp methods

    private synchronized void changeState(ClientAppState state) {
        _state = state;
        if (_cmgr != null)
            _cmgr.notify(this, state, null, null);
    }

    public synchronized void schedule() {
        long freq = _context.getProperty(PROP_FREQUENCY, DEFAULT_FREQUENCY);
        if (freq > 0) {
            List<Long> previous = _persister.load();
            long now = _context.clock().now() + 15*1000;
            if (freq < MIN_FREQUENCY)
                freq = MIN_FREQUENCY;
            long when;
            if (_wasRun) {
                when = now + freq;
            } else if (!previous.isEmpty()) {
                when = Math.max(previous.get(0).longValue() + freq, now);
            } else {
                when = now;
            }
            long up = _context.router().getUptime();
            when = Math.max(when, now + MIN_UPTIME - up);
            getTiming().setStartAfter(when);
            _context.jobQueue().addJob(this);
        } else {
            _context.jobQueue().removeJob(this);
        }
    }

    private static class RouterInfoRoutingKeyComparator implements Comparator<RouterInfo>, Serializable {
         private final Hash _us;
         /** @param us ROUTING KEY */
         public RouterInfoRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(RouterInfo l, RouterInfo r) {
             return HashDistance.getDistance(_us, l.getHash()).compareTo(HashDistance.getDistance(_us, r.getHash()));
        }
    }

    /**
     *  Merge points1 into points2.
     *  points1 is unmodified.
     */
/****
    private void mergePoints(Map<Hash, Points> points1, Map<Hash, Points> points2) {
        for (Map.Entry<Hash, Points> e : points1.entrySet()) {
             Hash h = e.getKey();
             Points p1 = e.getValue();
             Points p2 = points2.get(h);
             if (p2 != null) {
                 p2.points += p1.points;
                 p2.reasons.addAll(p1.reasons);
             } else {
                 points2.put(h, p1);
             }
        }
    }
****/

    /** */
    private void addPoints(Map<Hash, Points> points, Hash h, double d, String reason) {
        Points dd = points.get(h);
        if (dd != null) {
            dd.addPoints(d, reason);
        } else {
            points.put(h, new Points(d, reason));
        }
    }

    /**
     *  All the floodfills, not including us
     *  @since 0.9.38 split out from renderRouterInfoHTML
     */
    public List<RouterInfo> getFloodfills(Hash us) {
        Set<Hash> ffs = _context.peerManager().getPeersByCapability('f');
        List<RouterInfo> ris = new ArrayList<RouterInfo>(ffs.size());
        for (Hash ff : ffs) {
             if (ff.equals(us))
                 continue;
             RouterInfo ri = _context.mainNetDb().lookupRouterInfoLocally(ff);
             if (ri != null)
                 ris.add(ri);
        }
        return ris;
    }

    /**
     *  All the routers, not including us
     *  @since 0.9.41
     */
    public List<RouterInfo> getAllRouters(Hash us) {
        Set<RouterInfo> set = _context.mainNetDb().getRouters();
        List<RouterInfo> ris = new ArrayList<RouterInfo>(set.size());
        for (RouterInfo ri : set) {
            if (!ri.getIdentity().getHash().equals(us))
            ris.add(ri);
        }
        return ris;
    }

    public double getAvgMinDist(List<RouterInfo> ris) {
        double tot = 0;
        int count = 200;
        byte[] b = new byte[32];
        for (int i = 0; i < count; i++) {
            _context.random().nextBytes(b);
            Hash h = new Hash(b);
            double d = closestDistance(h, ris);
            tot += d;
        }
        double avgMinDist = tot / count;
        return avgMinDist;
    }

    /**
     *  Analyze threats. No output.
     *  Return separate maps for each cause instead?
     *  @param includeAll false for floodfills only
     *  @since 0.9.38
     */
    public synchronized Map<Hash, Points> backgroundAnalysis(boolean includeAll) {
        _wasRun = true;
        Map<Hash, Points> points = new HashMap<Hash, Points>(64);
        Hash us = _context.routerHash();
        if (us == null)
            return points;
        List<RouterInfo> ris;
        if (includeAll) {
            ris = getAllRouters(us);
        } else {
            ris = getFloodfills(us);
        }
        if (ris.isEmpty())
            return points;
        if (_log.shouldWarn())
            _log.warn("Analyzing " + ris.size() + " routers, including non-floodfills? " + includeAll);

        // IP analysis
        calculateIPGroupsFamily(ris, points);
        // unused here, just for the console, so use the same for all of them
        List<RouterInfo> dummy = new DummyList();
        calculateIPGroupsUs(ris, points, dummy, dummy, dummy, dummy, dummy);
        calculateIPGroups32(ris, points);
        calculateIPGroups24(ris, points);
        calculateIPGroups16(ris, points);
        calculateIPGroups64(ris, points);
        calculateIPGroups48(ris, points);

        // Pairwise distance analysis
        // O(n**2)
        long sz = ris.size();
        if (sz * sz < SystemVersion.getMaxMemory() / 10) {
            List<Pair> pairs = new ArrayList<Pair>(PAIRMAX);
            calculatePairDistance(ris, points, pairs);
        }

        // Distance to our router analysis
        // closest to our routing key today
        Hash ourRKey = _context.router().getRouterInfo().getRoutingKey();
        calculateRouterInfo(ourRKey, "our rkey", ris, points);
        // closest to our routing key tomorrow
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        Hash nkey = rkgen.getNextRoutingKey(us);
        calculateRouterInfo(nkey, "our rkey (tomorrow)", ris, points);
        // closest to us
        calculateRouterInfo(us, "our router", ris, points);

        // Distance to our published destinations analysis
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        List<Hash> destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        for (Hash client : destinations) {
            boolean isLocal = _context.clientManager().isLocal(client);
            if (!isLocal)
                continue;
            if (! _context.clientManager().shouldPublishLeaseSet(client))
                continue;
            LeaseSet ls = _context.mainNetDb().lookupLeaseSetLocally(client);
            if (ls == null)
                continue;
            Hash rkey = ls.getRoutingKey();
            TunnelPool in = clientInboundPools.get(client);
            String name = (in != null) ? DataHelper.escapeHTML(in.getSettings().getDestinationNickname()) : client.toBase64().substring(0,4);
            // closest to routing key today
            calculateRouterInfo(rkey, name, ris, points);
            // closest to routing key tomorrow
            nkey = rkgen.getNextRoutingKey(ls.getHash());
            calculateRouterInfo(nkey, name + " (tomorrow)", ris, points);
        }

        // Profile analysis
        addProfilePoints(ris, points);
        addVersionPoints(ris, points);
        if (_context.getProperty(PROP_BLOCK, DEFAULT_BLOCK))
            doBlocking(points);
        return points;
    }

    /**
     *  @since 0.9.57
     */
    private static class DummyList extends ArrayList<RouterInfo> {
        public DummyList() { super(0); }
        @Override
        public boolean add(RouterInfo ri) { return true; }
    }

    /**
     *  Blocklist and Banlist if configured
     *  @since 0.9.41
     */
    private void doBlocking(Map<Hash, Points> points) {
        double threshold = DEFAULT_BLOCK_THRESHOLD;
        long now = _context.clock().now();
        long blockUntil = _context.getProperty(Analysis.PROP_BLOCKTIME, DEFAULT_BLOCK_TIME) + now;
        try {
            threshold = Double.parseDouble(_context.getProperty(PROP_THRESHOLD, Double.toString(DEFAULT_BLOCK_THRESHOLD)));
            if (threshold < MIN_BLOCK_POINTS)
                threshold = MIN_BLOCK_POINTS;
        } catch (NumberFormatException nfe) {}
        String day = DataHelper.formatTime(now);
        Set<String> blocks = new HashSet<String>();
        for (Map.Entry<Hash, Points> e : points.entrySet()) {
            double p = e.getValue().getPoints();
            if (p >= threshold) {
                Hash h = e.getKey();
                blocks.add(h.toBase64());
                RouterInfo ri = _context.mainNetDb().lookupRouterInfoLocally(h);
                if (ri != null) {
                    for (RouterAddress ra : ri.getAddresses()) {
                        byte[] ip = ra.getIP();
                        if (ip != null)
                            _context.blocklist().add(ip, "Sybil " + h.toBase64());
                        String host = ra.getHost();
                        if (host != null)
                            blocks.add(host);
                    }
                }
                String reason = "Sybil analysis " + day + " with " + fmt.format(p) + " threat points";
                if (_log.shouldWarn()) {
                    if (ri != null)
                        _log.warn("Banned by " + reason + " and blocking IPs:\n" + ri);
                    else
                        _log.warn("Banned " + h.toBase64() + " by " + reason);
                }
                _context.banlist().banlistRouter(h, reason, null, Banlist.BANLIST_CODE_HARD, null, blockUntil);
            }
        }
        if (!blocks.isEmpty())
            _persister.storeBlocklist(blocks, blockUntil);
    }

    /**
     *  @param pairs out parameter, sorted
     *  @return average distance
     *  @since 0.9.38 split out from renderPairDistance()
     */
    public double calculatePairDistance(List<RouterInfo> ris, Map<Hash, Points> points,
                                        List<Pair> pairs) {
        int sz = ris.size();
        double total = 0;
        for (int i = 0; i < sz; i++) {
            RouterInfo info1 = ris.get(i);
            // don't do distance calculation for non-floodfills
            if (!info1.getCapabilities().contains("f"))
                continue;
            for (int j = i + 1; j < sz; j++) {
                RouterInfo info2 = ris.get(j);
                // don't do distance calculation for non-floodfills
                if (!info2.getCapabilities().contains("f"))
                    continue;
                BigInteger dist = HashDistance.getDistance(info1.getHash(), info2.getHash());
                if (pairs.isEmpty()) {
                    pairs.add(new Pair(info1, info2, dist));
                } else if (pairs.size() < PAIRMAX) {
                    pairs.add(new Pair(info1, info2, dist));
                    Collections.sort(pairs);
                } else if (dist.compareTo(pairs.get(PAIRMAX - 1).dist) < 0) {
                    pairs.set(PAIRMAX - 1, new Pair(info1, info2, dist));
                    Collections.sort(pairs);
                }
                total += biLog2(dist);
            }
        }

        double avg = total / (sz * sz / 2d);
        String other = _context.getBooleanProperty(PROP_NONFF) ? "router" : "floodfill";
        for (Pair p : pairs) {
            double distance = biLog2(p.dist);
            double point = MIN_CLOSE - distance;
            if (point < 0)
                break;  // sorted;
            point *= PAIR_DISTANCE_FACTOR;
            String b2 = p.r2.getHash().toBase64();
            addPoints(points, p.r1.getHash(), point, "Very close (" + fmt.format(distance) +
                          ") to other " + other + " <a href=\"netdb?r=" + b2 + "\">" + b2 + "</a>");
            String b1 = p.r1.getHash().toBase64();
            addPoints(points, p.r2.getHash(), point, "Very close (" + fmt.format(distance) +
                          ") to other " + other + " <a href=\"netdb?r=" + b1 + "\">" + b1 + "</a>");
        }
        return avg;
    }

    private static final BigInteger BI_MAX = (new BigInteger("2")).pow(256);

    private static double closestDistance(Hash h, List<RouterInfo> ris) {
        BigInteger min = BI_MAX;
        for (RouterInfo info : ris) {
            BigInteger dist = HashDistance.getDistance(h, info.getHash());
            if (dist.compareTo(min) < 0)
                min = dist;
        }
        return biLog2(min);
    }

    /** v4 only */
    private static byte[] getIP(RouterInfo ri) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null && rv.length == 4)
                return rv;
        }
        return null;
    }

    /**
     * v6 only
     * @since 0.9.57
     */
    private static byte[] getIPv6(RouterInfo ri) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null && rv.length == 16)
                // i2pd
                if (!DataHelper.eq(rv, IPV6_LOCALHOST))
                    return rv;
        }
        return null;
    }

    /**
     *  @param ri32 out parameter
     *  @param ri24 out parameter
     *  @param ri16 out parameter
     *  @param ri64 out parameter
     *  @param ri48 out parameter
     *  @since 0.9.38 split out from renderIPGroupsUs()
     */
    public void calculateIPGroupsUs(List<RouterInfo> ris, Map<Hash, Points> points,
                                    List<RouterInfo> ri32, List<RouterInfo> ri24, List<RouterInfo> ri16,
                                    List<RouterInfo> ri64, List<RouterInfo> ri48) {
        RouterInfo us = _context.router().getRouterInfo();
        byte[] ourIP = getIP(us);
        if (ourIP == null) {
            String last = _context.getProperty("i2np.lastIP");
            if (last != null)
                ourIP = Addresses.getIPOnly(last);
        }
        byte[] ourIPv6 = getIPv6(us);
        if (ourIPv6 == null) {
            String last = _context.getProperty("i2np.lastIPv6");
            if (last != null)
                ourIPv6 = Addresses.getIPOnly(last);
        }
        if (ourIP == null && ourIPv6 == null)
            return;

        String reason32;
        String reason24;
        String reason16;
        String reason64;
        String reason48;

        if (ourIP != null) {
            reason32 = "Same IP as <a href=\"/netdb?ip=" +
                       Addresses.toString(ourIP) +
                       "&amp;sybil\">us</a>";
            reason24 = "Same IPv4 /24 as <a href=\"/netdb?ip=" +
                       (ourIP[0] & 0xff) + '.' +
                       (ourIP[1] & 0xff) + '.' +
                       (ourIP[2] & 0xff) +
                       ".0/24&amp;sybil\">us</a>";
            reason16 = "Same IPv4 /16 as <a href=\"/netdb?ip=" +
                       (ourIP[0] & 0xff) + '.' +
                       (ourIP[1] & 0xff) +
                       ".0.0/16&amp;sybil\">us</a>";
        } else {
            reason32 = null;
            reason24 = null;
            reason16 = null;
        }
        if (ourIPv6 != null) {
            reason64 = "Same IPv6 /64 as <a href=\"/netdb?ip=" +
                        Integer.toString(((ourIPv6[0] << 8) & 0xff00) | (ourIPv6[1] & 0xff), 16) + ':' +
                        Integer.toString(((ourIPv6[2] << 8) & 0xff00) | (ourIPv6[3] & 0xff), 16) + ':' +
                        Integer.toString(((ourIPv6[4] << 8) & 0xff00) | (ourIPv6[5] & 0xff), 16) + ':' +
                        Integer.toString(((ourIPv6[6] << 8) & 0xff00) | (ourIPv6[7] & 0xff), 16) +
                        "::&amp;sybil\">us</a>";
            reason48 = "Same IPv6 /48 as <a href=\"/netdb?ip=" +
                        Integer.toString(((ourIPv6[0] << 8) & 0xff00) | (ourIPv6[1] & 0xff), 16) + ':' +
                        Integer.toString(((ourIPv6[2] << 8) & 0xff00) | (ourIPv6[3] & 0xff), 16) + ':' +
                        Integer.toString(((ourIPv6[4] << 8) & 0xff00) | (ourIPv6[5] & 0xff), 16) +
                        "::&amp;sybil\">us</a>";
        } else {
            reason64 = null;
            reason48 = null;
        }
        for (RouterInfo info : ris) {
            if (ourIP != null) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if (ip[0] == ourIP[0] && ip[1] == ourIP[1]) {
                    if (ip[2] == ourIP[2]) {
                        if (ip[3] == ourIP[3]) {
                            addPoints(points, info.getHash(), POINTS_US32, reason32);
                            ri32.add(info);
                        } else {
                            addPoints(points, info.getHash(), POINTS_US24, reason24);
                            ri24.add(info);
                        }
                    } else {
                        addPoints(points, info.getHash(), POINTS_US16, reason16);
                        ri16.add(info);
                    }
                }
            }
            if (ourIPv6 != null) {
                byte[] ip = getIPv6(info);
                if (ip == null)
                    continue;
                if (DataHelper.eq(ip, 0, ourIPv6, 0, 6)) {
                    if (ip[6] == ourIPv6[6] && ip[7] == ourIPv6[7]) {
                        addPoints(points, info.getHash(), POINTS_V6_US64, reason64);
                        ri64.add(info);
                    } else {
                        addPoints(points, info.getHash(), POINTS_V6_US48, reason48);
                        ri48.add(info);
                    }
                }
            }
        }
    }

    /**
     *  @since 0.9.38 split out from renderIPGroups32()
     */
    public Map<Integer, List<RouterInfo>> calculateIPGroups32(List<RouterInfo> ris, Map<Hash, Points> points) {
        ObjectCounterUnsafe<Integer> oc = new ObjectCounterUnsafe<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 4));
            oc.increment(x);
        }
        Map<Integer, List<RouterInfo>> rv = new HashMap<Integer, List<RouterInfo>>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 2)
                rv.put(ii, new ArrayList<RouterInfo>(count));
        }
        for (Map.Entry<Integer, List<RouterInfo>> e : rv.entrySet()) {
            Integer ii = e.getKey();
            int count = oc.count(ii);
            double point = POINTS32 * (count - 1);
            int i = ii.intValue();
            int i0 = (i >> 24) & 0xff;
            int i1 = (i >> 16) & 0xff;
            int i2 = (i >> 8) & 0xff;
            int i3 = i & 0xff;
            String reason = "Same IP with <a href=\"/netdb?ip=" +
                            i0 + '.' + i1 + '.' + i2 + '.' + i3 + "&amp;sybil\">" +
                            (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                if ((ip[3] & 0xff) != i3)
                    continue;
                e.getValue().add(info);
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    /**
     *  @since 0.9.38 split out from renderIPGroups24()
     */
    public Map<Integer, List<RouterInfo>> calculateIPGroups24(List<RouterInfo> ris, Map<Hash, Points> points) {
        ObjectCounterUnsafe<Integer> oc = new ObjectCounterUnsafe<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 3));
            oc.increment(x);
        }
        Map<Integer, List<RouterInfo>> rv = new HashMap<Integer, List<RouterInfo>>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 2)
                rv.put(ii, new ArrayList<RouterInfo>(count));
        }
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        for (Map.Entry<Integer, List<RouterInfo>> e : rv.entrySet()) {
            Integer ii = e.getKey();
            int count = oc.count(ii);
            double point = POINTS24 * (count - 1);
            int i = ii.intValue();
            int i0 = i >> 16;
            int i1 = (i >> 8) & 0xff;
            int i2 = i & 0xff;
            String reason = "Same IPv4 /24 with <a href=\"/netdb?ip=" +
                            i0 + '.' + i1 + '.' + i2 + ".0/24&amp;sybil\">" +
                            (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                if (fkc != null) {
                    String f = info.getOption("family");
                    if (f != null && _familyExemptPoints24.contains(f) &&
                        fkc.verify(info) == FamilyKeyCrypto.Result.STORED_KEY)
                        continue;
                }
                e.getValue().add(info);
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    /**
     *  @since 0.9.38 split out from renderIPGroups16()
     */
    public Map<Integer, List<RouterInfo>> calculateIPGroups16(List<RouterInfo> ris, Map<Hash, Points> points) {
        ObjectCounterUnsafe<Integer> oc = new ObjectCounterUnsafe<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 2));
            oc.increment(x);
        }
        Map<Integer, List<RouterInfo>> rv = new HashMap<Integer, List<RouterInfo>>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 4)
                rv.put(ii, new ArrayList<RouterInfo>(count));
        }
        for (Map.Entry<Integer, List<RouterInfo>> e : rv.entrySet()) {
            Integer ii = e.getKey();
            int count = oc.count(ii);
            double point = POINTS16 * (count - 1);
            int i = ii.intValue();
            int i0 = i >> 8;
            int i1 = i & 0xff;
            String reason = "Same IPv4 /16 with <a href=\"/netdb?ip=" +
                            i0 + '.' + i1 + ".0.0/16&amp;sybil\">" +
                            (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                e.getValue().add(info);
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    /**
     *  @since 0.9.57
     */
    public Map<Long, List<RouterInfo>> calculateIPGroups64(List<RouterInfo> ris, Map<Hash, Points> points) {
        ObjectCounterUnsafe<Long> oc = new ObjectCounterUnsafe<Long>();
        for (RouterInfo info : ris) {
            byte[] ip = getIPv6(info);
            if (ip == null)
                continue;
            Long x = Long.valueOf(DataHelper.fromLong8(ip, 0));
            oc.increment(x);
        }
        Map<Long, List<RouterInfo>> rv = new HashMap<Long, List<RouterInfo>>();
        for (Long ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 2)
                rv.put(ii, new ArrayList<RouterInfo>(count));
        }
        for (Map.Entry<Long, List<RouterInfo>> e : rv.entrySet()) {
            Long ii = e.getKey();
            int count = oc.count(ii);
            double point = POINTS64 * (count - 1);
            long i = ii.longValue();
            int i0 = (int) ((i >> 56) & 0xff);
            int i1 = (int) ((i >> 48) & 0xff);
            int i2 = (int) ((i >> 40) & 0xff);
            int i3 = (int) ((i >> 32) & 0xff);
            int i4 = (int) ((i >> 24) & 0xff);
            int i5 = (int) ((i >> 16) & 0xff);
            int i6 = (int) ((i >> 8) & 0xff);
            int i7 = (int) (i & 0xff);
            String reason = "Same IPv6 /64 with <a href=\"/netdb?ip=" +
                            Integer.toString((i0 << 8) | i1, 16) + ':' +
                            Integer.toString((i2 << 8) | i3, 16) + ':' +
                            Integer.toString((i4 << 8) | i5, 16) + ':' +
                            Integer.toString((i6 << 8) | i7, 16) +
                            "::&amp;sybil\">" +
                            (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
            for (RouterInfo info : ris) {
                byte[] ip = getIPv6(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                if ((ip[3] & 0xff) != i3)
                    continue;
                if ((ip[4] & 0xff) != i4)
                    continue;
                if ((ip[5] & 0xff) != i5)
                    continue;
                if ((ip[6] & 0xff) != i6)
                    continue;
                if ((ip[7] & 0xff) != i7)
                    continue;
                e.getValue().add(info);
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    /**
     *  @since 0.9.57
     */
    public Map<Long, List<RouterInfo>> calculateIPGroups48(List<RouterInfo> ris, Map<Hash, Points> points) {
        ObjectCounterUnsafe<Long> oc = new ObjectCounterUnsafe<Long>();
        for (RouterInfo info : ris) {
            byte[] ip = getIPv6(info);
            if (ip == null)
                continue;
            Long x = Long.valueOf(DataHelper.fromLong(ip, 0, 6));
            oc.increment(x);
        }
        Map<Long, List<RouterInfo>> rv = new HashMap<Long, List<RouterInfo>>();
        for (Long ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 4)
                rv.put(ii, new ArrayList<RouterInfo>(count));
        }
        for (Map.Entry<Long, List<RouterInfo>> e : rv.entrySet()) {
            Long ii = e.getKey();
            int count = oc.count(ii);
            double point = POINTS48 * (count - 1);
            long i = ii.longValue();
            int i0 = (int) ((i >> 40) & 0xff);
            int i1 = (int) ((i >> 32) & 0xff);
            int i2 = (int) ((i >> 24) & 0xff);
            int i3 = (int) ((i >> 16) & 0xff);
            int i4 = (int) ((i >> 8) & 0xff);
            int i5 = (int) (i & 0xff);
            String reason = "Same IPv6 /48 with <a href=\"/netdb?ip=" +
                            Integer.toString((i0 << 8) | i1, 16) + ':' +
                            Integer.toString((i2 << 8) | i3, 16) + ':' +
                            Integer.toString((i4 << 8) | i5, 16) +
                            "::&amp;sybil\">" +
                            (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
            for (RouterInfo info : ris) {
                byte[] ip = getIPv6(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                if ((ip[3] & 0xff) != i3)
                    continue;
                if ((ip[4] & 0xff) != i4)
                    continue;
                if ((ip[5] & 0xff) != i5)
                    continue;
                e.getValue().add(info);
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    /**
     *  @return map of family name to list of routers in that family
     *  @since 0.9.38 split out from renderIPGroupsFamily()
     */
    public Map<String, List<RouterInfo>> calculateIPGroupsFamily(List<RouterInfo> ris, Map<Hash, Points> points) {
        Map<String, List<RouterInfo>> rv = new HashMap<String, List<RouterInfo>>();
        for (RouterInfo info : ris) {
            String fam = info.getOption("family");
            if (fam == null)
                continue;
            List<RouterInfo> fris = rv.get(fam);
            if (fris == null) {
                fris = new ArrayList<RouterInfo>(4);
                rv.put(fam, fris);
            }
            fris.add(info);
        }
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        String ourFamily = fkc != null ? fkc.getOurFamilyName() : null;
        for (Map.Entry<String, List<RouterInfo>> e : rv.entrySet()) {
            String s = e.getKey();
            List<RouterInfo> list = e.getValue();
            int count = list.size();
            String ss = DataHelper.escapeHTML(s);
            for (RouterInfo info : list) {
                double point;
                String reason;
                if (fkc != null) {
                    if (s.equals(ourFamily)) {
                        if (fkc.verifyOurFamily(info)) {
                            point = POINTS_OUR_FAMILY;
                            reason = "Our family \"" + ss + "\" with <a href=\"/netdb?fam=" + ss + "&amp;sybil\">" + (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
                        } else {
                            point = POINTS_BAD_OUR_FAMILY;
                            reason = "Spoofed our family \"" + ss + "\" with <a href=\"/netdb?fam=" + ss + "&amp;sybil\">" + (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
                        }
                    } else {
                        FamilyKeyCrypto.Result r = fkc.verify(info);
                        switch (r) {
                            case BAD_KEY:
                            case BAD_SIG:
                            case INVALID_SIG:
                            case NO_SIG:
                                point = POINTS_BAD_FAMILY;
                                reason = "Bad family config \"" + ss + '"';
                                break;

                            case STORED_KEY:
                                point = POINTS_FAMILY_VERIFIED;
                                if (count > 1)
                                    reason = "In verified family \"" + ss + "\" with <a href=\"/netdb?fam=" + ss + "&amp;sybil\">" + (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
                                else
                                    reason = "In verified family \"" + ss + '"';
                                break;

                            case NO_KEY:
                            case RI_KEY:
                            case UNSUPPORTED_SIG:
                            case NAME_CHANGED:
                            case SIG_CHANGED:
                            case NO_FAMILY:  // won't happen
                            default:
                                point = POINTS_FAMILY;
                                if (count > 1)
                                    reason = "In unverified family \"" + ss + "\" with <a href=\"/netdb?fam=" + ss + "&amp;sybil\">" + (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
                                else
                                    reason = "In unverified family \"" + ss + '"';
                                break;
                        }
                    }
                } else if (count > 1) {
                    point = POINTS_FAMILY;
                    reason = "In unverified family \"" + ss + "\" with <a href=\"/netdb?fam=" + ss + "&amp;sybil\">" + (count - 1) + " other" + (( count > 2) ? "s" : "") + "</a>";
                } else {
                    point = POINTS_FAMILY;
                    reason = "In unverified family \"" + ss + '"';
                }
                addPoints(points, info.getHash(), point, reason);
            }
        }
        return rv;
    }

    private static final long DAY = 24*60*60*1000L;

    public void addProfilePoints(List<RouterInfo> ris, Map<Hash, Points> points) {
        Map<Hash, Banlist.Entry> banEntries = _context.banlist().getEntries();
        long now = _context.clock().now();
        RateAverages ra = RateAverages.getTemp();
        for (RouterInfo info : ris) {
            Hash h = info.getHash();
            if (_context.banlist().isBanlisted(h)) {
                StringBuilder buf = new StringBuilder("Banlisted");
                Banlist.Entry entry = banEntries.get(h);
                if (entry != null) {
                    if (entry.cause != null) {
                        buf.append(": ");
                        if (entry.causeComment != null)
                            buf.append(_t(entry.cause, entry.causeComment));
                        else
                            buf.append(_t(entry.cause));
                    }
                }
                addPoints(points, h, POINTS_BANLIST, buf.toString());
            }
            // don't do profile calcluations for non-floodfills
            if (!info.getCapabilities().contains("f"))
                continue;
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(h);
            if (prof != null) {
                long heard = prof.getFirstHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    if (age < 2 * DAY) {
                        // (POINTS_NEW / 48) for every hour under 48, max POINTS_NEW
                        double point = Math.min(POINTS_NEW, (2 * DAY - age) / (2 * DAY / POINTS_NEW));
                        addPoints(points, h, point,
                                  "First heard about: " + _t("{0} ago", DataHelper.formatDuration2(age)));
                    }
                }
                DBHistory dbh = prof.getDBHistory();
                if (dbh != null) {
                    RateStat rs = dbh.getFailedLookupRate();
                    if (rs != null) {
                        Rate r = rs.getRate(24*60*60*1000);
                        if (r != null) {
                            r.computeAverages(ra, false);
                            if (ra.getTotalEventCount() > 0) {
                                double avg = 100 * ra.getAverage();
                                if (avg > 40)
                                    addPoints(points, h, (avg - 40) / 6.0, "Lookup fail rate " + ((int) avg) + '%');
                            }
                        }
                    }
                }
            }
        }
    }

    public void addVersionPoints(List<RouterInfo> ris, Map<Hash, Points> points) {
        RouterInfo us = _context.router().getRouterInfo();
        if (us == null) return;
        String ourVer = us.getVersion();
        // TODO do the math once we hit version 1.0.0
        if (!ourVer.startsWith("0.9.")) return;
        ourVer = ourVer.substring(4);
        int dot = ourVer.indexOf('.');
        if (dot > 0)
            ourVer = ourVer.substring(0, dot);
        int minor;
        try {
            minor = Integer.parseInt(ourVer);
        } catch (NumberFormatException nfe) { return; }
        for (RouterInfo info : ris) {
            Hash h = info.getHash();
            String caps = info.getCapabilities();
            if (!caps.contains("R"))
                addPoints(points, h, POINTS_UNREACHABLE, "Unreachable: " + DataHelper.escapeHTML(caps));
            if (!caps.contains("f"))
                addPoints(points, h, POINTS_NONFF, "Non-floodfill");
            String hisFullVer = info.getVersion();
            if (!hisFullVer.startsWith("0.9.")) {
                if (!hisFullVer.startsWith("1."))
                    addPoints(points, h, POINTS_BAD_VERSION, "Strange version " + DataHelper.escapeHTML(hisFullVer));
                // TODO do the math once we hit version 1.0.0
                continue;
            }
            String hisVer = hisFullVer.substring(4);
            dot = hisVer.indexOf('.');
            if (dot > 0)
                hisVer = hisVer.substring(0, dot);
            int hisMinor;
            try {
                hisMinor = Integer.parseInt(hisVer);
            } catch (NumberFormatException nfe) { continue; }
            int howOld = minor - hisMinor;
            if (howOld < 3)
                continue;
            addPoints(points, h, howOld * VERSION_FACTOR, howOld + " versions behind: " + DataHelper.escapeHTML(hisFullVer));
        }
    }

    /**
     *  @param usName HTML escaped
     *  @param ris will be re-sorted in place
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    public void calculateRouterInfo(Hash us, String usName,
                                     List<RouterInfo> ris, Map<Hash, Points> points) {
        Collections.sort(ris, new RouterInfoRoutingKeyComparator(us));
        int count = Math.min(MAX, ris.size());
        for (int i = 0; i < count; i++) {
            RouterInfo ri = ris.get(i);
            // don't do distance calculation for non-floodfills
            if (!ri.getCapabilities().contains("f"))
                continue;
            BigInteger bidist = HashDistance.getDistance(us, ri.getHash());
            double dist = biLog2(bidist);
            double point = MIN_CLOSE - dist;
            if (point <= 0)
                break;
            point *= OUR_KEY_FACTOR;
            addPoints(points, ri.getHash(), point, "Very close (" + fmt.format(dist) + ") to our key " + usName + ": " + us.toBase64());
        }
    }

    /**
     * For debugging
     * http://forums.sun.com/thread.jspa?threadID=597652
     * @since 0.7.14
     */
    private static double biLog2(BigInteger a) {
        return Util.biLog2(a);
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    private String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }
}
