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
    private final Log _log;
    private final I2PAppContext _context;

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
    /** default false */
    public static final String PROP_STAT_FULL = "stat.full";
    
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
    
    /** @since 0.8.8 */
    public void shutdown() {
        _frequencyStats.clear();
        _rateStats.clear();
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
     * The stat is ONLY created if the stat.full property is true or we are not in the router context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createFrequencyStat(String name, String description, String group, long periods[]) {
        if (ignoreStat(name)) return;
        createRequiredFrequencyStat(name, description, group, periods);
    }

    /**
     * Create a new statistic to monitor the frequency of some event.
     * The stat is always created, independent of the stat.full setting or context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     * @since 0.8.7
     */
    public void createRequiredFrequencyStat(String name, String description, String group, long periods[]) {
        if (_frequencyStats.containsKey(name)) return;
        _frequencyStats.putIfAbsent(name, new FrequencyStat(name, description, group, periods));
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     * The stat is ONLY created if the stat.full property is true or we are not in the router context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     */
    public void createRateStat(String name, String description, String group, long periods[]) {
        if (ignoreStat(name)) return;
        createRequiredRateStat(name, description, group, periods);
    }

    /**
     * Create a new statistic to monitor the average value and confidence of some action.
     * The stat is always created, independent of the stat.full setting or context.
     *
     * @param name unique name of the statistic
     * @param description simple description of the statistic
     * @param group used to group statistics together
     * @param periods array of period lengths (in milliseconds)
     * @since 0.8.7
     */
    public void createRequiredRateStat(String name, String description, String group, long periods[]) {
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

    /**
     *  Misnamed, as it returns a FrequenceyStat, not a Frequency.
     */
    public FrequencyStat getFrequency(String name) {
        return _frequencyStats.get(name);
    }

    /**
     *  Misnamed, as it returns a RateStat, not a Rate.
     */
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
     * Save memory by not creating stats unless they are required for router operation.
     * For backward compatibility of any external clients, always returns false if not in router context.
     *
     * @param statName ignored
     * @return true if the stat should be ignored.
     */
    public boolean ignoreStat(String statName) {
        return _context.isRouterContext() && !_context.getBooleanProperty(PROP_STAT_FULL);
    }
}
