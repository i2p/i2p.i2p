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
    private String _graphs;
    private boolean _isFull;
    
    public ConfigStatsHandler() {
        super();
        _graphs = "";
        _isFull = false;
    }
    
    @Override
    protected void processForm() {
        if (_action != null && _action.equals("foo")) {
            saveChanges();
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

    public void setIsFull(String foo) { _isFull = true; }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        Map<String, String> changes = new HashMap<String, String>();
        boolean graphsChanged = !_graphs.equals(_context.getProperty("stat.summaries"));
        changes.put("stat.summaries", _graphs);
        boolean fullChanged = _context.getBooleanProperty(StatManager.PROP_STAT_FULL) != _isFull;
        changes.put(StatManager.PROP_STAT_FULL, Boolean.toString(_isFull));
        _context.router().saveConfig(changes, null);
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
