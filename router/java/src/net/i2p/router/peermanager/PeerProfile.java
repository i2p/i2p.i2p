package net.i2p.router.peermanager;

import java.io.File;
import java.text.DecimalFormat;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Copied from http://www.i2p2.i2p/how_peerselection.html
 *
 * See also main() below for additional commentary by zzz.
 *
 * Currently, there is no 'ejection' strategy to get rid of the profiles for peers that
 * are no longer active (or when the network consists of thousands of peers, to get rid
 * of peers that are performing poorly). However, the size of each profile is fairly small,
 * and is unrelated to how much data is collected about the peer, so that a router can
 * keep a few thousand active peer profiles before the overhead becomes a serious concern.
 * Once it becomes necessary, we can simply compact the poorly performing profiles
 * (keeping only the most basic data) and maintain hundreds of thousands of profiles
 * in memory. Beyond that size, we can simply eject the peers (e.g. keeping the best 100,000).
 */

public class PeerProfile {
    private final Log _log;
    private final RouterContext _context;
    // whoozaat?
    private final Hash _peer;
    // general peer stats
    private long _firstHeardAbout;
    private long _lastHeardAbout;
    private long _lastSentToSuccessfully;
    private long _lastFailedSend;
    private long _lastHeardFrom;
    private double _tunnelTestResponseTimeAvg;
    // periodic rates
    //private RateStat _sendSuccessSize = null;
    //private RateStat _receiveSize = null;
    private RateStat _dbResponseTime = null;
    private RateStat _tunnelCreateResponseTime = null;
    private RateStat _tunnelTestResponseTime = null;
    private RateStat _dbIntroduction = null;
    // calculation bonuses
    private long _speedBonus;
    private long _capacityBonus;
    private long _integrationBonus;
    // calculation values
    private double _speedValue;
    private double _capacityValue;
    private double _integrationValue;
    private boolean _isFailing;
    // good vs bad behavior
    private TunnelHistory _tunnelHistory;
    private DBHistory _dbHistory;
    // does this peer profile contain expanded data, or just the basics?
    private boolean _expanded;
    private boolean _expandedDB;
    private int _consecutiveShitlists;
    
    public PeerProfile(RouterContext context, Hash peer) {
        this(context, peer, true);
    }

    /**
     *  @param expand must be true (see below)
     */
    public PeerProfile(RouterContext context, Hash peer, boolean expand) {
        _context = context;
        _log = context.logManager().getLog(PeerProfile.class);
        _peer = peer;
        // this is always true, and there are several places in the router that will NPE
        // if it is false, so all need to be fixed before we can have non-expanded profiles
        if (expand)
            expandProfile();
    }
    
    /** what peer is being profiled */
    public Hash getPeer() { return _peer; }
    
    /**
     * are we keeping an expanded profile on the peer, or just the bare minimum.
     * If we aren't keeping the expanded profile, all of the rates as well as the
     * TunnelHistory and DBHistory will not be available.
     *
     */
    public boolean getIsExpanded() { return _expanded; }
    public boolean getIsExpandedDB() { return _expandedDB; }
    
    public int incrementShitlists() { return _consecutiveShitlists++; }
    public void unshitlist() { _consecutiveShitlists = 0; }
    
    /**
     * Is this peer active at the moment (sending/receiving messages within the last
     * 5 minutes)
     */
    public boolean getIsActive() {
        return getIsActive(5*60*1000);
    }
    
