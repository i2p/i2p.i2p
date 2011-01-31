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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *  Mostly unused, see overrides in FloodfillPeerSelector
 */
class PeerSelector {
    protected final Log _log;
    protected final RouterContext _context;
    
    public PeerSelector(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
    }
    
    /**
     * UNUSED - See FloodfillPeerSelector override
     * Search through the kbucket set to find the most reliable peers close to the
     * given key, skipping all of the ones already checked
     * List will not include our own hash.
     *
     * @return ordered list of Hash objects
     */
    List<Hash> selectMostReliablePeers(Hash key, int numClosest, Set<Hash> alreadyChecked, KBucketSet kbuckets) {
        // get the peers closest to the key
        return selectNearestExplicit(key, numClosest, alreadyChecked, kbuckets);
    }
    
    /**
     * Ignore KBucket ordering and do the XOR explicitly per key.  Runs in O(n*log(n))
     * time (n=routing table size with c ~ 32 xor ops).  This gets strict ordering 
     * on closest
     * List will not include our own hash.
     *
     * @return List of Hash for the peers selected, ordered by bucket (but intra bucket order is not defined)
     */
    List<Hash> selectNearestExplicit(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) {
        //if (true)
            return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets);
        
/******
        if (peersToIgnore == null)
            peersToIgnore = new HashSet(1);
        peersToIgnore.add(_context.routerHash());
        Set allHashes = kbuckets.getAll(peersToIgnore);
        removeFailingPeers(allHashes);
        Map diffMap = new HashMap(allHashes.size());
        for (Iterator iter = allHashes.iterator(); iter.hasNext(); ) {
            Hash cur = (Hash)iter.next();
            BigInteger diff = getDistance(key, cur);
            diffMap.put(diff, cur);
        }
        // n*log(n)
        Map sortedMap = new TreeMap(diffMap);
        List peerHashes = new ArrayList(maxNumRouters);
        for (Iterator iter = sortedMap.values().iterator(); iter.hasNext(); ) {
            if (peerHashes.size() >= maxNumRouters) break;
            peerHashes.add(iter.next());
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching for " + maxNumRouters + " peers close to " + key + ": " 
                       + peerHashes + " (not including " + peersToIgnore + ") [allHashes.size = " 
                       + allHashes.size() + "]");
        return peerHashes;
******/
    }
    
    /**
     * UNUSED - See FloodfillPeerSelector override
     * Ignore KBucket ordering and do the XOR explicitly per key.  Runs in O(n*log(n))
     * time (n=routing table size with c ~ 32 xor ops).  This gets strict ordering 
     * on closest
     * List will not include our own hash.
     *
     * @return List of Hash for the peers selected, ordered by bucket (but intra bucket order is not defined)
     */
    List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) {
        if (peersToIgnore == null)
            peersToIgnore = new HashSet(1);
        peersToIgnore.add(_context.routerHash());
        MatchSelectionCollector matches = new MatchSelectionCollector(key, peersToIgnore);
        kbuckets.getAll(matches);
        List rv = matches.get(maxNumRouters);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching for " + maxNumRouters + " peers close to " + key + ": " 
                       + rv + " (not including " + peersToIgnore + ") [allHashes.size = " 
                       + matches.size() + "]");
        return rv;
    }
    
    /** UNUSED */
    private class MatchSelectionCollector implements SelectionCollector {
        private TreeMap<BigInteger, Hash> _sorted;
        private Hash _key;
        private Set<Hash> _toIgnore;
        private int _matches;
        public MatchSelectionCollector(Hash key, Set<Hash> toIgnore) {
            _key = key;
            _sorted = new TreeMap();
            _toIgnore = toIgnore;
            _matches = 0;
        }
        public void add(Hash entry) {
            // deadlock seen here, and we don't mark profiles failing anymore
            //if (_context.profileOrganizer().isFailing(entry))
            //    return;
            if (_toIgnore.contains(entry))
                return;
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            if (info == null)
                return;
            if (info.getIdentity().isHidden())
                return;
            
            BigInteger diff = HashDistance.getDistance(_key, entry);
            _sorted.put(diff, entry);
            _matches++;
        }
        /** get the first $howMany entries matching */
        public List<Hash> get(int howMany) {
            List<Hash> rv = new ArrayList(howMany);
            for (int i = 0; i < howMany; i++) {
                if (_sorted.isEmpty())
                    break;
                rv.add(_sorted.remove(_sorted.firstKey()));
            }
            return rv;
        }
        public int size() { return _matches; }
    }
        
    /** 
     * strip out all of the peers that are failing
     *
     */
/********
    private void removeFailingPeers(Set peerHashes) {
        List failing = null;
        for (Iterator iter = peerHashes.iterator(); iter.hasNext(); ) {
            Hash cur = (Hash)iter.next();
            if (_context.profileOrganizer().isFailing(cur)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer " + cur.toBase64() + " is failing, don't include them in the peer selection");
                if (failing == null)
                    failing = new ArrayList(4);
                failing.add(cur);
            } else if (_context.profileOrganizer().peerSendsBadReplies(cur)) {
                if (true) {
                    _log.warn("Peer " + cur.toBase64() + " sends us bad replies (but we still query them)");
                } else {
                    if (failing == null)
                        failing = new ArrayList(4);
                    failing.add(cur);
                    if (_log.shouldLog(Log.WARN)) {
                        PeerProfile profile = _context.profileOrganizer().getProfile(cur);
                        if (profile != null) {
                            RateStat invalidReplyRateStat = profile.getDBHistory().getInvalidReplyRate();
                            Rate invalidReplyRate = invalidReplyRateStat.getRate(60*60*1000l);
                            _log.warn("Peer " + cur.toBase64() + " sends us bad replies: current hour: " 
                                      + invalidReplyRate.getCurrentEventCount() + " and last hour: " 
                                      + invalidReplyRate.getLastEventCount() + ":\n" + invalidReplyRate.toString());
                        }
                    }
                }
            }
        }
        if (failing != null)
            peerHashes.removeAll(failing);
    }
**********/
    
    /**
     * UNUSED - See FloodfillPeerSelector override
     * Generic KBucket filtering to find the hashes close to a key, regardless of other considerations.
     * This goes through the kbuckets, starting with the key's location, moving towards us, and then away from the
     * key's location's bucket, selecting peers until we have numClosest.  
     * List MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the original key (NOT the routing key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected, ordered by bucket (but intra bucket order is not defined)
     */
    List<Hash> selectNearest(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) {
        // sure, this may not be exactly correct per kademlia (peers on the border of a kbucket in strict kademlia
        // would behave differently) but I can see no reason to keep around an /additional/ more complicated algorithm.
        // later if/when selectNearestExplicit gets costly, we may revisit this (since kbuckets let us cache the distance()
        // into a simple bucket selection algo + random select rather than an n*log(n) op)
        return selectNearestExplicit(key, maxNumRouters, peersToIgnore, kbuckets);
    }
}
