package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

class FloodfillPeerSelector extends PeerSelector {
    public FloodfillPeerSelector(RouterContext ctx) { super(ctx); }
    
    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     *
     * @return List of Hash for the peers selected
     */
    @Override
    public List selectMostReliablePeers(Hash key, int maxNumRouters, Set peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, true);
    }

    @Override
    public List selectNearestExplicitThin(Hash key, int maxNumRouters, Set peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, false);
    }

    public List selectNearestExplicitThin(Hash key, int maxNumRouters, Set peersToIgnore, KBucketSet kbuckets, boolean preferConnected) { 
        if (peersToIgnore == null)
            peersToIgnore = new HashSet(1);
        peersToIgnore.add(_context.routerHash());
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(key, peersToIgnore, maxNumRouters);
        if (kbuckets == null) return new ArrayList();
        kbuckets.getAll(matches);
        List rv = matches.get(maxNumRouters, preferConnected);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching for " + maxNumRouters + " peers close to " + key + ": " 
                       + rv + " (not including " + peersToIgnore + ") [allHashes.size = " 
                       + matches.size() + "]", new Exception("Search by"));
        return rv;
    }
    
    /** Returned list will not include our own hash */
    public List selectFloodfillParticipants(KBucketSet kbuckets) {
        if (kbuckets == null) return new ArrayList();
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(null, null, 0);
        kbuckets.getAll(matches);
        return matches.getFloodfillParticipants();
    }
    
    private class FloodfillSelectionCollector implements SelectionCollector {
        private TreeMap _sorted;
        private List _floodfillMatches;
        private Hash _key;
        private Set _toIgnore;
        private int _matches;
        private int _wanted;
        public FloodfillSelectionCollector(Hash key, Set toIgnore, int wanted) {
            _key = key;
            _sorted = new TreeMap();
            _floodfillMatches = new ArrayList(1);
            _toIgnore = toIgnore;
            _matches = 0;
            _wanted = wanted;
        }
        public List getFloodfillParticipants() { return _floodfillMatches; }
        private static final int EXTRA_MATCHES = 100;
        public void add(Hash entry) {
            //if (_context.profileOrganizer().isFailing(entry))
            //    return;
            if ( (_toIgnore != null) && (_toIgnore.contains(entry)) )
                return;
            if (entry.equals(_context.routerHash()))
                return;
            // it isn't direct, so who cares if they're shitlisted
            //if (_context.shitlist().isShitlisted(entry))
            //    return;
            // ... unless they are really bad
            if (_context.shitlist().isShitlistedForever(entry))
                return;
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            //if (info == null)
            //    return;
            
            if (info != null && FloodfillNetworkDatabaseFacade.isFloodfill(info)) {
                _floodfillMatches.add(entry);
            } else {
                // This didn't really work because we stopped filling up when _wanted == _matches,
                // thus we don't add and sort the whole db to find the closest.
                // So we keep going for a while. This, together with periodically shuffling the
                // KBucket (see KBucketImpl.add()) makes exploration work well.
                if ( (!SearchJob.onlyQueryFloodfillPeers(_context)) && (_wanted + EXTRA_MATCHES > _matches) && (_key != null) ) {
                    BigInteger diff = getDistance(_key, entry);
                    _sorted.put(diff, entry);
                } else {
                    return;
                }
            }
            _matches++;
        }
        /** get the first $howMany entries matching */
        public List get(int howMany) {
            return get(howMany, false);
        }

        public List get(int howMany, boolean preferConnected) {
            Collections.shuffle(_floodfillMatches, _context.random());
            List rv = new ArrayList(howMany);
            List badff = new ArrayList(howMany);
            List unconnectedff = new ArrayList(howMany);
            int found = 0;
            long now = _context.clock().now();
            // Only add in "good" floodfills here...
            // Let's say published in last 3h and no failed sends in last 30m
            // (Forever shitlisted ones are excluded in add() above)
            for (int i = 0; found < howMany && i < _floodfillMatches.size(); i++) {
                Hash entry = (Hash) _floodfillMatches.get(i);
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
                if (info != null && now - info.getPublished() > 3*60*60*1000) {
                    badff.add(entry);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Skipping, published a while ago: " + entry);
                } else {
                    PeerProfile prof = _context.profileOrganizer().getProfile(entry);
                    if (prof != null && now - prof.getLastSendFailed() < 30*60*1000) {
                        badff.add(entry);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Skipping, recent failed send: " + entry);
                    } else if (preferConnected && !_context.commSystem().isEstablished(entry)) {
                        unconnectedff.add(entry);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Skipping, unconnected: " + entry);
                    } else {
                        rv.add(entry);
                        found++;
                    }
                }
            }
            // Put the unconnected floodfills after the connected floodfills
            for (int i = 0; found < howMany && i < unconnectedff.size(); i++) {
                rv.add(unconnectedff.get(i));
                found++;
            }
            // Put the "bad" floodfills at the end of the floodfills but before the kademlias
            for (int i = 0; found < howMany && i < badff.size(); i++) {
                rv.add(badff.get(i));
                found++;
            }
            for (int i = rv.size(); i < howMany; i++) {
                if (_sorted.size() <= 0)
                    break;
                rv.add(_sorted.remove(_sorted.firstKey()));
            }
            return rv;
        }
        public int size() { return _matches; }
    }
}
