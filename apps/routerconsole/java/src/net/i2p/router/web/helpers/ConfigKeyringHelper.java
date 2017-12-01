package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;

import net.i2p.router.web.HelperBase;


public class ConfigKeyringHelper extends HelperBase {
    public ConfigKeyringHelper() {}
    
    public String getSummary() {
        StringWriter sw = new StringWriter(4*1024);
        try {
            _context.keyRing().renderStatusHTML(sw);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return sw.toString();
    }
}
