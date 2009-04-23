package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import net.i2p.router.RouterContext;

public class NetDbHelper extends HelperBase {
    private String _routerPrefix;
    private boolean _full = false;
    private boolean _lease = false;
    
    public NetDbHelper() {}
    
    public void setRouter(String r) { _routerPrefix = r; }
    public void setFull(String f) { _full = "1".equals(f); }
    public void setLease(String l) { _lease = "1".equals(l); }
    
    public String getNetDbSummary() {
        try {
            if (_out != null) {
                if (_routerPrefix != null)
                    _context.netDb().renderRouterInfoHTML(_out, _routerPrefix);
                else if (_lease)
                    _context.netDb().renderLeaseSetHTML(_out);
                else
                    _context.netDb().renderStatusHTML(_out, _full);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                if (_routerPrefix != null)
                    _context.netDb().renderRouterInfoHTML(new OutputStreamWriter(baos), _routerPrefix);
                else if (_lease)
                    _context.netDb().renderLeaseSetHTML(new OutputStreamWriter(baos));
                else
                    _context.netDb().renderStatusHTML(new OutputStreamWriter(baos), _full);
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
