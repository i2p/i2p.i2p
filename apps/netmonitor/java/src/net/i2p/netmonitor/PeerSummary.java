package net.i2p.netmonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.util.Clock;
import net.i2p.util.Log;

/** 
 * coordinate the data points summarizing the performance of a particular peer 
 * within the network
 */
public class PeerSummary {
    private static final Log _log = new Log(PeerSummary.class);
    private String _peer;
    /** statName to a List of PeerStat elements (sorted by sample date, earliest first) */
    private Map _stats;
    /** lock on this when accessing stat data */
    private Object _coalesceLock = new Object();
    
    public PeerSummary(String peer) {
        _peer = peer;
        _stats = new HashMap(16);   
    }
    
    /** 
     * Track a data point
     *
     * @param stat what data are we tracking?
     * @param description what does this data mean?  (and what are the values?)
     * @param when what data set is this sample based off?
     * @param val actual data harvested
     */
    public void addData(String stat, String description, String valueDescriptions[], long when, double val[]) {
        synchronized (_coalesceLock) {
            TreeMap stats = locked_getData(stat);
            stats.put(new Long(when), new PeerStat(stat, description, valueDescriptions, when, val));
        }
    }
    
    /** 
     * Track a data point
     *
     * @param stat what data are we tracking?
     * @param description what does this data mean?  (and what are the values?)
     * @param when what data set is this sample based off?
     * @param val actual data harvested
     */
    public void addData(String stat, String description, String valueDescriptions[], long when, long val[]) {
        synchronized (_coalesceLock) {
            TreeMap stats = locked_getData(stat);
            stats.put(new Long(when), new PeerStat(stat, description, valueDescriptions, when, val));
        }
    }
    
    /** get the peer's name (H(routerIdentity).toBase64()) */
    public String getPeer() { return _peer; }
    
    /** 
     * fetch the ordered list of PeerStat objects for the given stat (or null if 
     * isn't being tracked or has no data) 
     *
     */
    public List getData(String statName) {
        synchronized (_coalesceLock) {
            return new ArrayList(((TreeMap)_stats.get(statName)).values());
        }
    }
    
    /**
     * Get the names of all of the stats that are being tracked 
     *
     */
    public Set getStatNames() { 
        synchronized (_coalesceLock) {
            return new HashSet(_stats.keySet());
        }
    }
    
    /** drop old data points */
    public void coalesceData(long summaryDurationMs) {
        long earliest = Clock.getInstance().now() - summaryDurationMs;
        synchronized (_coalesceLock) {
            locked_coalesce(earliest);
        }
    }
    
    /** go through all the stats and remove ones from before the given date */
    private void locked_coalesce(long earliestSampleDate) {
        if (true) return;
        for (Iterator iter = _stats.keySet().iterator(); iter.hasNext(); ) {
            String statName = (String)iter.next();
            TreeMap stats = (TreeMap)_stats.get(statName);
            while (stats.size() > 0) {
                Long when = (Long)stats.keySet().iterator().next();
                if (when.longValue() < earliestSampleDate) {
                    stats.remove(when);
                } else {
                    break;
                }
            }
        }
    }
    
    /**
     * @return PeerStat elements, ordered by sample date (earliest first)
     */
    private TreeMap locked_getData(String statName) {
        if (!_stats.containsKey(statName))
            _stats.put(statName, new TreeMap());
        return (TreeMap)_stats.get(statName);
    }
}
