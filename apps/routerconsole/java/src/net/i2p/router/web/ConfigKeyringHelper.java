package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class ConfigKeyringHelper extends HelperBase {
    public ConfigKeyringHelper() {}
    
    public String getSummary() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        try {
            _context.keyRing().renderStatusHTML(new OutputStreamWriter(baos));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new String(baos.toByteArray());
    }
}
