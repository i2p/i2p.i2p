package net.i2p.router.peermanager;

import java.io.File;
import java.text.DecimalFormat;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

public class PeerProfile {
    private Log _log;
    private RouterContext _context;
    // whoozaat?
    private Hash _peer;
    // general peer stats
    private long _firstHeardAbout;
    private long _lastHeardAbout;
    private long _lastSentToSuccessfully;
    private long _lastFailedSend;
    private long _lastHeardFrom;
    private double _tunnelTestResponseTimeAvg;
    // periodic rates
    private RateStat _sendSuccessSize = null;
    private RateStat _sendFailureSize = null;
    private RateStat _receiveSize = null;
    private RateStat _dbResponseTime = null;
    private RateStat _tunnelCreateResponseTime = null;
    private RateStat _tunnelTestResponseTime = null;
    private RateStat _tunnelTestResponseTimeSlow = null;
    private RateStat _commError = null;
    private RateStat _dbIntroduction = null;
    // calculation bonuses
    private long _speedBonus;
    private long _reliabilityBonus;
    private long _capacityBonus;
    private long _integrationBonus;
    // calculation values
    private double _speedValue;
    private double _reliabilityValue;
    private double _capacityValue;
    private double _integrationValue;
    private double _oldSpeedValue;
    private boolean _isFailing;
    // good vs bad behavior
    private TunnelHistory _tunnelHistory;
    private DBHistory _dbHistory;
    // does this peer profile contain expanded data, or just the basics?
    private boolean _expanded;
    private int _consecutiveShitlists;
    
    public PeerProfile(RouterContext context, Hash peer) {
        this(context, peer, true);
    }
    public PeerProfile(RouterContext context, Hash peer, boolean expand) {
        _context = context;
        _log = context.logManager().getLog(PeerProfile.class);
        _expanded = false;
        _speedValue = 0;
        _reliabilityValue = 0;
        _capacityValue = 0;
        _integrationValue = 0;
        _isFailing = false;
        _consecutiveShitlists = 0;
        _tunnelTestResponseTimeAvg = 0.0d;
        _peer = peer;
        if (expand)
            expandProfile();
    }
    
    /** what peer is being profiled */
    public Hash getPeer() { return _peer; }
    public void setPeer(Hash peer) { _peer = peer; }
    
    /**
     * are we keeping an expanded profile on the peer, or just the bare minimum.
     * If we aren't keeping the expanded profile, all of the rates as well as the
     * TunnelHistory and DBHistory will not be available.
     *
     */
    public boolean getIsExpanded() { return _expanded; }
    
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
     */
    public boolean getIsActive(long period) {
        if ( (getSendSuccessSize().getRate(period).getCurrentEventCount() > 0) ||
             (getSendSuccessSize().getRate(period).getLastEventCount() > 0) ||
             (getReceiveSize().getRate(period).getCurrentEventCount() > 0) ||
             (getReceiveSize().getRate(period).getLastEventCount() > 0) )
            return true;
        else
            return false;
    }
    
    
    /** when did we first hear about this peer? */
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
    
    /** history of tunnel activity with the peer */
    public TunnelHistory getTunnelHistory() { return _tunnelHistory; }
    public void setTunnelHistory(TunnelHistory history) { _tunnelHistory = history; }
    
    /** history of db activity with the peer */
    public DBHistory getDBHistory() { return _dbHistory; }
    public void setDBHistory(DBHistory hist) { _dbHistory = hist; }
    
    /** how large successfully sent messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getSendSuccessSize() { return _sendSuccessSize; }
    /** how large messages that could not be sent were, calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getSendFailureSize() { return _sendFailureSize; }
    /** how large received messages are, calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getReceiveSize() { return _receiveSize; }
    /** how long it takes to get a db response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getDbResponseTime() { return _dbResponseTime; }
    /** how long it takes to get a tunnel create response from the peer (in milliseconds), calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getTunnelCreateResponseTime() { return _tunnelCreateResponseTime; }
    /** how long it takes to successfully test a tunnel this peer participates in (in milliseconds), calculated over a 10 minute, 1 hour, and 1 day period */
    public RateStat getTunnelTestResponseTime() { return _tunnelTestResponseTime; }
    /** how long it takes to successfully test the peer (in milliseconds) when the time exceeds 5s */
    public RateStat getTunnelTestResponseTimeSlow() { return _tunnelTestResponseTimeSlow; }
    /** how long between communication errors with the peer (disconnection, etc), calculated over a 1 minute, 1 hour, and 1 day period */
    public RateStat getCommError() { return _commError; }
    /** how many new peers we get from dbSearchReplyMessages or dbStore messages, calculated over a 1 hour, 1 day, and 1 week period */
    public RateStat getDbIntroduction() { return _dbIntroduction; }
    
