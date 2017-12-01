package net.i2p.router.web.helpers;

import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;

/**
 *  Helper for searches.
 *
 *  @since 0.9
 */
public class SearchHelper extends HelperBase {

    private String _engine;
    private String _query;
    private Map<String, String> _engines = new TreeMap<String, String>();
    
    private static final char S = ',';
    // in case engines need to know where it came from
    private static final String SOURCE = "&ref=console";
    static final String PROP_ENGINES = "routerconsole.searchEngines";
    private static final String PROP_DEFAULT = "routerconsole.searchEngine";

    static final String ENGINES_DEFAULT =
        "eepsites.i2p" + S + "http://eepsites.i2p/Content/Search/SearchResults.aspx?inpQuery=%s" + SOURCE + S +
        "epsilon.i2p" + S + "http://epsilon.i2p/search.jsp?q=%s" + SOURCE + // S +
        //"searchthis.i2p" + S + "http://searchthis.i2p/cgi-bin/search.cgi?q=%s" + SOURCE + S +
        //"simple-search.i2p" + S + "http://simple-search.i2p/search.sh?search=%s" + SOURCE + S +
        //"sprongle.i2p" + S + "http://sprongle.i2p/sprongle.php?q=%s" + SOURCE + S +
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

    private static final String SS = Character.toString(S);

    private void buildEngineMap() {
        String config = _context.getProperty(PROP_ENGINES, ENGINES_DEFAULT);
        String[] args = DataHelper.split(config, SS);
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
        buf.append("<select name=\"engine\" title=\"").append(_t("Select search engine")).append("\">");
        for (String name : _engines.keySet()) {
            buf.append("<option value=\"").append(name).append('\"');
            if (name.equals(dflt))
                buf.append(" selected=\"selected\"");
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
        _query = DataHelper.escapeHTML(_query).trim();
        if (_query.length() <= 0)
            return null;
        buildEngineMap();
        String url = _engines.get(_engine);
        if (url == null)
            return null;
        if (url.contains("%s"))
            url = url.replace("%s", _query);
        else
            url += _query;
        return url;
    }
}
