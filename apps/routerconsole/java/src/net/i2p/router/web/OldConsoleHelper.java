package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import net.i2p.router.RouterContext;

public class OldConsoleHelper extends HelperBase {
    public OldConsoleHelper() {}
    
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
                gen.generateStatsPage(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                gen.generateStatsPage(new OutputStreamWriter(baos));
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }
}
