package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.util.FileUtil;

public class LogsHelper extends HelperBase {

    private static final String _jstlVersion = jstlVersion();

    /** @since 0.8.12 */
    public String getJettyVersion() {
        return RouterConsoleRunner.jettyVersion();
    }

    /** @since 0.9.15 */
    public String getUnavailableCrypto() {
        StringBuilder buf = new StringBuilder(128);
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                buf.append("<tr><td><b>Crypto:</b></td><td>").append(t.toString()).append(" unavailable</td></tr>");
            }
        }
        return buf.toString();
    }

    /**
     * @return non-null, "n/a" on failure
     * @since 0.9.26
     */
    public String getJstlVersion() {
        return _jstlVersion;
    }

    /**
     * @return non-null, "n/a" on failure
     * @since 0.9.26
     */
    private static String jstlVersion() {
        String rv = "n/a";
        try {
            Class<?> cls = Class.forName("org.apache.taglibs.standard.Version", true, ClassLoader.getSystemClassLoader());
            Method getVersion = cls.getMethod("getVersion");
            // returns "standard-taglib 1.2.0"
            Object version = getVersion.invoke(null, (Object[]) null);
            rv = (String) version;
            //int sp = rv.indexOf(' ');
            //if (sp >= 0 && rv.length() > sp + 1)
            //    rv = rv.substring(sp + 1);
        } catch (Exception e) {}
        return rv;
    }

    /**
     *  Does not call logManager.flush(); call getCriticalLogs() first to flush
     */
    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        return "<p>" + _t("File location") + ": <a href=\"/router.log\" target=\"_blank\">" + _context.logManager().currentFile() + "</a></p>" + str;
    }
    
    /**
     *  Side effect - calls logManager.flush()
     */
    public String getCriticalLogs() {
        _context.logManager().flush();
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }

    public String getServiceLogs() {
        File f = ConfigServiceHandler.wrapperLogFile(_context);
        String str;
        if (_context.hasWrapper()) {
            // platform encoding
            str = readTextFile(f, 250);
        } else {
            // UTF-8
            str = FileUtil.readTextFile(f.getAbsolutePath(), 250, false);
        }
        if (str == null) {
            return "<p>" + _t("File not found") + ": <b><code>" + f.getAbsolutePath() + "</code></b></p>";
        } else {
            str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return "<p>" + _t("File location") + ": <a href=\"/wrapper.log\" target=\"_blank\">" + f.getAbsolutePath() + "</a></p></td></tr>\n<tr><td><pre id=\"servicelogs\">" + str + "</pre>";
        }
    }
   
    /**
     * @since 0.9.35
     */
    public String getBuiltBy() {
        File baseDir = _context.getBaseDir();
        File f = new File(new File(baseDir, "lib"), "i2p.jar");
        Attributes att = FileDumpHelper.attributes(f);
        if (att != null) {
            String s = FileDumpHelper.getAtt(att, "Built-By");
            if (s != null) {
                return s;
            }
        }
        return "Undefined";
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
            return "</td></tr><tr><td><p><i>" + _t("No log messages") + "</i></p>";
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = new StringBuilder(16*1024); 
        buf.append("</td></tr><tr><td><ul>");
        for (int i = msgs.size() - 1; i >= 0; i--) { 
            String msg = msgs.get(i);
            // don't display the dup message if it is last
            if (i == 0 && msg.contains("&darr;"))
                break;
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            msg = msg.replace("&amp;darr;", "&darr;");  // hack - undo the damage (LogWriter)
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
                if (msg.contains(_t("CRIT")))
                    color = "#cc0000";
                else if (msg.contains(_t("ERROR")))
                    color = "#ff3300";
                else if (msg.contains(_t("WARN")))
                   // color = "#ff00cc"; poor legibility on light backgrounds
                    color = "#bf00df";
                else if (msg.contains(_t("INFO")))
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

    /**
     * Read in the last few lines of a (newline delimited) textfile, or null if
     * the file doesn't exist.  
     *
     * Same as FileUtil.readTextFile but uses platform encoding,
     * not UTF-8, since the wrapper log cannot be configured:
     * http://stackoverflow.com/questions/14887690/how-do-i-get-the-tanuki-wrapper-log-files-to-be-utf-8-encoded
     *
     * Warning - this inefficiently allocates a StringBuilder of size maxNumLines*80,
     *           so don't make it too big.
     * Warning - converts \r\n to \n
     *
     * @param maxNumLines max number of lines (greater than zero)
     * @return string or null; does not throw IOException.
     * @since 0.9.11 modded from FileUtil.readTextFile()
     */
    private static String readTextFile(File f, int maxNumLines) {
        if (!f.exists()) return null;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            List<String> lines = new ArrayList<String>(maxNumLines);
            String line = null;
            while ( (line = in.readLine()) != null) {
                lines.add(line);
                if (lines.size() >= maxNumLines)
                    lines.remove(0);
            }
            StringBuilder buf = new StringBuilder(lines.size() * 80);
            for (int i = 0; i < lines.size(); i++) {
                buf.append(lines.get(i)).append('\n');
            }
            return buf.toString();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
}
