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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Routers are banlisted only if none of our transports can talk to them
 * or their signed router info is completely screwy.  Individual transports
 * manage their own unreachable lists and do not generally add to the overall
 * banlist.
 */
public class Banlist {
    private final Log _log;
    private final RouterContext _context;
    private final Map<Hash, Entry> _entries;
    
    public static class Entry {
        /** when it should expire, per the i2p clock */
        public long expireOn;
        /** why they were banlisted */
        public String cause;
        /** separate code so cause can contain {0} for translation */
        public String causeCode;
        /** what transports they were banlisted for (String), or null for all transports */
        public Set<String> transports;
    }
    
    /**
     *  Don't make this too long as the failure may be transient
     *  due to connection limits.
     */
    public final static long BANLIST_DURATION_MS = 7*60*1000;
    public final static long BANLIST_DURATION_MAX = 30*60*1000;
    public final static long BANLIST_DURATION_PARTIAL = 10*60*1000;
    public final static long BANLIST_DURATION_FOREVER = 181l*24*60*60*1000; // will get rounded down to 180d on console
    public final static long BANLIST_CLEANER_START_DELAY = BANLIST_DURATION_PARTIAL;
    
    public Banlist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Banlist.class);
        _entries = new ConcurrentHashMap<Hash, Entry>(16);
        _context.jobQueue().addJob(new Cleanup(_context));
    }
    
    private class Cleanup extends JobImpl {
        private List<Hash> _toUnbanlist;
        public Cleanup(RouterContext ctx) {
            super(ctx);
            _toUnbanlist = new ArrayList<Hash>(4);
            getTiming().setStartAfter(ctx.clock().now() + BANLIST_CLEANER_START_DELAY);
        }
        public String getName() { return "Expire banned peers"; }
        public void runJob() {
            _toUnbanlist.clear();
            long now = getContext().clock().now();
            try {
                for (Iterator<Map.Entry<Hash, Entry>> iter = _entries.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, Entry> e = iter.next();
                    if (e.getValue().expireOn <= now) {
                        iter.remove();
                        _toUnbanlist.add(e.getKey());
                    }
                }
            } catch (IllegalStateException ise) {} // next time...
            for (Hash peer : _toUnbanlist) {
                //PeerProfile prof = _context.profileOrganizer().getProfile(peer);
                //if (prof != null)
                //    prof.unbanlist();
                _context.messageHistory().unbanlist(peer);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unbanlisting router (expired) " + peer.toBase64());
            }
            
            requeue(30*1000);
        }
    }
    
    public int getRouterCount() {
        return _entries.size();
    }
    
    /**
     *  For BanlistRenderer in router console.
     *  Note - may contain expired entries.
     */
    public Map<Hash, Entry> getEntries() {
        return Collections.unmodifiableMap(_entries);
    }
    
    public boolean banlistRouter(Hash peer) {
        return banlistRouter(peer, null);
    }

    public boolean banlistRouter(Hash peer, String reason) { return banlistRouter(peer, reason, null); }

    /** ick have to put the reasonCode in the front to avoid ambiguity */
    public boolean banlistRouter(String reasonCode, Hash peer, String reason) {
        return banlistRouter(peer, reason, reasonCode, null, false);
    }

    public boolean banlistRouter(Hash peer, String reason, String transport) {
        return banlistRouter(peer, reason, transport, false);
    }

    public boolean banlistRouterForever(Hash peer, String reason) {
        return banlistRouter(peer, reason, null, true);
    }

    public boolean banlistRouterForever(Hash peer, String reason, String reasonCode) {
        return banlistRouter(peer, reason, reasonCode, null, true);
    }

    public boolean banlistRouter(Hash peer, String reason, String transport, boolean forever) {
        return banlistRouter(peer, reason, null, transport, forever);
    }

    private boolean banlistRouter(Hash peer, String reason, String reasonCode, String transport, boolean forever) {
        if (peer == null) {
            _log.error("wtf, why did we try to banlist null?", new Exception("banfaced"));
            return false;
        }
        if (_context.routerHash().equals(peer)) {
            _log.error("wtf, why did we try to banlist ourselves?", new Exception("banfaced"));
            return false;
        }
        boolean wasAlready = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Banlisting router " + peer.toBase64() +
               ((transport != null) ? " on transport " + transport : ""), new Exception("Banlist cause: " + reason));
        
        Entry e = new Entry();
        if (forever) {
            e.expireOn = _context.clock().now() + BANLIST_DURATION_FOREVER;
        } else if (transport != null) {
            e.expireOn = _context.clock().now() + BANLIST_DURATION_PARTIAL;
        } else {
            long period = BANLIST_DURATION_MS + _context.random().nextLong(BANLIST_DURATION_MS / 4);
            //PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            //if (prof != null) {
            //    period = BANLIST_DURATION_MS << prof.incrementBanlists();
            //    period += _context.random().nextLong(period);
            //}
       
            if (period > BANLIST_DURATION_MAX)
                period = BANLIST_DURATION_MAX;
            e.expireOn = _context.clock().now() + period;
        }
        e.cause = reason;
        e.causeCode = reasonCode;
        e.transports = null;
        if (transport != null) {
            e.transports = new ConcurrentHashSet<String>(2);
            e.transports.add(transport);
        }
        
            Entry old = _entries.get(peer);
            if (old != null) {
                wasAlready = true;
                // take the oldest expiration and cause, combine transports
                if (old.expireOn > e.expireOn) {
                    e.expireOn = old.expireOn;
                    e.cause = old.cause;
                    e.causeCode = old.causeCode;
                }
                if (e.transports != null) {
                    if (old.transports != null)
                        e.transports.addAll(old.transports);
                    else {
                        e.transports = null;
                        e.cause = reason;
                        e.causeCode = reasonCode;
                    }
                }
            }
            _entries.put(peer, e);
        
        if (transport == null) {
            // we hate the peer on *any* transport
            _context.netDb().fail(peer);
            _context.tunnelManager().fail(peer);
        }
        //_context.tunnelManager().peerFailed(peer);
        //_context.messageRegistry().peerFailed(peer);
        if (!wasAlready)
            _context.messageHistory().banlist(peer, reason);
        return wasAlready;
    }
    
    public void unbanlistRouter(Hash peer) {
        unbanlistRouter(peer, true);
    }

    private void unbanlistRouter(Hash peer, boolean realUnbanlist) { unbanlistRouter(peer, realUnbanlist, null); }

    public void unbanlistRouter(Hash peer, String transport) { unbanlistRouter(peer, true, transport); }

    private void unbanlistRouter(Hash peer, boolean realUnbanlist, String transport) {
        if (peer == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Calling unbanlistRouter " + peer.toBase64()
                      + (transport != null ? "/" + transport : ""));
        boolean fully = false;

        Entry e = _entries.remove(peer);
        if ( (e == null) || (e.transports == null) || (transport == null) || (e.transports.size() <= 1) ) {
            // fully unbanlisted
            fully = true;
        } else {
            e.transports.remove(transport);
            if (e.transports.isEmpty())
                fully = true;
            else
                _entries.put(peer, e);
        }

        if (fully) {
            //if (realUnbanlist) {
            //    PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            //    if (prof != null)
            //        prof.unbanlist();
            //}
            _context.messageHistory().unbanlist(peer);
            if (_log.shouldLog(Log.INFO) && e != null)
                _log.info("Unbanlisting router " + peer.toBase64()
                          + (transport != null ? "/" + transport : ""));
        }
    }
    
    public boolean isBanlisted(Hash peer) { return isBanlisted(peer, null); }

    public boolean isBanlisted(Hash peer, String transport) {
        boolean rv = false;
        boolean unbanlist = false;

        Entry entry = _entries.get(peer);
        if (entry == null) {
            rv = false;
        } else if (entry.expireOn <= _context.clock().now()) {
            _entries.remove(peer);
            unbanlist = true;
            rv = false;
        } else if (entry.transports == null) {
            rv = true;
        } else {
            rv = entry.transports.contains(transport);
        }
        
        if (unbanlist) {
            //PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            //if (prof != null)
            //    prof.unbanlist();
            _context.messageHistory().unbanlist(peer);
            if (_log.shouldLog(Log.INFO))
                _log.info("Unbanlisting router (expired) " + peer.toBase64());
        }
        
        return rv;
    }
    
    public boolean isBanlistedForever(Hash peer) {
        Entry entry = _entries.get(peer);
        return entry != null && entry.expireOn > _context.clock().now() + BANLIST_DURATION_MAX;
    }

    /** @deprecated moved to router console */
    public void renderStatusHTML(Writer out) throws IOException {
    }
}
