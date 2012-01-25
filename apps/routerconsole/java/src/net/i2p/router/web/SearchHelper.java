package net.i2p.router.web;

import java.util.Map;
import java.util.TreeMap;

import net.i2p.util.PortMapper;

/**
 *  Helper for searches.
 *
 *  @since 0.9
 */
public class SearchHelper extends HelperBase {

    private String _engine;
    private String _query;
    private Map<String, String> _engines = new TreeMap();
    
    private static final char S = ';';
    private static final String PROP_ENGINES = "routerconsole.searchEngines";
    private static final String PROP_DEFAULT = "routerconsole.searchEngine";

    private static final String PROP_DEFAULTS =
        "eepsites.i2p" + S + "http://eepsites.i2p/Content/Search/SearchResults.aspx?inpQuery=%s" + S +
        "epsilon.i2p" + S + "http://epsilon.i2p/search.jsp?q=%s" + S +
        "sprongle.i2p" + S + "http://sprongle.i2p/sprongle.php?q=%s" + S +
        "";

    public void setEngine(String s) {
        _engine = s;
        if (s != null) {
            String dflt = _context.getProperty(PROP_DEFAULT);
            if (!s.equals(dflt))
                _context.router().saveConfig(PROP_DEFAULT, s);
        }
    }

    public void setQuery(String s) {
        _query = s;
    }

    private void buildEngineMap() {
        String config = _context.getProperty(PROP_ENGINES, PROP_DEFAULTS);
        String[] args = config.split("" + S);
        for (int i = 0; i < args.length - 1; i += 2) {
            String name = args[i];
            String url = args[i+1];
            _engines.put(name, url);
        }
    }

    public String getSelector() {
        buildEngineMap();
        if (_engines.isEmpty())
            return "<b>No search engines specified</b>";
        String dflt = _context.getProperty(PROP_DEFAULT);
        if (dflt == null || !_engines.containsKey(dflt)) {
            // pick a randome one as default and save it
            int idx = _context.random().nextInt(_engines.size());
            int i = 0;
            for (String name : _engines.keySet()) {
                dflt = name;
                if (i++ >= idx) {
                    _context.router().saveConfig(PROP_DEFAULT, dflt);
                    break;
                }
            }
        }
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<select name=\"engine\">");
        for (String name : _engines.keySet()) {
            buf.append("<option value=\"").append(name).append('\"');
            if (name.equals(dflt))
                buf.append(" selected=\"true\"");
            buf.append('>').append(name).append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     *  @return null on error
     */
    public String getURL() {
        if (_engine == null || _query == null)
            return null;
        _query = _query.trim();
        if (_query.length() <= 0)
            return null;
        buildEngineMap();
        String url = _engines.get(_engine);
        if (url == null)
            return null;
        // _query = escape query
        if (url.contains("%s"))
            url = url.replace("%s", _query);
        else
            url += _query;
        return url;
    }
}
