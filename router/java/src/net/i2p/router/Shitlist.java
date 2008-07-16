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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Routers are shitlisted only if none of our transports can talk to them
 * or their signed router info is completely screwy.  Individual transports
 * manage their own unreachable lists and do not generally add to the overall
 * shitlist.
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
    
    public final static long SHITLIST_DURATION_MS = 40*60*1000; // 40 minute shitlist
    public final static long SHITLIST_DURATION_MAX = 60*60*1000;
    public final static long SHITLIST_DURATION_FOREVER = 181l*24*60*60*1000; // will get rounded down to 180d on console
    
    public Shitlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Shitlist.class);
        _entries = new HashMap(32);
        _context.jobQueue().addJob(new Cleanup(_context));
    }
    
    private class Cleanup extends JobImpl {
        private List _toUnshitlist;
        public Cleanup(RouterContext ctx) {
            super(ctx);
            _toUnshitlist = new ArrayList(4);
        }
        public String getName() { return "Cleanup shitlist"; }
        public void runJob() {
            _toUnshitlist.clear();
            long now = getContext().clock().now();
            synchronized (_entries) {
                for (Iterator iter = _entries.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    Entry entry = (Entry)_entries.get(peer);
                    if (entry.expireOn <= now) {
                        iter.remove();
                        _toUnshitlist.add(peer);
                    }
                }
            }
            for (int i = 0; i < _toUnshitlist.size(); i++) {
                Hash peer = (Hash)_toUnshitlist.get(i);
                PeerProfile prof = _context.profileOrganizer().getProfile(peer);
                if (prof != null)
                    prof.unshitlist();
                _context.messageHistory().unshitlist(peer);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unshitlisting router (expired) " + peer.toBase64());
            }
            
            requeue(30*1000);
        }
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
        return shitlistRouter(peer, reason, null, false);
    }
    public boolean shitlistRouterForever(Hash peer, String reason) {
        return shitlistRouter(peer, reason, null, true);
    }
    public boolean shitlistRouter(Hash peer, String reason, String transport, boolean forever) {
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
        
        Entry e = new Entry();
        if (forever) {
            e.expireOn = _context.clock().now() + SHITLIST_DURATION_FOREVER;
        } else {
            long period = SHITLIST_DURATION_MS + _context.random().nextLong(SHITLIST_DURATION_MS);
            PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            if (prof != null) {
                period = SHITLIST_DURATION_MS << prof.incrementShitlists();
                period += _context.random().nextLong(period);
            }
       
            if (period > SHITLIST_DURATION_MAX)
                period = SHITLIST_DURATION_MAX;
            e.expireOn = _context.clock().now() + period;
        }
        e.cause = reason;
        e.transports = null;
        if (transport != null) {
            e.transports = new HashSet(1);
            e.transports.add(transport);
        }
        
        synchronized (_entries) {
            Entry old = (Entry)_entries.get(peer);
            if (old != null) {
                wasAlready = true;
                // take the oldest expiration and cause, combine transports
                if (old.expireOn > e.expireOn) {
                    e.expireOn = old.expireOn;
                    e.cause = old.cause;
                }
                if (e.transports != null) {
                    if (old.transports != null)
                        e.transports.addAll(old.transports);
                    else   
                        e.transports = null;
                }
            }
            _entries.put(peer, e);
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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Calling unshitlistRouter " + peer.toBase64()
                      + (transport != null ? "/" + transport : ""));
        boolean fully = false;
        Entry e;
        synchronized (_entries) {
            e = (Entry)_entries.remove(peer);
            if ( (e == null) || (e.transports == null) || (transport == null) || (e.transports.size() <= 1) ) {
                // fully unshitlisted
                fully = true;
            } else {
                e.transports.remove(transport);
                if (e.transports.size() <= 0)
                    fully = true;
                else
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
            if (_log.shouldLog(Log.INFO) && e != null)
                _log.info("Unshitlisting router " + peer.toBase64()
                          + (transport != null ? "/" + transport : ""));
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
            if (_log.shouldLog(Log.INFO))
                _log.info("Unshitlisting router (expired) " + peer.toBase64());
        }
        
        return rv;
    }
    
    public boolean isShitlistedForever(Hash peer) {
        Entry entry;
        synchronized (_entries) {
            entry = (Entry)_entries.get(peer);
        }
        return entry != null && entry.expireOn > _context.clock().now() + SHITLIST_DURATION_MAX;
    }

    class HashComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((Hash)l).toBase64().compareTo(((Hash)r).toBase64());
        }
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<h2>Shitlist</h2>");
        Map entries = new TreeMap(new HashComparator());
        
        synchronized (_entries) {
            entries.putAll(_entries);
        }
        buf.append("<ul>");
        
        int partial = 0;
        for (Iterator iter = entries.keySet().iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Entry entry = (Entry)entries.get(key);
            if ( (entry.transports != null) && (entry.transports.size() > 0) ) {
                partial++;
                continue;
            }
            buf.append("<li><b>").append(key.toBase64()).append("</b>");
            buf.append(" (<a href=\"netdb.jsp#").append(key.toBase64().substring(0, 6)).append("\">netdb</a>)");
            buf.append(" expiring in ");
            buf.append(DataHelper.formatDuration(entry.expireOn-_context.clock().now()));
            Set transports = entry.transports;
            if ( (transports != null) && (transports.size() > 0) )
                buf.append(" on the following transports: ").append(transports);
            if (entry.cause != null) {
                buf.append("<br />\n");
                buf.append(entry.cause);
            }
            buf.append(" (<a href=\"configpeer.jsp?peer=").append(key.toBase64()).append("#unsh\">unshitlist now</a>)");
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        if (partial > 0) {
            buf.append("<i>Partial shitlisted peers (only blocked on some transports): ");
            buf.append(partial);
            buf.append("</i>\n");
        }
        out.write(buf.toString());
        out.flush();
    }
}