    /**
     * Is this peer active at the moment (sending/receiving messages within the 
     * given period?)
     * Also mark active if it is connected, as this will tend to encourage use
     * of already-connected peers.
     *
     * Note: this appears to be the only use for these two RateStats.
     *
     * Update: Rewritten so we can get rid of the two RateStats.
     *         This also helps by not having it depend on coalesce boundaries.
     *
     * @param period must be one of the periods in the RateStat constructors below
     *        (5*60*1000 or 60*60*1000)
     */
    public boolean getIsActive(long period) {
        //if ( (getSendSuccessSize().getRate(period).getCurrentEventCount() > 0) ||
        //     (getSendSuccessSize().getRate(period).getLastEventCount() > 0) ||
        //     (getReceiveSize().getRate(period).getCurrentEventCount() > 0) ||
        //     (getReceiveSize().getRate(period).getLastEventCount() > 0) ||
        //     _context.commSystem().isEstablished(_peer) )
        //    return true;
        //else
        //    return false;
        long before = _context.clock().now() - period;
        return getLastHeardFrom() < before ||
               getLastSendSuccessful() < before ||
             _context.commSystem().isEstablished(_peer);
    }
    
    
    /**
     *  When did we first hear about this peer?
     *  Currently unused, candidate for removal.
     */
    public long getFirstHeardAbout() { return _firstHeardAbout; }
    public void setFirstHeardAbout(long when) { _firstHeardAbout = when; }
    
    /** when did we last hear about this peer? */
    public long getLastHeardAbout() { return _lastHeardAbout; }
    public void setLastHeardAbout(long when) { _lastHeardAbout = when; }
    
    /** when did we last send to this peer successfully? */
    public long getLastSendSuccessful() { return _lastSentToSuccessfully; }
    public void setLastSendSuccessful(long when) { _lastSentToSuccessfully = when; }
    
    /** when did we last have a problem sending to this peer? */
    public long getLastSendFailed() { return _lastFailedSend; }
    public void setLastSendFailed(long when) { _lastFailedSend = when; }
    
    /** when did we last hear from the peer? */
    public long getLastHeardFrom() { return _lastHeardFrom; }
    public void setLastHeardFrom(long when) { _lastHeardFrom = when; }
    
    /** history of tunnel activity with the peer
        Warning - may return null if !getIsExpanded() */
    public TunnelHistory getTunnelHistory() { return _tunnelHistory; }
    public void setTunnelHistory(TunnelHistory history) { _tunnelHistory = history; }
    
    /** history of db activity with the peer
        Warning - may return null if !getIsExpandedDB() */
    public DBHistory getDBHistory() { return _dbHistory; }
    public void setDBHistory(DBHistory hist) { _dbHistory = hist; }
    
    /** how large successfully sent messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    //public RateStat getSendSuccessSize() { return _sendSuccessSize; }
    /** how large received messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    //public RateStat getReceiveSize() { return _receiveSize; }
    /** how long it takes to get a db response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period
        Warning - may return null if !getIsExpandedDB() */
    public RateStat getDbResponseTime() { return _dbResponseTime; }
    /** how long it takes to get a tunnel create response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period
        Warning - may return null if !getIsExpanded() */
    public RateStat getTunnelCreateResponseTime() { return _tunnelCreateResponseTime; }
    /** how long it takes to successfully test a tunnel this peer participates in (in milliseconds), calculated over a 10 minute, 1 hour, and 1 day period
        Warning - may return null if !getIsExpanded() */
    public RateStat getTunnelTestResponseTime() { return _tunnelTestResponseTime; }
    /** how many new peers we get from dbSearchReplyMessages or dbStore messages, calculated over a 1 hour, 1 day, and 1 week period
        Warning - may return null if !getIsExpandedDB() */
    public RateStat getDbIntroduction() { return _dbIntroduction; }
    
    /**
     * extra factor added to the speed ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks speed.  Negative values are
     * penalties
     */
    public long getSpeedBonus() { return _speedBonus; }
    public void setSpeedBonus(long bonus) { _speedBonus = bonus; }
    
    /**
     * extra factor added to the capacity ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks capacity.  Negative values are
     * penalties
     */
    public long getCapacityBonus() { return _capacityBonus; }
    public void setCapacityBonus(long bonus) { _capacityBonus = bonus; }
    
    /**
     * extra factor added to the integration ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks integration.  Negative values are
     * penalties
     */
    public long getIntegrationBonus() { return _integrationBonus; }
    public void setIntegrationBonus(long bonus) { _integrationBonus = bonus; }
    
