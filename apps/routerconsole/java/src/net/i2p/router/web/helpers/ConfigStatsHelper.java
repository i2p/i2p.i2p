package net.i2p.router.web.helpers;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeMap;

import net.i2p.router.web.HelperBase;
import net.i2p.router.web.StatSummarizer;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;

public class ConfigStatsHelper extends HelperBase {
    private Log _log;
    private String _filter;
    private final Set<String> _filters;
    private final Set<String> _graphs;
    /** list of names of stats which are remaining, ordered by nested groups */
    private final List<String> _stats;
    private String _currentStatName;
    private String _currentGraphName;
    private String _currentStatDescription;
    private String _currentGroup;
    /** true if the current stat is the first in the group */
    private boolean _currentIsFirstInGroup;
    /** true if the stat is being logged */
    private boolean _currentIsLogged;
    private boolean _currentIsGraphed;
    private boolean _currentCanBeGraphed;
    
    public ConfigStatsHelper() {
        _stats = new ArrayList<String>();
        _filters = new HashSet<String>();
        _graphs = new HashSet<String>();
    }

    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId beginning few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _log = _context.logManager().getLog(ConfigStatsHelper.class);
        
        Map<String, SortedSet<String>> unsorted = _context.statManager().getStatsByGroup();
        Map<String, Set<String>> groups = new TreeMap<String, Set<String>>(new AlphaComparator());
        groups.putAll(unsorted);
        for (Set<String> stats : groups.values()) {
             _stats.addAll(stats);
        }
        _filter = _context.statManager().getStatFilter(); 
        if (_filter == null)
            _filter = "";
        
        StringTokenizer tok = new StringTokenizer(_filter, ",");
        while (tok.hasMoreTokens())
            _filters.add(tok.nextToken().trim());

        // create a local copy of the config. Querying r.getSummaryListener()
        // lags behind, as StatSummarizer only runs once a minute.
        String specs = _context.getProperty("stat.summaries", StatSummarizer.DEFAULT_DATABASES);
        tok = new StringTokenizer(specs, ",");
        while (tok.hasMoreTokens()) {
            _graphs.add(tok.nextToken().trim());
        }
    }

    /**
     *  Just hide for everybody unless already set.
     *  To enable set advanced config stat.logFilters=foo before starting...
     *  it has to be set at startup anyway for logging to be enabled at all
     *  @since 0.9
     */
    public boolean shouldShowLog() {
        return !_filters.isEmpty();
    }

    public String getFilename() { return _context.statManager().getStatFile(); }
    
    /** 
     * move the cursor to the next known stat, returning true if a valid
     * stat is available.
     *
     * @return true if a valid stat is available, otherwise false
     */
    public boolean hasMoreStats() {
        if (_stats.isEmpty())
            return false;
        _currentIsGraphed = false;
        _currentStatName = _stats.remove(0);
        RateStat rs = _context.statManager().getRate(_currentStatName);
        if (rs != null) {
            _currentStatDescription = rs.getDescription();
            if (_currentGroup == null)
                _currentIsFirstInGroup = true;
            else if (!rs.getGroupName().equals(_currentGroup))
                _currentIsFirstInGroup = true;
            else
                _currentIsFirstInGroup = false;
            _currentGroup = rs.getGroupName();
            long period = rs.getPeriods()[0]; // should be the minimum
            if (period <= 10*60*1000) {
                Rate r = rs.getRate(period);
                _currentCanBeGraphed = r != null;
                if (_currentCanBeGraphed) {
                    // see above
                    //_currentIsGraphed = r.getSummaryListener() != null;
                    _currentGraphName = _currentStatName + "." + period;
                    _currentIsGraphed = _graphs.contains(_currentGraphName);
                }
            } else {
                _currentCanBeGraphed = false;
            }
        } else {
            FrequencyStat fs = _context.statManager().getFrequency(_currentStatName);
            if (fs != null) {
                _currentStatDescription = fs.getDescription();
                if (_currentGroup == null)
                    _currentIsFirstInGroup = true;
                else if (!fs.getGroupName().equals(_currentGroup))
                    _currentIsFirstInGroup = true;
                else
                    _currentIsFirstInGroup = false;
                _currentGroup = fs.getGroupName();
                _currentCanBeGraphed = false;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Stat does not exist?!  [" + _currentStatName + "]");
                return false;
            }
        }
        
        if (_filters.contains("*") || _filters.contains(_currentStatName))
            _currentIsLogged = true;
        else
            _currentIsLogged = false;
        return true;
    }
    
    /** Is the current stat the first in the group? */
    public boolean groupRequired() {
        if (_currentIsFirstInGroup) {
            _currentIsFirstInGroup = false;
            return true;
        } else {
            return false;
        }
    }
    /** What group is the current stat in */
    public String getCurrentGroupName() { return _currentGroup; }
    public String getCurrentStatName() { return _currentStatName; }
    public String getCurrentGraphName() { return _currentGraphName; }
    public String getCurrentStatDescription() { return _currentStatDescription; }
    public boolean getCurrentIsLogged() { return _currentIsLogged; }
    public boolean getCurrentIsGraphed() { return _currentIsGraphed; }
    public boolean getCurrentCanBeGraphed() { return _currentCanBeGraphed; }
    public String getExplicitFilter() { return _filter; }
    public boolean getIsFull() {
        return _context.getBooleanProperty(StatManager.PROP_STAT_FULL);
    }

    /**
     *  Translated sort
     *  Inner class, can't be Serializable
     *  @since 0.9.4
     */
    private class AlphaComparator implements Comparator<String> {
        public int compare(String lhs, String rhs) {
            String lname = _t(lhs);
            String rname = _t(rhs);
            return Collator.getInstance().compare(lname, rname);
        }
    }
}
