package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class NetDbHelper extends HelperBase {
    private String _routerPrefix;
    private int _full;
    private boolean _lease = false;
    
    public NetDbHelper() {}
    
    public void setRouter(String r) { _routerPrefix = r; }
    public void setFull(String f) {
        try {
            _full = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }
    public void setLease(String l) { _lease = "1".equals(l); }
    
    public String getNetDbSummary() {
        NetDbRenderer renderer = new NetDbRenderer(_context);
        try {
            if (_out != null) {
                if (_routerPrefix != null)
                    renderer.renderRouterInfoHTML(_out, _routerPrefix);
                else if (_lease)
                    renderer.renderLeaseSetHTML(_out);
                else
                    renderer.renderStatusHTML(_out, _full);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                if (_routerPrefix != null)
                    renderer.renderRouterInfoHTML(new OutputStreamWriter(baos), _routerPrefix);
                else if (_lease)
                    renderer.renderLeaseSetHTML(new OutputStreamWriter(baos));
                else
                    renderer.renderStatusHTML(new OutputStreamWriter(baos), _full);
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
