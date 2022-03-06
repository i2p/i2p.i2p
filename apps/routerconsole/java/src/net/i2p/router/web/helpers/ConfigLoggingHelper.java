package net.i2p.router.web.helpers;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Log;
import net.i2p.util.Translate;

public class ConfigLoggingHelper extends HelperBase {
    
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
        buf.append("</textarea><br>\n<p>");
        buf.append(_t("Add additional logging statements above (e.g. {0}).", "<b>net.i2p.router.tunnel=WARN</b>"))
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

    /** these are translated in the core bundle */
    private static final String[] levels = { "CRIT", "ERROR", "WARN", "INFO", "DEBUG" };

    public String getDefaultLogLevelBox() {
        StringBuilder buf = new StringBuilder(128);
        String cur = _context.logManager().getDefaultLimit();
        getLogLevelBox(buf, "defaultloglevel", cur, false);
        return buf.toString();
    }

    private void getLogLevelBox(StringBuilder buf, String name, String cur, boolean showRemove) {
        buf.append("<select name=\"").append(name).append("\">\n");
        
        for (int i = 0; i < levels.length; i++) {
            String l = levels[i];
            buf.append("<option value=\"").append(l).append('\"');
            if (l.equals(cur))
                buf.append(SELECTED);
            buf.append('>').append(_c(l)).append("</option>\n");
        }        
        
        //if (showRemove)
        //    buf.append("<option value=\"remove\">").append(_t("Remove")).append("</option>");
        buf.append("</select>\n");
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
                    // add a package marker, see below
                    sortedLogs.add(subst + '.');
                }
                lastdot = nextdot;
            }
        }

        StringBuilder buf = new StringBuilder(65536);
        buf.append("<select name=\"newlogclass\">\n" +
                   "<option value=\"\" selected=\"selected\">")
           .append(_t("Select a class to add"))
           .append("</option>\n");

        int groups = 0;
        for (String l : sortedLogs) {
            String d;
            // replace package marker
            if (l.endsWith(".")) {
                if (groups++ > 0)
                    buf.append("</optgroup>\n");
                l = l.substring(0, l.length() - 1);
                buf.append("<optgroup label=\"").append(l).append("\">\n");
                d = _t("All classes in {0}", l);
            } else {
                int last = l.lastIndexOf(".");
                if (last > 0)
                    d = l.substring(last + 1);
                else
                    d = l;
            }
            buf.append("<option value=\"").append(l).append("\">")
               .append(d).append("</option>\n");
        }        
        if (groups > 0)
            buf.append("</optgroup>\n");
        
        buf.append("</select>\n");
        getLogLevelBox(buf, "newloglevel", "WARN", false);
        return buf.toString();
    }

    private static final String CORE_BUNDLE_NAME = "net.i2p.util.messages";

    /**
     *  translate a string from the core bundle
     *  @since 0.9.45
     */
    private String _c(String s) {
        return Translate.getString(s, _context, CORE_BUNDLE_NAME);
    }
}
