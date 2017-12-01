package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;


public class ConfigPeerHelper extends HelperBase {
    public ConfigPeerHelper() {}
    
    public String getBlocklistSummary() {
        StringWriter sw = new StringWriter(4*1024);
        try {
            _context.blocklist().renderStatusHTML(sw);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return sw.toString();
    }
}