    /**
     * extra factor added to the speed ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks speed.  Negative values are
     * penalties
     */
    public long getSpeedBonus() { return _speedBonus; }
    public void setSpeedBonus(long bonus) { _speedBonus = bonus; }
    
    /**
     * extra factor added to the reliability ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks reliability.  Negative values are
     * penalties
     */
    public long getReliabilityBonus() { return _reliabilityBonus; }
    public void setReliabilityBonus(long bonus) { _reliabilityBonus = bonus; }
    
    /**
     * extra factor added to the capacity ranking - this can be updated in the profile
     * written to disk to affect how the algorithm ranks capacity.  Negative values are
     * penalties
     */
    public double getCapacityBonus() { return _capacityBonus; }
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
    public double getOldSpeedValue() { return _oldSpeedValue; }
    /**
     * How likely are they to stay up and pass on messages over the next few minutes.
     * Positive numbers means more likely, negative numbers means its probably not
     * even worth trying.
     *
     */
    public double getReliabilityValue() { return _reliabilityValue; }
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
    
    /**
     * when the given peer is performing so poorly that we don't want to bother keeping
     * extensive stats on them, call this to discard excess data points.  Specifically,
     * this drops the rates, the tunnelHistory, and the dbHistory.
     *
     */
    public void shrinkProfile() {
        _sendSuccessSize = null;
        _sendFailureSize = null;
        _receiveSize = null;
        _dbResponseTime = null;
        _tunnelCreateResponseTime = null;
        _tunnelTestResponseTime = null;
        _tunnelTestResponseTimeSlow = null;
        _commError = null;
        _dbIntroduction = null;
        _tunnelHistory = null;
        _dbHistory = null;
        
        _expanded = false;
    }
    
