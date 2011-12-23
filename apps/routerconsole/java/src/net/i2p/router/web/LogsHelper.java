package net.i2p.router.web;

import java.io.File;
import java.util.List;

import net.i2p.util.FileUtil;

import org.mortbay.jetty.Server;

public class LogsHelper extends HelperBase {
    public LogsHelper() {}
    
    /** @since 0.8.11 */
    public String getJettyVersion() {
        return Server.getVersion();
    }

    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        return _("File location") + ": <b><code>" + _context.logManager().currentFile() + "</code></b><br><br>" + str;
    }
    
    public String getCriticalLogs() {
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }
    
    public String getServiceLogs() {
        // RouterLaunch puts the location here if no wrapper
        String path = System.getProperty("wrapper.logfile");
        File f;
        if (path != null) {
            f = new File(path);
        } else {
            // look in new and old places
            f = new File(System.getProperty("java.io.tmpdir"), "wrapper.log");
            if (!f.exists())
                f = new File(_context.getBaseDir(), "wrapper.log");
        }
        String str = FileUtil.readTextFile(f.getAbsolutePath(), 250, false);
        if (str == null) 
            return _("File not found") + ": <b><code>" + f.getAbsolutePath() + "</code></b>";
        else {
            str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return _("File location") + ": <b><code>" + f.getAbsolutePath() + "</code></b> <pre>" + str + "</pre>";
        }
    }
    
    /*****  unused
    public String getConnectionLogs() {
        return formatMessages(_context.commSystem().getMostRecentErrorMessages());
    }
    ******/

    private final static String NL = System.getProperty("line.separator");

    /** formats in reverse order */
    private String formatMessages(List<String> msgs) {
        if (msgs.isEmpty())
            return "<p><i>" + _("No log messages") + "</i></p>";
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = new StringBuilder(16*1024); 
        buf.append("<ul>");
        for (int i = msgs.size() - 1; i >= 0; i--) { 
            String msg = msgs.get(i);
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            // remove  last \n that LogRecordFormatter added
            if (msg.endsWith(NL))
                msg = msg.substring(0, msg.length() - NL.length());
            // replace \n so that exception stack traces will format correctly and will paste nicely into pastebin
            msg = msg.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
            buf.append("<li>");
            if (colorize) {
                // TODO this would be a lot easier if LogConsoleBuffer stored LogRecords instead of formatted strings
                String color;
                // Homeland Security Advisory System
                // http://www.dhs.gov/xinfoshare/programs/Copy_of_press_release_0046.shtm
                // but pink instead of yellow for WARN
                if (msg.contains(_("CRIT")))
                    color = "#cc0000";
                else if (msg.contains(_("ERROR")))
                    color = "#ff3300";
                else if (msg.contains(_("WARN")))
                    color = "#ff00cc";
                else if (msg.contains(_("INFO")))
                    color = "#000099";
                else
                    color = "#006600";
                buf.append("<font color=\"").append(color).append("\">");
                buf.append(msg);
                buf.append("</font>");
            } else {
                buf.append(msg);
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        
        return buf.toString();
    }
}
