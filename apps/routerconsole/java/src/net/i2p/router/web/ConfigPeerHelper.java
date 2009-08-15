package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class ConfigPeerHelper extends HelperBase {
    public ConfigPeerHelper() {}
    
    public String getBlocklistSummary() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        try {
            _context.blocklist().renderStatusHTML(new OutputStreamWriter(baos));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new String(baos.toByteArray());
    }
}