    /**
     * How fast is the peer, taking into consideration both throughput and latency.
     * This may even be made to take into consideration current rates vs. estimated
     * (or measured) max rates, allowing this speed to reflect the speed /available/.
     *
     */
    public double getSpeedValue() { return _speedValue; }
    /**
     * How many tunnels do we think this peer can handle over the next hour? 
     *
     */
    public double getCapacityValue() { return _capacityValue; }
    /**
     * How well integrated into the network is this peer (as measured by how much they've
     * told us that we didn't already know).  Higher numbers means better integrated
     *
     */
    public double getIntegrationValue() { return _integrationValue; }
    /**
     * is this peer actively failing (aka not worth touching)?
     * deprecated - unused - always false
     */
    public boolean getIsFailing() { return _isFailing; }

    public double getTunnelTestTimeAverage() { return _tunnelTestResponseTimeAvg; }
    void setTunnelTestTimeAverage(double avg) { _tunnelTestResponseTimeAvg = avg; }
    
    void updateTunnelTestTimeAverage(long ms) {
        if (_tunnelTestResponseTimeAvg <= 0) 
            _tunnelTestResponseTimeAvg = 30*1000; // should we instead start at $ms?
        
        // weighted since we want to let the average grow quickly and shrink slowly
        if (ms < _tunnelTestResponseTimeAvg)
            _tunnelTestResponseTimeAvg = 0.95*_tunnelTestResponseTimeAvg + .05*(double)ms;
        else
            _tunnelTestResponseTimeAvg = 0.75*_tunnelTestResponseTimeAvg + .25*(double)ms;
        
        if ( (_peer != null) && (_log.shouldLog(Log.INFO)) )
            _log.info("Updating tunnel test time for " + _peer.toBase64().substring(0,6) 
                      + " to " + _tunnelTestResponseTimeAvg + " via " + ms);
    }

    /** keep track of the fastest 3 throughputs */
    private static final int THROUGHPUT_COUNT = 3;
    /** 
     * fastest 1 minute throughput, in bytes per minute, ordered with fastest
     * first.  this is not synchronized, as we don't *need* perfection, and we only
     * reorder/insert values on coallesce
     */
    private final double _peakThroughput[] = new double[THROUGHPUT_COUNT];
    private volatile long _peakThroughputCurrentTotal;
    public double getPeakThroughputKBps() { 
        double rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakThroughput[i];
        rv /= (60d*1024d*(double)THROUGHPUT_COUNT);
        return rv;
    }
    public void setPeakThroughputKBps(double kBps) {
        _peakThroughput[0] = kBps*60*1024;
        //for (int i = 0; i < THROUGHPUT_COUNT; i++)
        //    _peakThroughput[i] = kBps*60;
    }
    void dataPushed(int size) { _peakThroughputCurrentTotal += size; }
    
