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
import java.util.*;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

class FloodfillPeerSelector extends PeerSelector {
    public FloodfillPeerSelector(RouterContext ctx) { super(ctx); }
    
    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     *
     * @return List of Hash for the peers selected
     */
    public List selectNearestExplicitThin(Hash key, int maxNumRouters, Set peersToIgnore, KBucketSet kbuckets) { 
        if (peersToIgnore == null)
            peersToIgnore = new HashSet(1);
        peersToIgnore.add(_context.router().getRouterInfo().getIdentity().getHash());
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(key, peersToIgnore, maxNumRouters);
        if (kbuckets == null) return new ArrayList();
        kbuckets.getAll(matches);
        List rv = matches.get(maxNumRouters);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching for " + maxNumRouters + " peers close to " + key + ": " 
                       + rv + " (not including " + peersToIgnore + ") [allHashes.size = " 
                       + matches.size() + "]", new Exception("Search by"));
        return rv;
    }
    
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
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            //if (info == null)
            //    return;
            
            if (info != null && FloodfillNetworkDatabaseFacade.isFloodfill(info)) {
                _floodfillMatches.add(entry);
            } else {
                if ( (!SearchJob.onlyQueryFloodfillPeers(_context)) && (_wanted > _matches) && (_key != null) ) {
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
            Collections.shuffle(_floodfillMatches, _context.random());
            List rv = new ArrayList(howMany);
            for (int i = 0; i < howMany && i < _floodfillMatches.size(); i++) {
                rv.add(_floodfillMatches.get(i));
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
