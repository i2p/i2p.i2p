package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;

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
        buf.append("<ul>");
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
    
    public String getCriticalLogs() {
        List msgs = _context.logManager().getBuffer().getMostRecentCriticalMessages();
        StringBuffer buf = new StringBuffer(16*1024); 
        buf.append("<ul>");
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
    
    public String getServiceLogs() {
        String str = FileUtil.readTextFile("wrapper.log", 500, false);
        if (str == null) 
            return "";
        else
            return "<pre>" + str + "</pre>";
    }
    
    public String getConnectionLogs() {
        List msgs = _context.commSystem().getMostRecentErrorMessages();
        StringBuffer buf = new StringBuffer(16*1024); 
        buf.append("<ul>");
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