    /**
     * When the given peer is performing well enough that we want to keep detailed
     * stats on them again, call this to set up the info we dropped during shrinkProfile.
     * This will not however overwrite any existing data, so it can be safely called
     * repeatedly
     *
     */
    public void expandProfile() {
        String group = (null == _peer ? "profileUnknown" : _peer.toBase64().substring(0,6));
        if (_sendSuccessSize == null)
            _sendSuccessSize = new RateStat("sendSuccessSize", "How large successfully sent messages are", group, new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        if (_sendFailureSize == null)
            _sendFailureSize = new RateStat("sendFailureSize", "How large messages that could not be sent were", group, new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_receiveSize == null)
            _receiveSize = new RateStat("receiveSize", "How large received messages are", group, new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_dbResponseTime == null)
            _dbResponseTime = new RateStat("dbResponseTime", "how long it takes to get a db response from the peer (in milliseconds)", group, new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_tunnelCreateResponseTime == null)
            _tunnelCreateResponseTime = new RateStat("tunnelCreateResponseTime", "how long it takes to get a tunnel create response from the peer (in milliseconds)", group, new long[] { 10*60*1000l, 30*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_tunnelTestResponseTime == null)
            _tunnelTestResponseTime = new RateStat("tunnelTestResponseTime", "how long it takes to successfully test a tunnel this peer participates in (in milliseconds)", group, new long[] { 10*60*1000l, 30*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 } );
        if (_tunnelTestResponseTimeSlow == null)
            _tunnelTestResponseTimeSlow = new RateStat("tunnelTestResponseTimeSlow", "how long it takes to successfully test a peer when the time exceeds 5s", group, new long[] { 10*60*1000l, 30*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l, });
        if (_commError == null)
            _commError = new RateStat("commErrorRate", "how long between communication errors with the peer (e.g. disconnection)", group, new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000 } );
        if (_dbIntroduction == null)
            _dbIntroduction = new RateStat("dbIntroduction", "how many new peers we get from dbSearchReplyMessages or dbStore messages", group, new long[] { 60*60*1000l, 24*60*60*1000l, 7*24*60*60*1000l });

        if (_tunnelHistory == null)
            _tunnelHistory = new TunnelHistory(_context, group);
        if (_dbHistory == null)
            _dbHistory = new DBHistory(_context, group);

        _sendSuccessSize.setStatLog(_context.statManager().getStatLog());
        _sendFailureSize.setStatLog(_context.statManager().getStatLog());
        _receiveSize.setStatLog(_context.statManager().getStatLog());
        _dbResponseTime.setStatLog(_context.statManager().getStatLog());
        _tunnelCreateResponseTime.setStatLog(_context.statManager().getStatLog());
        _tunnelTestResponseTime.setStatLog(_context.statManager().getStatLog());
        _tunnelTestResponseTimeSlow.setStatLog(_context.statManager().getStatLog());
        _commError.setStatLog(_context.statManager().getStatLog());
        _dbIntroduction.setStatLog(_context.statManager().getStatLog());
        _expanded = true;
    }
    
    /** update the stats and rates (this should be called once a minute) */
    public void coalesceStats() {
        if (!_expanded) return;
        _commError.coalesceStats();
        _dbIntroduction.coalesceStats();
        _dbResponseTime.coalesceStats();
        _receiveSize.coalesceStats();
        _sendFailureSize.coalesceStats();
        _sendSuccessSize.coalesceStats();
        _tunnelCreateResponseTime.coalesceStats();
        _tunnelTestResponseTime.coalesceStats();
        _tunnelTestResponseTimeSlow.coalesceStats();
        _dbHistory.coalesceStats();
        _tunnelHistory.coalesceStats();
        
        _speedValue = calculateSpeed();
        _oldSpeedValue = calculateOldSpeed();
        _reliabilityValue = calculateReliability();
        _capacityValue = calculateCapacity();
        _integrationValue = calculateIntegration();
        _isFailing = calculateIsFailing();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Coalesced: speed [" + _speedValue + "] reliability [" + _reliabilityValue + "] capacity [" + _capacityValue + "] integration [" + _integrationValue + "] failing? [" + _isFailing + "]");
    }
    
    private double calculateSpeed() { return _context.speedCalculator().calc(this); }
    private double calculateOldSpeed() { return _context.oldSpeedCalculator().calc(this); }
    private double calculateReliability() { return _context.reliabilityCalculator().calc(this); }
    private double calculateCapacity() { return _context.capacityCalculator().calc(this); }
    private double calculateIntegration() { return _context.integrationCalculator().calc(this); }
    private boolean calculateIsFailing() { return _context.isFailingCalculator().calcBoolean(this); }
    void setIsFailing(boolean val) { _isFailing = val; }
    
    public int hashCode() { return (_peer == null ? 0 : _peer.hashCode()); }
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj.getClass() != PeerProfile.class) return false;
        if (_peer == null) return false;
        PeerProfile prof = (PeerProfile)obj;
        return _peer.equals(prof.getPeer());
    }
    public String toString() { return "Profile: " + getPeer().toBase64(); }
    
    /**
     * Calculate the memory consumption of profiles.  Measured to be ~3739 bytes
     * for an expanded profile, and ~212 bytes for a compacted one.
     *
     */
    public static void main2(String args[]) {
        RouterContext ctx = new RouterContext(null);
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
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
        DecimalFormat fmt = new DecimalFormat("0,000.0");
        fmt.setPositivePrefix("+");
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        try { Thread.sleep(5*1000); } catch (InterruptedException e) {}
        StringBuffer buf = new StringBuffer(1024);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new File(args[i]));
            if (profile == null) {
                buf.append("Could not load profile ").append(args[i]).append('\n');
                continue;
            }
            //profile.coalesceStats();
            buf.append("Peer " + profile.getPeer().toBase64() 
                       + ":\t Speed:\t" + fmt.format(profile.calculateSpeed())
                       + " Reliability:\t" + fmt.format(profile.calculateReliability())
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
