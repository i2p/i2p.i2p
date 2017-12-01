package net.i2p.router.web.helpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterVersion;
import net.i2p.router.web.HelperBase;


public class OldConsoleHelper extends HelperBase {
    private boolean _full;

    public OldConsoleHelper() {}
    
    public void setFull(String f) {
        _full = f != null && f.length() > 0;
    }

    public String getConsole() {
        try {
            if (_out != null) {
                renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(2*1024);
                renderStatusHTML(new OutputStreamWriter(baos));
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }
    
    public String getStats() {
        StatsGenerator gen = new StatsGenerator(_context);
        try {
            if (_out != null) {
                gen.generateStatsPage(_out, _full);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                gen.generateStatsPage(new OutputStreamWriter(baos), _full);
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }

    /**
     *  this is for oldconsole.jsp, pretty much unused except as a way to get memory info,
     *  so let's comment out the rest, it is available elsewhere, and we don't really
     *  want to spend a minute rendering a multi-megabyte page in memory.
     *
     *  @since 0.9 moved from Router.java
     */
    private void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        
        // Please don't change the text or formatting, tino matches it in his scripts
        Hash h = _context.routerHash();
        if (h != null)
            buf.append("<b>Router: </b> ").append(h.toBase64()).append("<br>\n");
        buf.append("<b>As of: </b> ").append(new Date(_context.clock().now())).append("<br>\n");
        buf.append("<b>RouterUptime: </b> " ).append(DataHelper.formatDuration(_context.router().getUptime())).append(" <br>\n");
        buf.append("<b>Started on: </b> ").append(new Date(_context.router().getWhenStarted())).append("<br>\n");
        buf.append("<b>Clock offset: </b> ").append(_context.clock().getOffset()).append("ms (OS time: ").append(new Date(_context.clock().now() - _context.clock().getOffset())).append(")<br>\n");
        buf.append("<b>RouterVersion:</b> ").append(RouterVersion.FULL_VERSION).append(" / SDK: ").append(CoreVersion.VERSION).append("<br>\n"); 
        long tot = Runtime.getRuntime().totalMemory()/1024;
        long free = Runtime.getRuntime().freeMemory()/1024;
        buf.append("<b>Memory:</b> In use: ").append((tot-free)).append("KB Free: ").append(free).append("KB <br>\n"); 

        out.write(buf.toString());
        out.flush();
    }
    
}
