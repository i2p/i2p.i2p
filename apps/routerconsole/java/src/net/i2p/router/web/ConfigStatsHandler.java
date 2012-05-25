package net.i2p.router.web;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.stat.StatManager;
import net.i2p.util.Log;

/**
 * Handler to deal with form submissions from the stats config form and act
 * upon the values.
 *
 */
public class ConfigStatsHandler extends FormHandler {
    private String _filename;
    private List _stats;
    private String _graphs;
    private boolean _explicitFilter;
    private String _explicitFilterValue;
    private boolean _isFull;
    
    public ConfigStatsHandler() {
        super();
        _stats = new ArrayList();
        _graphs = "";
        _explicitFilter = false;
        _isFull = false;
    }
    
    protected void processForm() {
        saveChanges();
    }
    
    public void setFilename(String filename) {
        _filename = (filename != null ? filename.trim() : null);
    }

    public void setStatList(String stats[]) {
        if (stats != null) {
            for (int i = 0; i < stats.length; i++) {
                String cur = stats[i].trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Stat: [" + cur + "]");
                if ( (cur.length() > 0) && (!_stats.contains(cur)) )
                    _stats.add(cur);
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updated stats: " + _stats);
    }

    public void setGraphList(String stats[]) {
        if (stats != null) {
            String s = "";
            for (int i = 0; i < stats.length; i++) {
                String cur = stats[i].trim();
                if (cur.length() > 0) {
                    if (s.length() > 0)
                        s = s + ",";
                    s = s + cur;
                }
            }
            _graphs = s;
        } else {
            _graphs = "";
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updated graphs: " + _graphs);
    }

    public void setExplicitFilter(String foo) { _explicitFilter = true; }
    public void setExplicitFilterValue(String filter) { _explicitFilterValue = filter; }
    public void setIsFull(String foo) { _isFull = true; }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        if (_filename == null)
            _filename = StatManager.DEFAULT_STAT_FILE;
        _context.router().setConfigSetting(StatManager.PROP_STAT_FILE, _filename);
        
        if (_explicitFilter) {
            _stats.clear();
            if (_explicitFilterValue == null)
                _explicitFilterValue = "";
            
            if (_explicitFilterValue.indexOf(',') != -1) {
                StringTokenizer tok = new StringTokenizer(_explicitFilterValue, ",");
                while (tok.hasMoreTokens()) {
                    String cur = tok.nextToken().trim();
                    if ( (cur.length() > 0) && (!_stats.contains(cur)) )
                        _stats.add(cur);
                }
            } else {
                String stat = _explicitFilterValue.trim();
                if ( (stat.length() > 0) && (!_stats.contains(stat)) )
                    _stats.add(stat);
            }
        }
        
        StringBuilder stats = new StringBuilder();
        for (int i = 0; i < _stats.size(); i++) {
            stats.append((String)_stats.get(i));
            if (i + 1 < _stats.size())
                stats.append(',');
        }
            
        _context.router().setConfigSetting(StatManager.PROP_STAT_FILTER, stats.toString());
        _context.router().setConfigSetting("stat.summaries", _graphs);
        _context.router().setConfigSetting(StatManager.PROP_STAT_FULL, "" + _isFull);
        boolean ok = _context.router().saveConfig();
        if (ok) 
            addFormNotice("Stat filter and location updated successfully to: " + stats.toString());
        else
            addFormError("Failed to update the stat filter and location");
        addFormNotice("Graph list updated, may take up to 60s to be reflected here and on the <a href=\"graphs.jsp\">Graphs Page</a>");
    }
    
}
