package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Iterator;
import java.util.TreeMap;

import net.i2p.util.Log;

import net.i2p.router.RouterContext;

public class ConfigLoggingHelper {
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
    
    public ConfigLoggingHelper() {}
    
    public String getLogFilePattern() {
        return _context.logManager().getBaseLogfilename();
    }
    public String getRecordPattern() {
        return new String(_context.logManager().getFormat());
    }
    public String getDatePattern() {
        return _context.logManager().getDateFormatPattern();
    }
    public String getMaxFileSize() {
        int bytes = _context.logManager().getFileSize();
        if (bytes == 0) return "1m";
        if (bytes > 1024*1024*1024)
            return (bytes/(1024*1024*1024)) + "g";
        else if (bytes > 1024*1024)
            return (bytes/(1024*1024)) + "m";
        else
            return (bytes/(1024)) + "k";
    }
    public String getLogLevelTable() {
        StringBuffer buf = new StringBuffer(32*1024);
        buf.append("<textarea rows=\"20\" cols=\"80\">");
        List logs = _context.logManager().getLogs();
        TreeMap sortedLogs = new TreeMap();
        for (int i = 0; i < logs.size(); i++) {
            Log l = (Log)logs.get(i);
            sortedLogs.put(l.getName(), l);
        }
        int i = 0;
        for (Iterator iter = sortedLogs.values().iterator(); iter.hasNext(); i++) {
            Log l = (Log)iter.next();
            buf.append(l.getName()).append('=');
            buf.append(Log.toLevelString(l.getMinimumPriority()));
            buf.append("\n");
        }
        buf.append("</textarea><br />\n");
        buf.append("<i>Valid levels are DEBUG, INFO, WARN, ERROR, CRIT</i>\n");
        return buf.toString();
    }
    public String getLogLevelTableDetail() {
        StringBuffer buf = new StringBuffer(8*1024);
        buf.append("<table border=\"1\">\n");
        buf.append("<tr><td>Package/class</td><td>Level</td></tr>\n");
        List logs = _context.logManager().getLogs();
        TreeMap sortedLogs = new TreeMap();
        for (int i = 0; i < logs.size(); i++) {
            Log l = (Log)logs.get(i);
            sortedLogs.put(l.getName(), l);
        }
        int i = 0;
        for (Iterator iter = sortedLogs.values().iterator(); iter.hasNext(); i++) {
            Log l = (Log)iter.next();
            buf.append("<tr>\n <td><input size=\"50\" type=\"text\" name=\"logrecord.");
            buf.append(i).append(".package\" value=\"").append(l.getName());
            buf.append("\" /></td>\n");
            buf.append("<td><select name=\"logrecord.").append(i);
            buf.append(".level\">\n\t");
            buf.append("<option value=\"DEBUG\" ");
            if (l.getMinimumPriority() == Log.DEBUG)
                buf.append("selected=\"true\" ");
            buf.append(">Debug</option>\n\t");
            buf.append("<option value=\"INFO\" ");
            if (l.getMinimumPriority() == Log.INFO)
                buf.append("selected=\"true\" ");
            buf.append(">Info</option>\n\t");
            buf.append("<option value=\"WARN\" ");
            if (l.getMinimumPriority() == Log.WARN)
                buf.append("selected=\"true\" ");
            buf.append(">Warn</option>\n\t");
            buf.append("<option value=\"ERROR\" ");
            if (l.getMinimumPriority() == Log.ERROR)
                buf.append("selected=\"true\" ");
            buf.append(">Error</option>\n\t");
            buf.append("<option value=\"CRIT\" ");
            if (l.getMinimumPriority() == Log.CRIT)
                buf.append("selected=\"true\" ");
            buf.append(">Critical</option>\n\t");
            buf.append("</select></td>\n</tr>\n");
        }
        buf.append("</table>\n");
        return buf.toString();
    }
}
