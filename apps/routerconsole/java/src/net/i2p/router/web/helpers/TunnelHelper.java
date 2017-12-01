package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;


public class TunnelHelper extends HelperBase {
    public TunnelHelper() {}
    
    public String getTunnelSummary() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderStatusHTML(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderer.renderStatusHTML(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
