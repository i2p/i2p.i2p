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

/**
 *  This is where we implement semi-Kademlia with the floodfills, by
 *  selecting floodfills closest to a given key for
 *  searches and stores.
 *
 */
class FloodfillPeerSelector extends PeerSelector {
    public FloodfillPeerSelector(RouterContext ctx) { super(ctx); }
    
    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Puts the floodfill peers that are directly connected first in the list.
     *
     * @return List of Hash for the peers selected
     */
    @Override
    public List<Hash> selectMostReliablePeers(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, true);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Does not prefer the floodfill peers that are directly connected.
     *
     * @return List of Hash for the peers selected
     */
    @Override
    public List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, false);
    }

    public List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets, boolean preferConnected) { 
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
    
    /**
     *  @return all floodfills not shitlisted forever. list will not include our own hash
     *  List is not sorted and not shuffled.
     */
    public List<Hash> selectFloodfillParticipants(KBucketSet kbuckets) {
        if (kbuckets == null) return new ArrayList();
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(null, null, 0);
        kbuckets.getAll(matches);
        return matches.getFloodfillParticipants();
    }
    
    /**
     *  @return all floodfills not shitlisted foreverx
     *  @param key the routing key
     *  @param maxNumRouters max to return
     *  Sorted by closest to the key if > maxNumRouters, otherwise not
     */
    public List<Hash> selectFloodfillParticipants(Hash key, int maxNumRouters, KBucketSet kbuckets) {
        List<Hash> ffs = selectFloodfillParticipants(kbuckets);
        if (ffs.size() <= maxNumRouters)
            return ffs; // unsorted
        TreeMap<BigInteger, Hash> sorted = new TreeMap();
        for (int i = 0; i < ffs.size(); i++) {
            Hash h = ffs.get(i);
            BigInteger diff = getDistance(key, h);
            sorted.put(diff, h);
        }
        List<Hash> rv = new ArrayList(maxNumRouters);
        for (int i = 0; i < maxNumRouters; i++) {
            rv.add(sorted.remove(sorted.firstKey()));
        }
        return rv;
    }
    
    private class FloodfillSelectionCollector implements SelectionCollector {
        private TreeMap<BigInteger, Hash> _sorted;
        private List<Hash>  _floodfillMatches;
        private Hash _key;
        private Set<Hash>  _toIgnore;
        private int _matches;
        private int _wanted;
        public FloodfillSelectionCollector(Hash key, Set<Hash> toIgnore, int wanted) {
            _key = key;
            _sorted = new TreeMap();
            _floodfillMatches = new ArrayList(8);
            _toIgnore = toIgnore;
            _matches = 0;
            _wanted = wanted;
        }

        /**
         *  @return unsorted list of all with the 'f' mark in their netdb
         *          except for shitlisted ones.
         */
        public List<Hash> getFloodfillParticipants() { return _floodfillMatches; }

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
        public List<Hash> get(int howMany) {
            return get(howMany, false);
        }

        /**
         *  @return list of all with the 'f' mark in their netdb except for shitlisted ones.
         *  The list is in 3 groups - unsorted (shuffled) within each group.
         *  Group 1: If preferConnected = true, the peers we are directly
         *           connected to, that meet the group 2 criteria
         *  Group 2: Netdb published less than 3h ago, no bad send in last 30m.
         *  Group 3: All others
         */
        public List<Hash> get(int howMany, boolean preferConnected) {
            Collections.shuffle(_floodfillMatches, _context.random());
            List<Hash> rv = new ArrayList(howMany);
            List<Hash> badff = new ArrayList(howMany);
            List<Hash> unconnectedff = new ArrayList(howMany);
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
