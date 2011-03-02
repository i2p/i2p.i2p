package net.i2p.stat;

import java.text.Collator;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/** 
 * Coordinate the management of various frequencies and rates within I2P components,
 * both allowing central update and retrieval, as well as distributed creation and 
 * use.  This does not provide any persistence, but the data structures exposed can be
 * read and updated to manage the complete state.
 * 
 */
public class StatManager {
    private Log _log;
    private I2PAppContext _context;

    /** stat name to FrequencyStat */
    private final ConcurrentHashMap<String, FrequencyStat> _frequencyStats;
    /** stat name to RateStat */
    private final ConcurrentHashMap<String, RateStat> _rateStats;
    /** may be null */
    private StatLog _statLog;

    /**
     *  Comma-separated stats or * for all.
     *  This property must be set at startup, or
     *  logging is disabled.
     */
    public static final String PROP_STAT_FILTER = "stat.logFilters";
    public static final String PROP_STAT_FILE = "stat.logFile";
    public static final String DEFAULT_STAT_FILE = "stats.log";
    /** default true */
    public static final String PROP_STAT_FULL = "stat.full";
    public static final String PROP_STAT_REQUIRED = "stat.required";
    /**
     * These are all the stats published in netDb, plus those required for the operation of
     * the router (many in RouterThrottleImpl), plus those that are on graphs.jsp by default,
     * plus those used on the summary bar (SummaryHelper.java).
     * Wildcard ('*') allowed at end of stat only.
     * Ignore all the rest of the stats unless stat.full=true.
     */
    public static final String DEFAULT_STAT_REQUIRED =
        "bw.recvRate,bw.sendBps,bw.sendRate,client.sendAckTime,clock.skew,crypto.elGamal.encrypt," +
        "jobQueue.jobLag,netDb.successTime,peer.failedLookupRate,router.fastPeers," +
        "prng.bufferFillTime,prng.bufferWaitTime,router.memoryUsed," +
        "transport.receiveMessageSize,transport.sendMessageSize,transport.sendProcessingTime," +
        "tunnel.acceptLoad,tunnel.buildRequestTime,tunnel.rejectOverloaded,tunnel.rejectTimeout," +
        "tunnel.buildClientExpire,tunnel.buildClientReject,tunnel.buildClientSuccess," +
        "tunnel.buildExploratoryExpire,tunnel.buildExploratoryReject,tunnel.buildExploratorySuccess," +
        "tunnel.buildRatio.*,tunnel.corruptMessage,tunnel.dropLoad*," +
        "tunnel.decryptRequestTime,tunnel.fragmentedDropped,tunnel.participatingMessageCount,"+
        "tunnel.participatingTunnels,tunnel.testFailedTime,tunnel.testSuccessTime," +
        "tunnel.participatingBandwidth,udp.sendPacketSize,udp.packetsRetransmitted,udp.sendException" ;
    
    /**
     * The stat manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public StatManager(I2PAppContext context) {
        _log = context.logManager().getLog(StatManager.class);
        _context = context;
        _frequencyStats = new ConcurrentHashMap(8);
        _rateStats = new ConcurrentHashMap(128);
        if (getStatFilter() != null)
            _statLog = new BufferedStatLog(context);
    }
    
    /** may be null */
    public StatLog getStatLog() { return _statLog; }
    public void setStatLog(StatLog log) { 
        _statLog = log; 
            for (Iterator<RateStat> iter = _rateStats.values().iterator(); iter.hasNext(); ) {
                RateStat rs = iter.next();
                rs.setStatLog(log);
            }
    }

