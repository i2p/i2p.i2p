package net.i2p.router.web;

import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;

public class ContentHelper {
    private String _page;
    private int _maxLines;
    private boolean _startAtBeginning;
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    public ContentHelper() {}
    
    public void setPage(String page) { _page = page; }
    public void setStartAtBeginning(String moo) { 
        _startAtBeginning = Boolean.valueOf(""+moo).booleanValue(); 
    }
    
    public void setMaxLines(String lines) {
        if (lines != null) {
            try {
                _maxLines = Integer.parseInt(lines);
            } catch (NumberFormatException nfe) {
                _maxLines = -1;
            }
        } else {
            _maxLines = -1;
        }
    } 
    public String getContent() {
        String str = FileUtil.readTextFile(_page, _maxLines, _startAtBeginning);
        if (str == null) 
            return "";
        else 
            return str;
    } 
    public String getTextContent() {
        String str = FileUtil.readTextFile(_page, _maxLines, _startAtBeginning);
        if (str == null) 
            return "";
        else {
            StringBuffer sb = new StringBuffer(str.length()+11);
            sb.append("<pre>");
            for (int i=0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (str.charAt(i)) {
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '&': sb.append("&amp;"); break;
                    default: sb.append(c); break;
                }
            }
            return sb.append("</pre>").toString();
	}
    }
}
