package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Manage in memory the routers we are oh so fond of.
 * This needs to get a little bit more sophisticated... currently there is no
 * way out of the shitlist
 *
 */
public class Shitlist {
    private Log _log;
    private RouterContext _context;
    private Map _shitlist; // H(routerIdent) --> Date
    
    public final static long SHITLIST_DURATION_MS = 4*60*1000; // 4 minute shitlist
    
    public Shitlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Shitlist.class);
        _shitlist = new HashMap(100);
    }
    
    public boolean shitlistRouter(Hash peer) {
        if (peer == null) return false;
        if (_context.routerHash().equals(peer)) {
            _log.error("wtf, why did we try to shitlist ourselves?", new Exception("shitfaced"));
            return false;
        }
        boolean wasAlready = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Shitlisting router " + peer.toBase64(), new Exception("Shitlist cause"));
        
        synchronized (_shitlist) {
            Date oldDate = (Date)_shitlist.put(peer, new Date(_context.clock().now()));
            wasAlready = (null == oldDate);
        }
        
        _context.netDb().fail(peer);
        _context.tunnelManager().peerFailed(peer);
        return wasAlready;
    }
    
    public void unshitlistRouter(Hash peer) {
        if (peer == null) return;
        _log.info("Unshitlisting router " + peer.toBase64());
        synchronized (_shitlist) {
            _shitlist.remove(peer);
        }
    }
    
    public boolean isShitlisted(Hash peer) {
        Date shitlistDate = null;
        synchronized (_shitlist) {
            shitlistDate = (Date)_shitlist.get(peer);
        }
        if (shitlistDate == null) return false;
        
        // check validity
        if (shitlistDate.getTime() > _context.clock().now() - SHITLIST_DURATION_MS) {
            return true;
        } else {
            unshitlistRouter(peer);
            return false;
        }
    }
    
    public String renderStatusHTML() {
        StringBuffer buf = new StringBuffer();
        buf.append("<h2>Shitlist</h2>");
        Map shitlist = new HashMap();
        synchronized (_shitlist) {
            shitlist.putAll(_shitlist);
        }
        buf.append("<ul>");
        
        long limit = _context.clock().now() - SHITLIST_DURATION_MS;
        
        for (Iterator iter = shitlist.keySet().iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Date shitDate = (Date)shitlist.get(key);
            if (shitDate.getTime() < limit)
                unshitlistRouter(key);
            else
                buf.append("<li><b>").append(key.toBase64()).append("</b> was shitlisted on ").append(shitDate).append("</li>\n");
        }
        buf.append("</ul>\n");
        return buf.toString();
    }
}
