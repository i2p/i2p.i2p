package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;

import net.i2p.router.RouterContext;

public class PeerHelper {
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
    
    public PeerHelper() {}
    
    public void setOut(Writer out) { _out = out; }
    
    public String getPeerSummary() {
        try {
            _context.commSystem().renderStatusHTML(_out);
            _context.bandwidthLimiter().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
}