    /**
     * Create a new statistic to monitor the frequency of some event.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createFrequencyStat(String name, String description, String group, long periods[]) {
        if (ignoreStat(name)) return;
        if (_frequencyStats.containsKey(name)) return;
        _frequencyStats.putIfAbsent(name, new FrequencyStat(name, description, group, periods));
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createRateStat(String name, String description, String group, long periods[]) {
        if (ignoreStat(name)) return;
            if (_rateStats.containsKey(name)) return;
            RateStat rs = new RateStat(name, description, group, periods);
            if (_statLog != null) rs.setStatLog(_statLog);
            _rateStats.putIfAbsent(name, rs);
    }

    // Hope this doesn't cause any problems with unsynchronized accesses like addRateData() ...
    public void removeRateStat(String name) {
            _rateStats.remove(name);
    }

    /** update the given frequency statistic, taking note that an event occurred (and recalculating all frequencies) */
    public void updateFrequency(String name) {
        FrequencyStat freq = _frequencyStats.get(name);
        if (freq != null) freq.eventOccurred();
    }

    /** update the given rate statistic, taking note that the given data point was received (and recalculating all rates) */
    public void addRateData(String name, long data, long eventDuration) {
        RateStat stat = _rateStats.get(name); // unsynchronized
        if (stat != null) stat.addData(data, eventDuration);
    }

    private int coalesceCounter;
    /** every this many minutes for frequencies */
    private static final int FREQ_COALESCE_RATE = 9;

    public void coalesceStats() {
        if (++coalesceCounter % FREQ_COALESCE_RATE == 0) {
                for (FrequencyStat stat : _frequencyStats.values()) {
                    if (stat != null) {
                        stat.coalesceStats();
                    }
                }
        }
            for (Iterator<RateStat> iter = _rateStats.values().iterator(); iter.hasNext();) {
                RateStat stat = iter.next();
                if (stat != null) {
                    stat.coalesceStats();
                }
            }
    }

    public FrequencyStat getFrequency(String name) {
        return _frequencyStats.get(name);
    }

    public RateStat getRate(String name) {
        return _rateStats.get(name);
    }

    public Set<String> getFrequencyNames() {
        return new HashSet(_frequencyStats.keySet());
    }

    public Set<String> getRateNames() {
        return new HashSet(_rateStats.keySet());
    }

    /** is the given stat a monitored rate? */
    public boolean isRate(String statName) {
        return _rateStats.containsKey(statName);
    }

    /** is the given stat a monitored frequency? */
    public boolean isFrequency(String statName) {
        return _frequencyStats.containsKey(statName);
    }

    /** Group name (String) to a Set of stat names, ordered alphabetically */
    public Map getStatsByGroup() {
        Map groups = new TreeMap(Collator.getInstance());
        for (Iterator<FrequencyStat> iter = _frequencyStats.values().iterator(); iter.hasNext();) {
            FrequencyStat stat = iter.next();
            if (!groups.containsKey(stat.getGroupName())) groups.put(stat.getGroupName(), new TreeSet());
            Set names = (Set) groups.get(stat.getGroupName());
            names.add(stat.getName());
        }
        for (Iterator<RateStat> iter = _rateStats.values().iterator(); iter.hasNext();) {
            RateStat stat = iter.next();
            if (!groups.containsKey(stat.getGroupName())) groups.put(stat.getGroupName(), new TreeSet());
            Set names = (Set) groups.get(stat.getGroupName());
            names.add(stat.getName());
        }
        return groups;
    }

    public String getStatFilter() { return _context.getProperty(PROP_STAT_FILTER); }
    public String getStatFile() { return _context.getProperty(PROP_STAT_FILE, DEFAULT_STAT_FILE); }

    /**
     * Save memory by not creating stats unless they are required for router operation
     * @return true if the stat should be ignored.
     */
    public boolean ignoreStat(String statName) {
        if (_context.getBooleanProperty(PROP_STAT_FULL))
            return false;
        String required = _context.getProperty(PROP_STAT_REQUIRED, DEFAULT_STAT_REQUIRED);
        String req[] = required.split(",");
        for (int i=0; i<req.length; i++) {
             if (req[i].equals(statName))
                 return false;
             if (req[i].endsWith("*") && statName.startsWith(req[i].substring(0, req[i].length() - 2)))
                 return false;
        }
        return true;
    }
}
