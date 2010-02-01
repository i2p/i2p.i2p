package net.i2p.router.web;

import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;


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
        
        buf.append("<textarea name=\"levels\" rows=\"4\" cols=\"60\" wrap=\"off\">");
        for (Iterator iter = sortedLogs.iterator(); iter.hasNext(); ) {
            String prefix = (String)iter.next();
            String level = limits.getProperty(prefix);
            buf.append(prefix).append('=').append(level).append('\n');
        }
        buf.append("</textarea><br>\n");
        buf.append("<i>" + _("Add additional logging statements above. Example: net.i2p.router.tunnel=WARN") + "</i><br>");
        buf.append("<i>" + _("Or put entries in the logger.config file. Example: logger.record.net.i2p.router.tunnel=WARN") + "</i><br>");
        buf.append("<i>" + _("Valid levels are DEBUG, INFO, WARN, ERROR, CRIT") + "</i>\n");
        return buf.toString();
    }

    private static String[] levels = { _x("CRIT"), _x("ERROR"), _x("WARN"), _x("INFO"), _x("DEBUG") };

    public String getDefaultLogLevelBox() {
        String cur = _context.logManager().getDefaultLimit();
        StringBuilder buf = new StringBuilder(128);
        buf.append("<select name=\"defaultloglevel\">\n");
        
        for (int i = 0; i < levels.length; i++) {
            String l = levels[i];
            buf.append("<option value=\"").append(l).append("\" ");
            if (l.equals(cur))
                buf.append(" selected=\"true\" ");
            buf.append('>').append(_(l)).append("</option>\n");
        }        
        
        buf.append("</select>\n");
        return buf.toString();
    }
}
