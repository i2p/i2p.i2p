package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import net.i2p.router.RouterContext;

public class NetDbHelper {
    private RouterContext _context;
    private Writer _out;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    public NetDbHelper() {}
    
    public void setWriter(Writer writer) { _out = writer; }
    
    public String getNetDbSummary() {
        try {
            if (_out != null) {
                _context.netDb().renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                _context.netDb().renderStatusHTML(new OutputStreamWriter(baos));
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
