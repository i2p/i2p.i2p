package net.i2p.router.web;

import java.io.File;
import java.util.List;

import net.i2p.util.FileUtil;

public class LogsHelper extends HelperBase {
    public LogsHelper() {}
    
    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        return "Location: <code>" + _context.logManager().currentFile() + "</code><br /><br />" + str;
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
            return "";
        else {
            str = str.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
            return "Location:<code> " + f.getAbsolutePath() + "</code> <pre>" + str + "</pre>";
        }
    }
    
    /*****  unused
    public String getConnectionLogs() {
        return formatMessages(_context.commSystem().getMostRecentErrorMessages());
    }
    ******/

    private String formatMessages(List msgs) {
        boolean colorize = Boolean.valueOf(_context.getProperty("routerconsole.logs.color")).booleanValue();
        StringBuilder buf = new StringBuilder(16*1024); 
        buf.append("<ul>");
        buf.append("<code>\n");
        for (int i = msgs.size(); i > 0; i--) { 
            String msg = (String)msgs.get(i - 1);
            buf.append("<li>");
            if (colorize) {
                String color;
                // Homeland Security Advisory System
                // http://www.dhs.gov/xinfoshare/programs/Copy_of_press_release_0046.shtm
                // but pink instead of yellow for WARN
                if (msg.contains("CRIT"))
                    color = "#cc0000";
                else if (msg.contains("ERROR"))
                    color = "#ff3300";
                else if (msg.contains("WARN"))
                    color = "#ff00cc";
                else if (msg.contains("INFO"))
                    color = "#000099";
                else
                    color = "#006600";
                buf.append("<font color=\"").append(color).append("\">");
                buf.append(msg.replaceAll("<", "&lt;").replaceAll(">", "&gt;"));
                buf.append("</font>");
            } else {
                buf.append(msg);
            }
            buf.append("</li>\n");
        }
        buf.append("</code></ul>\n");
        
        return buf.toString();
    }
}
