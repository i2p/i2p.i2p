package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import net.i2p.router.RouterContext;
import net.i2p.router.admin.StatsGenerator;

public class OldConsoleHelper {
    private RouterContext _context;
    private Writer _out;
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
    
    public OldConsoleHelper() {}
    
    public void setWriter(Writer writer) { 
        _out = writer; 
    }
    
    public String getConsole() {
        try {
            if (_out != null) {
                _context.router().renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(128*1024);
                _context.router().renderStatusHTML(new OutputStreamWriter(baos));
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error rending the console</b>";
        }
    }
    
    public String getStats() {
        StatsGenerator gen = new StatsGenerator(_context);
        try {
            if (_out != null) {
                gen.generateStatsPage(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                gen.generateStatsPage(new OutputStreamWriter(baos));
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error rending the console</b>";
        }
    }
}
