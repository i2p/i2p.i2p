package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class TunnelHelper extends HelperBase {
    public TunnelHelper() {}
    
    public String getTunnelSummary() {
        TunnelRenderer renderer = new TunnelRenderer(_context);
        try {
            if (_out != null) {
                renderer.renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                renderer.renderStatusHTML(new OutputStreamWriter(baos));
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
