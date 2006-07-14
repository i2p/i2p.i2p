package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import net.i2p.data.DataHelper;

import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;
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
    private Map _entries;
    
    private class Entry {
        /** when it should expire, per the i2p clock */
        long expireOn;
        /** why they were shitlisted */
        String cause;
        /** what transports they were shitlisted for (String), or null for all transports */
        Set transports;
    }
    
    public final static long SHITLIST_DURATION_MS = 4*60*1000; // 4 minute shitlist
    
    public Shitlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Shitlist.class);
        _entries = new HashMap(32);
    }
    
    public int getRouterCount() {
        synchronized (_entries) {
            return _entries.size();
        }
    }
    
    public boolean shitlistRouter(Hash peer) {
        return shitlistRouter(peer, null);
    }
    public boolean shitlistRouter(Hash peer, String reason) { return shitlistRouter(peer, reason, null); }
    public boolean shitlistRouter(Hash peer, String reason, String transport) {
        if (peer == null) {
            _log.error("wtf, why did we try to shitlist null?", new Exception("shitfaced"));
            return false;
        }
        if (_context.routerHash().equals(peer)) {
            _log.error("wtf, why did we try to shitlist ourselves?", new Exception("shitfaced"));
            return false;
        }
        boolean wasAlready = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Shitlisting router " + peer.toBase64(), new Exception("Shitlist cause: " + reason));
        
        long period = SHITLIST_DURATION_MS + _context.random().nextLong(SHITLIST_DURATION_MS);
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        if (prof != null) {
            period = SHITLIST_DURATION_MS << prof.incrementShitlists();
            period += _context.random().nextLong(period);
        }
        
        if (period > 60*60*1000)
            period = 60*60*1000;
        
        Entry e = new Entry();
        e.expireOn = _context.clock().now() + period;
        e.cause = reason;
        e.transports = null;
        if (transport != null) {
            e.transports = new HashSet(1);
            e.transports.add(transport);
        }
        
        synchronized (_entries) {
            Entry old = (Entry)_entries.put(peer, e);
            if (old != null) {
                wasAlready = true;
                _entries.put(peer, old);
                if (e.transports == null) {
                    old.transports = null;
                } else if (old.transports != null) {
                    old.transports.addAll(e.transports);
                }
                e = old;
            }
        }
        
        if (transport == null) {
            // we hate the peer on *any* transport
            _context.netDb().fail(peer);
        }
        //_context.tunnelManager().peerFailed(peer);
        //_context.messageRegistry().peerFailed(peer);
        if (!wasAlready)
            _context.messageHistory().shitlist(peer, reason);
        return wasAlready;
    }
    
    public void unshitlistRouter(Hash peer) {
        unshitlistRouter(peer, true);
    }
    private void unshitlistRouter(Hash peer, boolean realUnshitlist) { unshitlistRouter(peer, realUnshitlist, null); }
    public void unshitlistRouter(Hash peer, String transport) { unshitlistRouter(peer, true, transport); }
    private void unshitlistRouter(Hash peer, boolean realUnshitlist, String transport) {
        if (peer == null) return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Unshitlisting router " + peer.toBase64()
                      + (transport != null ? "/" + transport : ""));
        boolean fully = false;
        synchronized (_entries) {
            Entry e = (Entry)_entries.remove(peer);
            if ( (e == null) || (e.transports == null) || (transport == null) || (e.transports.size() <= 1) ) {
                // fully unshitlisted
                fully = true;
            } else {
                e.transports.remove(transport);
                _entries.put(peer, e);
            }
        }
        if (fully) {
            if (realUnshitlist) {
                PeerProfile prof = _context.profileOrganizer().getProfile(peer);
                if (prof != null)
                    prof.unshitlist();
            }
            _context.messageHistory().unshitlist(peer);
        }
    }
    
    public boolean isShitlisted(Hash peer) { return isShitlisted(peer, null); }
    public boolean isShitlisted(Hash peer, String transport) {
        boolean rv = false;
        boolean unshitlist = false;
        synchronized (_entries) {
            Entry entry = (Entry)_entries.get(peer);
            if (entry == null) {
                rv = false;
            } else {
                if (entry.expireOn <= _context.clock().now()) {
                    _entries.remove(peer);
                    unshitlist = true;
                    rv = false;
                } else {
                    if (entry.transports == null) {
                        rv = true;
                    } else if (entry.transports.contains(transport)) {
                        rv = true;
                    } else {
                        rv = false;
                    }
                }
            }
        }
        
        if (unshitlist) {
            PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            if (prof != null)
                prof.unshitlist();
            _context.messageHistory().unshitlist(peer);
        }
        
        return rv;
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<h2>Shitlist</h2>");
        Map entries = null;
        
        synchronized (_entries) {
            entries = new HashMap(_entries);
        }
        buf.append("<ul>");
        
        for (Iterator iter = entries.keySet().iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Entry entry = (Entry)entries.get(key);
            buf.append("<li><b>").append(key.toBase64()).append("</b>");
            buf.append(" <a href=\"netdb.jsp#").append(key.toBase64().substring(0, 6)).append("\">(?)</a>");
            buf.append(" expiring in ");
            buf.append(DataHelper.formatDuration(entry.expireOn-_context.clock().now()));
            Set transports = entry.transports;
            if ( (transports != null) && (transports.size() > 0) )
                buf.append(" on the following transports: ").append(transports);
            if (entry.cause != null) {
                buf.append("<br />\n");
                buf.append(entry.cause);
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        out.write(buf.toString());
        out.flush();
    }
}
