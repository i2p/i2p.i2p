package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;

public class ContentHelper {
    private String _page;
    private int _maxLines;
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
        String str = DataHelper.readTextFile(_page, _maxLines);
        if (str == null) 
            return "";
        else 
            return str;
    } 
    public String getTextContent() {
        String str = DataHelper.readTextFile(_page, _maxLines);
        if (str == null) 
            return "";
        else 
            return "<pre>" + str + "</pre>";
    }
}
