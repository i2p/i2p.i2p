package net.i2p.router.web;

import java.io.IOException;


public class PeerHelper extends HelperBase {
    private int _sortFlags;
    private String _urlBase;
    
    public PeerHelper() {}
    
    public void setSort(String flags) {
        if (flags != null) {
            try {
                _sortFlags = Integer.parseInt(flags);
            } catch (NumberFormatException nfe) {
                _sortFlags = 0;
            }
        } else {
            _sortFlags = 0;
        }
    }
    public void setUrlBase(String base) { _urlBase = base; }
    
    public String getPeerSummary() {
        try {
            _context.commSystem().renderStatusHTML(_out, _urlBase, _sortFlags);
            // boring and not worth translating
            //_context.bandwidthLimiter().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
}
