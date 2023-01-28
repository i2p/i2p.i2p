package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.jar.Attributes;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;

public class LogsHelper extends HelperBase {

    // cache so we only load once
    Attributes att;

    private static final String _jstlVersion = jstlVersion();

    private static final int MAX_WRAPPER_LINES = 250;
    private static final String PROP_LAST_WRAPPER = "routerconsole.lastWrapperLogEntry";


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
        return "<p>" + _t("File location") + ": <a href=\"/router.log\" target=\"_blank\">" +
               DataHelper.escapeHTML(_context.logManager().currentFile()) + "</a></p>" + str;
    }
    
    /**
     *
     */
    public String getCriticalLogs() {
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }

    /**
     *  Call before getLogs()
     *
     *  @return -1 if none
     *  @since 0.9.46
     */
    public int getLastMessageNumber() {
        UIMessages msgs = _context.logManager().getBuffer().getUIMessages();
        if (msgs.isEmpty())
            return -1;
        return msgs.getLastMessageID();
    }

    /**
     *  Call before getLogs(), getCriticalLogs(), or getLastMessageNumber()
     *  Side effect - calls logManager.flush()
     *
     *  @return -1 if none
     *  @since 0.9.46
     */
    public int getLastCriticalMessageNumber() {
        _context.logManager().flush();
        UIMessages msgs = _context.logManager().getBuffer().getCriticalUIMessages();
        if (msgs.isEmpty())
            return -1;
        return msgs.getLastMessageID();
    }

    /**
     *  @param n -1 for none
     *  @param crit -1 for none
     *  @param consoleNonce must match
     *  @since 0.9.46
     */
    public void clearThrough(int n, int crit, long wn, long wts, String wf, String consoleNonce) {
        if (!CSSHelper.getNonce().equals(consoleNonce))
            return;
        if (n >= 0)
            _context.logManager().getBuffer().getUIMessages().clearThrough(n);
        if (crit >= 0)
            _context.logManager().getBuffer().getCriticalUIMessages().clearThrough(crit);
        if (wn >= 0 && wts > 0 && wf != null) {
            // timestamp, last line number, filename
            String val = wts + "," + wn + "," + wf;
            if (!val.equals(_context.getProperty(PROP_LAST_WRAPPER)))
                _context.router().saveConfig(PROP_LAST_WRAPPER, val);
        }
    }

    /**
     *  last line number -1 on error
     *  @param obuf out parameter
     *  @return Long timestamp, Long last line number, String filename (escaped)
     */
    public Object[] getServiceLogs(StringBuilder obuf) {
        File f = ConfigServiceHandler.wrapperLogFile(_context);
        String str;
        long flastMod = f.lastModified();
        long lastMod = 0;
        long toSkip = 0;
        // timestamp, last line number, filename
        String prop = _context.getProperty(PROP_LAST_WRAPPER);
        if (prop != null) {
            String[] vals = DataHelper.split(prop, ",", 3);
            if (vals.length == 3) {
                if (vals[2].equals(f.getName())) {
                    try { lastMod = Long.parseLong(vals[0]); } catch (NumberFormatException nfe) {}
                    try { toSkip = Long.parseLong(vals[1]); } catch (NumberFormatException nfe) {}
                } else {
                    // file rotated
                    lastMod = 0;
                }
            }
        }
        if (lastMod > 0 && flastMod <= lastMod) {
            str = "";
            toSkip = -1;
        } else {
            // platform encoding or UTF8
            boolean utf8 = !_context.hasWrapper();
            StringBuilder buf = new StringBuilder(MAX_WRAPPER_LINES * 80);
            long ntoSkip = readTextFile(f, utf8, MAX_WRAPPER_LINES, toSkip, buf);
            if (ntoSkip < toSkip) {
                if (ntoSkip < 0) {
                    // error
                    str = null;
                } else {
                    // truncated?
                    str = "";
                }
                // remove old setting
                if (prop != null)
                    _context.router().saveConfig(PROP_LAST_WRAPPER, null);
            } else {
                str = buf.toString();
            }
            toSkip = ntoSkip;
        }
        String loc = DataHelper.escapeHTML(f.getAbsolutePath());
        if (str == null) {
            obuf.append("<p>").append(_t("File not found")).append(": <b><code>").append(loc).append("</code></b></p>");
            toSkip = -1;
        } else {
            obuf.append("<p>").append(_t("File location")).append(": <a href=\"/wrapper.log\" target=\"_blank\">")
                .append(loc).append("</a></p></td></tr>\n<tr><td>");
            if (str.length() > 0) {
                str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                obuf.append("<pre id=\"servicelogs\">").append(str).append("</pre>");
            } else {
                obuf.append("<p><i>").append(_t("No log messages")).append("</i></p></td></tr><td><tr>");
            }
        }
        Object[] rv = new Object[3];
        rv[0] = Long.valueOf(flastMod);
        rv[1] = Long.valueOf(toSkip);
        rv[2] = DataHelper.escapeHTML(f.getName()).replace(" ", "%20");
        return rv;
    }

    /**
     * @since 0.9.35
     */
    public String getBuiltBy() {
        return getAtt("Built-By");
    }

    /**
     * @since 0.9.58
     */
    public String getBuildDate() {
        return getAtt("Build-Date");
    }

    /**
     * @since 0.9.58
     */
    public String getRevision() {
        return getAtt("Base-Revision");
    }

    /**
     * @since 0.9.58 pulled out from above
     */
    private String getAtt(String a) {
        if (att == null) {
            File libDir = _context.getLibDir();
            File f = new File(libDir, "i2p.jar");
            att = FileDumpHelper.attributes(f);
        }
        if (att != null) {
            String s = FileDumpHelper.getAtt(att, a);
            if (s != null) {
                return s;
            }
        }
        return "Undefined";
    }

    private final static String NL = System.getProperty("line.separator");

    /** formats in forward order */
    private String formatMessages(List<String> msgs) {
        if (msgs.isEmpty())
            return "</td></tr><tr><td><p><i>" + _t("No log messages") + "</i></p>";
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = new StringBuilder(16*1024); 
        buf.append("</td></tr><tr><td><ul>");
        // newest first
        // for (int i = msgs.size() - 1; i >= 0; i--) { 
        // oldest first
        boolean displayed = false;
        for (int i = 0; i < msgs.size(); i++) { 
            String msg = msgs.get(i);
            // don't display the dup message if it is last
            //if (i == 0 && msg.contains("&darr;"))
            // don't display the dup message if it is first
            if (!displayed && msg.contains("&uarr;"))
                continue;
            displayed = true;
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            //msg = msg.replace("&amp;darr;", "&darr;");  // hack - undo the damage (LogWriter)
            msg = msg.replace("&amp;uarr;", "&uarr;");  // hack - undo the damage (LogWriter)
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
                if (msg.contains(_c("CRIT")))
                    color = "#cc0000";
                else if (msg.contains(_c("ERROR")))
                    color = "#ff3300";
                else if (msg.contains(_c("WARN")))
                   // color = "#ff00cc"; poor legibility on light backgrounds
                    color = "#bf00df";
                else if (msg.contains(_c("INFO")))
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
     * @param utf8 true for utf-8, false for system locale
     * @param maxNumLines max number of lines (greater than zero)
     * @param skipLines number of lines to skip, or zero
     * @param buf out parameter
     * @return -1 on failure, or number of lines in the file. Does not throw IOException.
     * @since 0.9.11 modded from FileUtil.readTextFile()
     */
    private static long readTextFile(File f, boolean utf8, int maxNumLines, long skipLines, StringBuilder buf) {
        if (!f.exists())
            return -1;
        BufferedReader in = null;
        try {
            if (utf8)
                in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            else
                in = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            long i = 0;
            while (i < skipLines) {
                // skip without readLine() to avoid object churn
                int c;
                do {
                    c = in.read();
                    if (c < 0)
                        return i;  // truncated
                } while (c != '\n');
                i++;
            }
            Queue<String> lines = new ArrayBlockingQueue<String>(maxNumLines);
            synchronized(lines) {
                String line = null;
                while ( (line = in.readLine()) != null) {
                    i++;
                    if (lines.size() >= maxNumLines)
                        lines.poll();
                    lines.offer(line);
                }
                for (String ln : lines) {
                    buf.append(ln).append('\n');
                }
            }
            return i;
        } catch (IOException ioe) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
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
