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
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.ConcurrentHashSet;
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
    private Map<Hash, Entry> _entries;
    
    private static class Entry {
        /** when it should expire, per the i2p clock */
        long expireOn;
        /** why they were shitlisted */
        String cause;
        /** what transports they were shitlisted for (String), or null for all transports */
        Set<String> transports;
    }
    
    public final static long SHITLIST_DURATION_MS = 20*60*1000;
    public final static long SHITLIST_DURATION_MAX = 30*60*1000;
    public final static long SHITLIST_DURATION_PARTIAL = 10*60*1000;
    public final static long SHITLIST_DURATION_FOREVER = 181l*24*60*60*1000; // will get rounded down to 180d on console
    public final static long SHITLIST_CLEANER_START_DELAY = SHITLIST_DURATION_PARTIAL;
    
    public Shitlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Shitlist.class);
        _entries = new ConcurrentHashMap(8);
        _context.jobQueue().addJob(new Cleanup(_context));
    }
    
    private class Cleanup extends JobImpl {
        private List<Hash> _toUnshitlist;
        public Cleanup(RouterContext ctx) {
            super(ctx);
            _toUnshitlist = new ArrayList(4);
            getTiming().setStartAfter(ctx.clock().now() + SHITLIST_CLEANER_START_DELAY);
        }
        public String getName() { return "Expire banned peers"; }
        public void runJob() {
            _toUnshitlist.clear();
            long now = getContext().clock().now();
            try {
                for (Iterator iter = _entries.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, Entry> e = (Map.Entry) iter.next();
                    if (e.getValue().expireOn <= now) {
                        iter.remove();
                        _toUnshitlist.add(e.getKey());
                    }
                }
            } catch (IllegalStateException ise) {} // next time...
            for (Hash peer : _toUnshitlist) {
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
        return _entries.size();
    }
    
    public boolean shitlistRouter(Hash peer) {
        return shitlistRouter(peer, null);
    }
    public boolean shitlistRouter(Hash peer, String reason) { return shitlistRouter(peer, reason, null); }
    public boolean shitlistRouter(Hash peer, String reason, String transport) {
        return shitlistRouter(peer, reason, transport, false);
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
            _log.info("Shitlisting router " + peer.toBase64() +
               ((transport != null) ? " on transport " + transport : ""), new Exception("Shitlist cause: " + reason));
        
        Entry e = new Entry();
        if (forever) {
            e.expireOn = _context.clock().now() + SHITLIST_DURATION_FOREVER;
        } else if (transport != null) {
            e.expireOn = _context.clock().now() + SHITLIST_DURATION_PARTIAL;
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
            e.transports = new ConcurrentHashSet(1);
            e.transports.add(transport);
        }
        
            Entry old = _entries.get(peer);
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
                    else {
                        e.transports = null;
                        e.cause = reason;
                    }
                }
            }
            _entries.put(peer, e);
        
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

        Entry e = _entries.remove(peer);
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

        Entry entry = _entries.get(peer);
        if (entry == null) {
            rv = false;
        } else if (entry.expireOn <= _context.clock().now()) {
            _entries.remove(peer);
            unshitlist = true;
            rv = false;
        } else if (entry.transports == null) {
            rv = true;
        } else {
            rv = entry.transports.contains(transport);
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
        Entry entry = _entries.get(peer);
        return entry != null && entry.expireOn > _context.clock().now() + SHITLIST_DURATION_MAX;
    }

    class HashComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((Hash)l).toBase64().compareTo(((Hash)r).toBase64());
        }
    }

    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h2>Banned Peers</h2>");
        Map<Hash, Entry> entries = new TreeMap(new HashComparator());
        
        entries.putAll(_entries);

        buf.append("<ul>");
        
        for (Map.Entry<Hash, Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Entry entry = e.getValue();
            buf.append("<li>").append(_context.commSystem().renderPeerHTML(key));
            buf.append(" expiring in ");
            buf.append(DataHelper.formatDuration(entry.expireOn-_context.clock().now()));
            Set transports = entry.transports;
            if ( (transports != null) && (transports.size() > 0) )
                buf.append(" on the following transport: ").append(transports);
            if (entry.cause != null) {
                buf.append("<br />\n");
                buf.append(entry.cause);
            }
            buf.append(" (<a href=\"configpeer.jsp?peer=").append(key.toBase64()).append("#unsh\">unban now</a>)");
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        out.write(buf.toString());
        out.flush();
    }
}