    private final double _peakTunnelThroughput[] = new double[THROUGHPUT_COUNT];
    /** the tunnel pushed that much data in its lifetime */
    void tunnelDataTransferred(long tunnelByteLifetime) {
        double lowPeak = _peakTunnelThroughput[THROUGHPUT_COUNT-1];
        if (tunnelByteLifetime > lowPeak) {
            synchronized (_peakTunnelThroughput) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (tunnelByteLifetime > _peakTunnelThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                           _peakTunnelThroughput[j] = _peakTunnelThroughput[j-1];
                        _peakTunnelThroughput[i] = tunnelByteLifetime;
                        break;
                    }
                }
            }
        }
    }
    public double getPeakTunnelThroughputKBps() { 
        double rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakTunnelThroughput[i];
        rv /= (10d*60d*1024d*(double)THROUGHPUT_COUNT);
        return rv;
    }
    public void setPeakTunnelThroughputKBps(double kBps) {
        _peakTunnelThroughput[0] = kBps*60d*10d*1024d;
    }
    
    /** total number of bytes pushed through a single tunnel in a 1 minute period */
    private final double _peakTunnel1mThroughput[] = new double[THROUGHPUT_COUNT];
    /** the tunnel pushed that much data in a 1 minute period */
    void dataPushed1m(int size) {
        double lowPeak = _peakTunnel1mThroughput[THROUGHPUT_COUNT-1];
        if (size > lowPeak) {
            synchronized (_peakTunnel1mThroughput) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (size > _peakTunnel1mThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                           _peakTunnel1mThroughput[j] = _peakTunnel1mThroughput[j-1];
                        _peakTunnel1mThroughput[i] = size;
                        break;
                    }
                }
            }
            
            if (_log.shouldLog(Log.DEBUG) ) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Updating 1m throughput after ").append(size).append(" to ");
                for (int i = 0; i < THROUGHPUT_COUNT; i++)
                    buf.append(_peakTunnel1mThroughput[i]).append(',');
                buf.append(" for ").append(_peer.toBase64());
                _log.debug(buf.toString());
            }
        }
    }
    /**
     * @return the average of the three fastest one-minute data transfers, on a per-tunnel basis,
     *         through this peer. Ever. Except that the peak values are cut in half
     *         once a day by coalesceThroughput(). This seems way too seldom.
     */
    public double getPeakTunnel1mThroughputKBps() { 
        double rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakTunnel1mThroughput[i];
        rv /= (60d*1024d*(double)THROUGHPUT_COUNT);
        return rv;
    }
    public void setPeakTunnel1mThroughputKBps(double kBps) {
        _peakTunnel1mThroughput[0] = kBps*60*1024;
    }
    
    /**
     * when the given peer is performing so poorly that we don't want to bother keeping
     * extensive stats on them, call this to discard excess data points.  Specifically,
     * this drops the rates, the tunnelHistory, and the dbHistory.
     *
     * UNUSED for now, will cause NPEs elsewhere
     */
