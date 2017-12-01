package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import net.i2p.router.web.FormHandler;
import net.i2p.stat.StatManager;

/**
 * Handler to deal with form submissions from the stats config form and act
 * upon the values.
 *
 */
public class ConfigStatsHandler extends FormHandler {
    private String _filename;
    private List<String> _stats;
    private String _graphs;
    private boolean _explicitFilter;
    private String _explicitFilterValue;
    private boolean _isFull;
    
    public ConfigStatsHandler() {
        super();
        _stats = new ArrayList<String>();
        _graphs = "";
        _explicitFilter = false;
        _isFull = false;
    }
    
    @Override
    protected void processForm() {
        if (_action != null && _action.equals("foo")) {
            saveChanges();
        }
    }
    
    public void setFilename(String filename) {
        _filename = (filename != null ? filename.trim() : null);
    }

    public void setStatList(String stats[]) {
        if (stats != null) {
            for (int i = 0; i < stats.length; i++) {
                String cur = stats[i].trim();
                if ( (cur.length() > 0) && (!_stats.contains(cur)) )
                    _stats.add(cur);
            }
        }
    }

    public void setGraphList(String stats[]) {
        if (stats != null) {
            StringBuilder s = new StringBuilder(128);
            for (int i = 0; i < stats.length; i++) {
                String cur = stats[i].trim();
                if (cur.length() > 0) {
                    if (s.length() > 0)
                        s.append(",");
                    s.append(cur);
                }
            }
            _graphs = s.toString();
        } else {
            _graphs = "";
        }
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
        Map<String, String> changes = new HashMap<String, String>();
        if (_filename == null)
            _filename = StatManager.DEFAULT_STAT_FILE;
        changes.put(StatManager.PROP_STAT_FILE, _filename);
        
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
            stats.append(_stats.get(i));
            if (i + 1 < _stats.size())
                stats.append(',');
        }
            
        changes.put(StatManager.PROP_STAT_FILTER, stats.toString());
        boolean graphsChanged = !_graphs.equals(_context.getProperty("stat.summaries"));
        changes.put("stat.summaries", _graphs);
        boolean fullChanged = _context.getBooleanProperty(StatManager.PROP_STAT_FULL) != _isFull;
        changes.put(StatManager.PROP_STAT_FULL, "" + _isFull);
        _context.router().saveConfig(changes, null);
        if (!_stats.isEmpty())
            addFormNotice(_t("Stat filter and location updated successfully to") + ": " + stats.toString());
        if (fullChanged) {
            if (_isFull)
                addFormNotice(_t("Full statistics enabled"));
            else
                addFormNotice(_t("Full statistics disabled"));
            addFormNotice(_t("Restart required to take effect"));
        }
        if (graphsChanged)
            addFormNoticeNoEscape(_t("Graph list updated, may take up to 60s to be reflected on the {0}Graphs Page{1}", "<a href=\"graphs\">", "</a>"));
    }
    
}
