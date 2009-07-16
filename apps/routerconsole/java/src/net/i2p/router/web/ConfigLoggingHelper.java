package net.i2p.router.web;

import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;

import net.i2p.router.RouterContext;

public class ConfigLoggingHelper extends HelperBase {
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
        StringBuilder buf = new StringBuilder(32*1024);
        Properties limits = _context.logManager().getLimits();
        TreeSet sortedLogs = new TreeSet();
        for (Iterator iter = limits.keySet().iterator(); iter.hasNext(); ) {
            String prefix = (String)iter.next();
            sortedLogs.add(prefix);
        }
        
        buf.append("<textarea name=\"levels\" rows=\"4\" cols=\"60\">");
        for (Iterator iter = sortedLogs.iterator(); iter.hasNext(); ) {
            String prefix = (String)iter.next();
            String level = limits.getProperty(prefix);
            buf.append(prefix).append('=').append(level).append('\n');
        }
        buf.append("</textarea><br />\n");
        buf.append("<i>Add additional logging statements above. Example: net.i2p.router.tunnel=WARN</i><br>");
        buf.append("<i>Or put entries in the logger.config file. Example: logger.record.net.i2p.router.tunnel=WARN</i><br>");
        buf.append("<i>Valid levels are DEBUG, INFO, WARN, ERROR, CRIT</i>\n");
        return buf.toString();
    }
    public String getDefaultLogLevelBox() {
        String cur = _context.logManager().getDefaultLimit();
        StringBuilder buf = new StringBuilder(128);
        buf.append("<select name=\"defaultloglevel\">\n");
        
        buf.append("<option value=\"DEBUG\" ");
        if ("DEBUG".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">DEBUG</option>\n");
        
        buf.append("<option value=\"INFO\" ");
        if ("INFO".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">INFO</option>\n");
        
        buf.append("<option value=\"WARN\" ");
        if ("WARN".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">WARN</option>\n");
        
        buf.append("<option value=\"ERROR\" ");
        if ("ERROR".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">ERROR</option>\n");
        
        buf.append("<option value=\"CRIT\" ");
        if ("CRIT".equals(cur)) buf.append(" selected=\"true\" ");
        buf.append(">CRIT</option>\n");
        
        buf.append("</select>\n");
        return buf.toString();
    }
}