/*****
    public void shrinkProfile() {
        //_sendSuccessSize = null;
        //_receiveSize = null;
        _dbResponseTime = null;
        _tunnelCreateResponseTime = null;
        _tunnelTestResponseTime = null;
        _dbIntroduction = null;
        _tunnelHistory = null;
        _dbHistory = null;
        
        _expanded = false;
        _expandedDB = false;
    }
******/
    
    /**
     * When the given peer is performing well enough that we want to keep detailed
     * stats on them again, call this to set up the info we dropped during shrinkProfile.
     * This will not however overwrite any existing data, so it can be safely called
     * repeatedly
     *
     */
    public void expandProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        //if (_sendSuccessSize == null)
        //    _sendSuccessSize = new RateStat("sendSuccessSize", "How large successfully sent messages are", group, new long[] { 5*60*1000l, 60*60*1000l });
        //if (_receiveSize == null)
        //    _receiveSize = new RateStat("receiveSize", "How large received messages are", group, new long[] { 5*60*1000l, 60*60*1000l } );
        if (_tunnelCreateResponseTime == null)
            _tunnelCreateResponseTime = new RateStat("tunnelCreateResponseTime", "how long it takes to get a tunnel create response from the peer (in milliseconds)", group, new long[] { 10*60*1000l, 30*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_tunnelTestResponseTime == null)
            _tunnelTestResponseTime = new RateStat("tunnelTestResponseTime", "how long it takes to successfully test a tunnel this peer participates in (in milliseconds)", group, new long[] { 10*60*1000l, 30*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 } );

        if (_tunnelHistory == null)
            _tunnelHistory = new TunnelHistory(_context, group);

        //_sendSuccessSize.setStatLog(_context.statManager().getStatLog());
        //_receiveSize.setStatLog(_context.statManager().getStatLog());
        _tunnelCreateResponseTime.setStatLog(_context.statManager().getStatLog());
        _tunnelTestResponseTime.setStatLog(_context.statManager().getStatLog());
        _expanded = true;
    }

    /**
     * For floodfills
     */
    public synchronized void expandDBProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        if (_dbResponseTime == null)
            _dbResponseTime = new RateStat("dbResponseTime", "how long it takes to get a db response from the peer (in milliseconds)", group, new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_dbIntroduction == null)
            _dbIntroduction = new RateStat("dbIntroduction", "how many new peers we get from dbSearchReplyMessages or dbStore messages", group, new long[] { 60*60*1000l, 6*60*60*1000l, 24*60*60*1000l });

        if (_dbHistory == null)
            _dbHistory = new DBHistory(_context, group);

        _dbResponseTime.setStatLog(_context.statManager().getStatLog());
        _dbIntroduction.setStatLog(_context.statManager().getStatLog());
        _expandedDB = true;
    }

    /** once a day, on average, cut the measured throughtput values in half */
    /** let's try once an hour times 3/4 */
    private static final int DROP_PERIOD_MINUTES = 60;
    private static final double DEGRADE_FACTOR = 0.75;
    private long _lastCoalesceDate = System.currentTimeMillis();
    private void coalesceThroughput() {
        long now = System.currentTimeMillis();
        long measuredPeriod = now - _lastCoalesceDate;
        if (measuredPeriod >= 60*1000) {
            long tot = _peakThroughputCurrentTotal;
            double lowPeak = _peakThroughput[THROUGHPUT_COUNT-1];
            if (tot > lowPeak) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    if (tot > _peakThroughput[i]) {
                        for (int j = THROUGHPUT_COUNT-1; j > i; j--)
                            _peakThroughput[j] = _peakThroughput[j-1];
                        _peakThroughput[i] = tot;
                        break;
                    }
                }
            } else {
                if (_context.random().nextInt(DROP_PERIOD_MINUTES*2) <= 0) {
                    for (int i = 0; i < THROUGHPUT_COUNT; i++)
                        _peakThroughput[i] *= DEGRADE_FACTOR;
                }
            }
            
            // we degrade the tunnel throughput here too, regardless of the current
            // activity
            if (_context.random().nextInt(DROP_PERIOD_MINUTES*2) <= 0) {
                for (int i = 0; i < THROUGHPUT_COUNT; i++) {
                    _peakTunnelThroughput[i] *= DEGRADE_FACTOR;
                    _peakTunnel1mThroughput[i] *= DEGRADE_FACTOR;
                }
            }
            _peakThroughputCurrentTotal = 0;
            _lastCoalesceDate = now;
        }
    }
    
    /** update the stats and rates (this should be called once a minute) */
    public void coalesceStats() {
        if (!_expanded) return;
        //_receiveSize.coalesceStats();
        //_sendSuccessSize.coalesceStats();
        _tunnelCreateResponseTime.coalesceStats();
        _tunnelTestResponseTime.coalesceStats();
        _tunnelHistory.coalesceStats();
        if (_expandedDB) {
            _dbIntroduction.coalesceStats();
            _dbResponseTime.coalesceStats();
            _dbHistory.coalesceStats();
        }
        
        coalesceThroughput();
        
        _speedValue = calculateSpeed();
        _capacityValue = calculateCapacity();
        _integrationValue = calculateIntegration();
        _isFailing = calculateIsFailing();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Coalesced: speed [" + _speedValue + "] capacity [" + _capacityValue + "] integration [" + _integrationValue + "] failing? [" + _isFailing + "]");
    }
    
    private double calculateSpeed() { return SpeedCalculator.calc(this); }
    private double calculateCapacity() { return CapacityCalculator.calc(this); }
    private double calculateIntegration() { return IntegrationCalculator.calc(this); }
    /** deprecated - unused - always false */
    private boolean calculateIsFailing() { return false; }
    /** deprecated - unused - always false */
    void setIsFailing(boolean val) { _isFailing = val; }
    
    @Override
    public int hashCode() { return (_peer == null ? 0 : _peer.hashCode()); }

    @Override
    public boolean equals(Object obj) {
        if (obj == null ||
            (!(obj instanceof PeerProfile)) ||
            _peer == null)
            return false;
        PeerProfile prof = (PeerProfile)obj;
        return _peer.equals(prof.getPeer());
    }

    @Override
    public String toString() { return "Profile: " + getPeer().toBase64(); }
    
    /**
     * New measurement is 12KB per expanded profile. (2009-03 zzz)
     * And nowhere in the code is shrinkProfile() called so
     * the size of compact profiles doesn't matter right now.
     * This is far bigger than the NetDB entry, which is only about 1.5KB
     * now that most of the stats have been removed.
     *
     * The biggest user in the profile is the Rates. (144 bytes per according to jhat).
     * PeerProfile:     9 RateStats, 3-5 Rates each - 35 total
     * DBHistory:       2 RateStats, 3 each -          6 total
     * TunnelHistory:   4 RateStats, 5 each -         20 total
     *                ---                            ---------
     *                 15                             61 total  
     *                *60 bytes                     *144 bytes
     *                ---                            ---------
     *                900 bytes                     8784 bytes
     *
     * The RateStat itself is 32 bytes and the Rate[] is 28 so that adds
     * about 1KB.
     *
     * So two obvious things to do are cut out some of the Rates,
     * and call shrinkProfile().
     *
     * Obsolete calculation follows:
     *
     * Calculate the memory consumption of profiles.  Measured to be ~3739 bytes
     * for an expanded profile, and ~212 bytes for a compacted one.
     *
     */
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
        testProfileSize(ctx, 100, 0); // 560KB
        testProfileSize(ctx, 1000, 0); // 3.9MB
        testProfileSize(ctx, 10000, 0); // 37MB
        testProfileSize(ctx, 0, 10000); // 2.2MB
        testProfileSize(ctx, 0, 100000); // 21MB
        testProfileSize(ctx, 0, 300000);  // 63MB
    }
    
    /**
     * Read in all of the profiles specified and print out 
     * their calculated values.  Usage: <pre>
     *  PeerProfile [filename]*
     * </pre>
     */
    public static void main2(String args[]) {
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
        DecimalFormat fmt = new DecimalFormat("0,000.0");
        fmt.setPositivePrefix("+");
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        try { Thread.sleep(5*1000); } catch (InterruptedException e) {}
        StringBuilder buf = new StringBuilder(1024);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new File(args[i]));
            if (profile == null) {
                buf.append("Could not load profile ").append(args[i]).append('\n');
                continue;
            }
            //profile.coalesceStats();
            buf.append("Peer " + profile.getPeer().toBase64() 
                       + ":\t Speed:\t" + fmt.format(profile.calculateSpeed())
                       + " Capacity:\t" + fmt.format(profile.calculateCapacity())
                       + " Integration:\t" + fmt.format(profile.calculateIntegration())
                       + " Active?\t" + profile.getIsActive() 
                       + " Failing?\t" + profile.calculateIsFailing()
                       + '\n');
        }
        try { Thread.sleep(5*1000); } catch (InterruptedException e) {}
        System.out.println(buf.toString());
    }
    
    private static void testProfileSize(RouterContext ctx, int numExpanded, int numCompact) {
        Runtime.getRuntime().gc();
        PeerProfile profs[] = new PeerProfile[numExpanded];
        PeerProfile profsCompact[] = new PeerProfile[numCompact];
        long used = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long usedPer = used / (numExpanded+numCompact);
        System.out.println(numExpanded + "/" + numCompact + ": create array - Used: " + used + " bytes (or " + usedPer + " bytes per array entry)");
        
        int i = 0;
        int j = 0;
        try {
            for (; i < numExpanded; i++)
                profs[i] = new PeerProfile(ctx, new Hash(new byte[Hash.HASH_LENGTH]));
        } catch (OutOfMemoryError oom) {
            profs = null;
            profsCompact = null;
            Runtime.getRuntime().gc();
            System.out.println("Ran out of memory when creating profile " + i);
            return;
        }
        try {
            for (; i < numCompact; i++)
                profsCompact[i] = new PeerProfile(ctx, new Hash(new byte[Hash.HASH_LENGTH]), false);
        } catch (OutOfMemoryError oom) {
            profs = null;
            profsCompact = null;
            Runtime.getRuntime().gc();
            System.out.println("Ran out of memory when creating compacted profile " + i);
            return;
        }
        
        Runtime.getRuntime().gc();
        long usedObjects = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        usedPer = usedObjects / (numExpanded+numCompact);
        System.out.println(numExpanded + "/" + numCompact + ": create objects - Used: " + usedObjects + " bytes (or " + usedPer + " bytes per profile)");
        profs = null;
        profsCompact = null;
        Runtime.getRuntime().gc();
    }
}
