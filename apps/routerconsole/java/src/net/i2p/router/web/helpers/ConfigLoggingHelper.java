package net.i2p.router.web.helpers;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;

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
        if (bytes <= 0) return "1.00 MiB";
        return DataHelper.formatSize2(bytes, false) + 'B';
    }
    public String getLogLevelTable() {
        StringBuilder buf = new StringBuilder(32*1024);
        Properties limits = _context.logManager().getLimits();
        TreeSet<String> sortedLogs = new TreeSet<String>();
        for (String prefix : limits.stringPropertyNames()) {
            sortedLogs.add(prefix);
        }
        
        buf.append("<textarea name=\"levels\" rows=\"4\" cols=\"60\" wrap=\"off\" spellcheck=\"false\">");
        for (String prefix : sortedLogs) {
            String level = limits.getProperty(prefix);
            buf.append(prefix).append('=').append(level).append('\n');
        }
        buf.append("</textarea><br>\n");
        buf.append("<p>")
           .append(_t("Add additional logging statements above (e.g. {0}).", "<b>net.i2p.router.tunnel=WARN</b>"))
           .append("<br>")
           .append(_t("Alternatively, put entries in the file {0} (e.g. {1}).",
                      "<b>" + new File(_context.getConfigDir(), "logger.config") + "</b>",
                      "<b>logger.record.net.i2p.router.tunnel=WARN</b>"))
           .append("<br>")
           .append(_t("Valid log levels are {0}.", "<b>DEBUG, INFO, WARN, ERROR, CRIT</b>"))
           .append("</p>\n");

      /****
        // this is too big and ugly
        if (limits.size() <= 0)
            return "";
        buf.append("<table>");
        for (String prefix : sortedLogs) {
            buf.append("<tr><td>").append(prefix).append("</td><td>");
            String level = limits.getProperty(prefix);
            buf.append(getLogLevelBox("level-" + prefix, level, true)).append("</td></tr>");
        }
        buf.append("</table>");
       ****/

        return buf.toString();
    }

    private static String[] levels = { _x("CRIT"), _x("ERROR"), _x("WARN"), _x("INFO"), _x("DEBUG") };

    public String getDefaultLogLevelBox() {
        String cur = _context.logManager().getDefaultLimit();
        return getLogLevelBox("defaultloglevel", cur, false);
    }

    private String getLogLevelBox(String name, String cur, boolean showRemove) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("<select name=\"").append(name).append("\">\n");
        
        for (int i = 0; i < levels.length; i++) {
            String l = levels[i];
            buf.append("<option value=\"").append(l).append('\"');
            if (l.equals(cur))
                buf.append(" selected=\"selected\"");
            buf.append('>').append(_t(l)).append("</option>\n");
        }        
        
        if (showRemove)
            buf.append("<option value=\"remove\">").append(_t("Remove")).append("</option>");
        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     *  All the classes the log manager knows about, except ones that
     *  already have overrides
     *  @since 0.8.1
     */
    public String getNewClassBox() {
        List<Log> logs = _context.logManager().getLogs();
        Set<String> limits = _context.logManager().getLimits().stringPropertyNames();
        TreeSet<String> sortedLogs = new TreeSet<String>();

        for (Log log : logs) {
            String name = log.getName();
            if (!limits.contains(name))
                sortedLogs.add(name);

            // add higher classes of length 3 or more
            int dots = 0;
            int lastdot = -1;
            int nextdot = 0;
            while ((nextdot = name.indexOf('.', lastdot + 1)) > 0) {
                if (++dots >= 3) {
                    String subst = name.substring(0, nextdot);
                    if (!limits.contains(subst))
                        sortedLogs.add(subst);
                }
                lastdot = nextdot;
            }
        }

        StringBuilder buf = new StringBuilder(65536);
        buf.append("<select name=\"newlogclass\">\n" +
                   "<option value=\"\" selected=\"selected\">")
           .append(_t("Select a class to add"))
           .append("</option>\n");

        for (String l : sortedLogs) {
            buf.append("<option value=\"").append(l).append("\">")
               .append(l).append("</option>\n");
        }        
        
        buf.append("</select>\n");
        buf.append(getLogLevelBox("newloglevel", "WARN", false));
        return buf.toString();
    }
}
