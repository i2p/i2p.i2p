package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class OldConsoleHelper extends HelperBase {
    private boolean _full;

    public OldConsoleHelper() {}
    
    public void setFull(String f) {
        _full = f != null && f.length() > 0;
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
}
