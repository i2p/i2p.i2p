package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import net.i2p.router.RouterContext;

public class LogsHelper {
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
    
    public LogsHelper() {}
    
    public String getLogs() {
        List msgs = _context.logManager().getBuffer().getMostRecentMessages();
        StringBuffer buf = new StringBuffer(16*1024); 
        buf.append("<h2>Most recent console messages:</h2><ul>");
        buf.append("<code>\n");
        for (int i = msgs.size(); i > 0; i--) { 
            String msg = (String)msgs.get(i - 1);
            buf.append("<li>");
            buf.append(msg);
            buf.append("</li>\n");
        }
        buf.append("</code></ul>\n");
        
        return buf.toString();
    }
}
