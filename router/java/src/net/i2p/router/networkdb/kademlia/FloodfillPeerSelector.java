package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *  This is where we implement semi-Kademlia with the floodfills, by
 *  selecting floodfills closest to a given key for
 *  searches and stores.
 *
 *  Warning - most methods taking a key as an argument require the
 *            routing key, not the original key.
 *
 */
class FloodfillPeerSelector extends PeerSelector {
    public FloodfillPeerSelector(RouterContext ctx) { super(ctx); }
    
    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Puts the floodfill peers that are directly connected first in the list.
     * List will not include our own hash.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    @Override
    List<Hash> selectMostReliablePeers(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, true);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Does not prefer the floodfill peers that are directly connected.
     * List will not include our own hash.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    @Override
    List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) { 
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, false);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * List will not include our own hash.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets, boolean preferConnected) { 
        if (peersToIgnore == null)
            peersToIgnore = Collections.singleton(_context.routerHash());
        else
            peersToIgnore.add(_context.routerHash());
        // TODO this is very slow
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(key, peersToIgnore, maxNumRouters);
        if (kbuckets == null) return new ArrayList();
        kbuckets.getAll(matches);
        List<Hash> rv = matches.get(maxNumRouters, preferConnected);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching for " + maxNumRouters + " peers close to " + key + ": " 
                       + rv + " (not including " + peersToIgnore + ") [allHashes.size = " 
                       + matches.size() + "]", new Exception("Search by"));
        return rv;
    }
    
    /**
     *  @return all floodfills not shitlisted forever.
     *  List will not include our own hash.
     *  List is not sorted and not shuffled.
     */
    List<Hash> selectFloodfillParticipants(KBucketSet kbuckets) {
        Set<Hash> ignore = Collections.singleton(_context.routerHash());
        return selectFloodfillParticipants(ignore, kbuckets);
    }

    /**
     *  @param toIgnore can be null
     *  @return all floodfills not shitlisted forever.
     *  List MAY INCLUDE our own hash.
     *  List is not sorted and not shuffled.
     */
    private List<Hash> selectFloodfillParticipants(Set<Hash> toIgnore, KBucketSet kbuckets) {
      /*****
        if (kbuckets == null) return Collections.EMPTY_LIST;
        // TODO this is very slow - use profile getPeersByCapability('f') instead
        _context.statManager().addRateData("netDb.newFSC", 0, 0);
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(null, toIgnore, 0);
        kbuckets.getAll(matches);
        return matches.getFloodfillParticipants();
      *****/
        Set<Hash> set = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        List<Hash> rv = new ArrayList(set.size());
        for (Hash h : set) {
            if ((toIgnore != null && toIgnore.contains(h)) ||
                _context.shitlist().isShitlistedForever(h))
               continue;
            rv.add(h);
        }
        return rv;
    }
    
    /**
     *  Sort the floodfills. The challenge here is to keep the good ones
     *  at the front and the bad ones at the back. If they are all good or bad,
     *  searches and stores won't work well.
     *  List will not include our own hash.
     *
     *  @return floodfills closest to the key that are not shitlisted forever
     *  @param key the ROUTING key (NOT the original key)
     *  @param maxNumRouters max to return
     *  Sorted by closest to the key if > maxNumRouters, otherwise not
     *  The list is in 3 groups - sorted by routing key within each group.
     *  Group 1: No store or lookup failure in a long time, and
    *            lookup fail rate no more than 1.5 * average
     *  Group 2: No store or lookup failure in a little while or
     *           success newer than failure
     *  Group 3: All others
     */
    List<Hash> selectFloodfillParticipants(Hash key, int maxNumRouters, KBucketSet kbuckets) {
        Set<Hash> ignore = Collections.singleton(_context.routerHash());
        return selectFloodfillParticipants(key, maxNumRouters, ignore, kbuckets);
    }

    /** .5 * PublishLocalRouterInfoJob.PUBLISH_DELAY */
    private static final int NO_FAIL_STORE_OK = 10*60*1000;
    private static final int NO_FAIL_STORE_GOOD = NO_FAIL_STORE_OK * 2;
    /** this must be longer than the max streaming timeout (60s) */
    private static final int NO_FAIL_LOOKUP_OK = 75*1000;
    private static final int NO_FAIL_LOOKUP_GOOD = NO_FAIL_LOOKUP_OK * 3;
    private static final int MAX_GOOD_RESP_TIME = 5*1000;

    /**
     *  See above for description
     *  List will not include our own hash
     *  @param key the ROUTING key (NOT the original key)
     *  @param toIgnore can be null
     */
    List<Hash> selectFloodfillParticipants(Hash key, int howMany, Set<Hash> toIgnore, KBucketSet kbuckets) {
        if (toIgnore == null) {
            toIgnore = Collections.singleton(_context.routerHash());
        } else if (!toIgnore.contains(_context.routerHash())) {
            // copy the Set so we don't confuse StoreJob
            toIgnore = new HashSet(toIgnore);
            toIgnore.add(_context.routerHash());
        }
        return selectFloodfillParticipantsIncludingUs(key, howMany, toIgnore, kbuckets);
    }

    /**
     *  See above for description
     *  List MAY CONTAIN our own hash unless included in toIgnore
     *  @param key the ROUTING key (NOT the original key)
     *  @param toIgnore can be null
     */
    private List<Hash> selectFloodfillParticipantsIncludingUs(Hash key, int howMany, Set<Hash> toIgnore, KBucketSet kbuckets) {
        List<Hash> ffs = selectFloodfillParticipants(toIgnore, kbuckets);
        TreeSet<Hash> sorted = new TreeSet(new XORComparator(key));
        sorted.addAll(ffs);

        List<Hash> rv = new ArrayList(howMany);
        List<Hash> okff = new ArrayList(ffs.size());
        List<Hash> badff = new ArrayList(ffs.size());
        int found = 0;
        long now = _context.clock().now();

        double maxFailRate = 100;
        if (_context.router().getUptime() > 60*60*1000) {
            RateStat rs = _context.statManager().getRate("peer.failedLookupRate");
            if (rs != null) {
                Rate r = rs.getRate(60*60*1000);
                if (r != null) {
                    double currentFailRate = r.getAverageValue();
                    maxFailRate = Math.max(0.20d, 1.5d * currentFailRate);
                }
            }
        }

        // 8 == FNDF.MAX_TO_FLOOD + 1
        int limit = Math.max(8, howMany);
        limit = Math.min(limit, ffs.size());
        // split sorted list into 3 sorted lists
        for (int i = 0; found < howMany && i < limit; i++) {
            Hash entry = sorted.first();
            sorted.remove(entry);
            if (entry == null)
                break;  // shouldn't happen
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            if (info != null && now - info.getPublished() > 3*60*60*1000) {
                badff.add(entry);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Old: " + entry);
            } else {
                PeerProfile prof = _context.profileOrganizer().getProfile(entry);
                double maxGoodRespTime = MAX_GOOD_RESP_TIME;
                RateStat ttst = _context.statManager().getRate("tunnel.testSuccessTime");
                if (ttst != null) {
                    Rate tunnelTestTime = ttst.getRate(10*60*1000);
                    if (tunnelTestTime != null && tunnelTestTime.getAverageValue() > 500)
                        maxGoodRespTime = 2 * tunnelTestTime.getAverageValue();
                }
                if (prof != null && prof.getDBHistory() != null
                    && prof.getDbResponseTime().getRate(10*60*1000).getAverageValue() < maxGoodRespTime
                    && prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_GOOD
                    && prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_GOOD
                    && prof.getDBHistory().getFailedLookupRate().getRate(60*60*1000).getAverageValue() < maxFailRate) {
                    // good
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Good: " + entry);
                    rv.add(entry);
                    found++;
                } else if (prof != null && prof.getDBHistory() != null
                           && (prof.getDBHistory().getLastStoreFailed() <= prof.getDBHistory().getLastStoreSuccessful()
                               || prof.getDBHistory().getLastLookupFailed() <= prof.getDBHistory().getLastLookupSuccessful()
                               || (prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_OK
                                   && prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_OK))) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("OK: " + entry);
                    okff.add(entry);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Bad: " + entry);
                    badff.add(entry);
                }
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Good: " + rv + " OK: " + okff + " Bad: " + badff);

        // Put the ok floodfills after the good floodfills
        for (int i = 0; found < howMany && i < okff.size(); i++) {
            rv.add(okff.get(i));
            found++;
        }
        // Put the "bad" floodfills after the ok floodfills
        for (int i = 0; found < howMany && i < badff.size(); i++) {
            rv.add(badff.get(i));
            found++;
        }

        return rv;
    }
    
    private class FloodfillSelectionCollector implements SelectionCollector {
        private final TreeSet<Hash> _sorted;
        private final List<Hash>  _floodfillMatches;
        private final Hash _key;
        private final Set<Hash> _toIgnore;
        private int _matches;
        private final int _wanted;

        /**
         *  Warning - may return our router hash - add to toIgnore if necessary
         *  @param key the ROUTING key (NOT the original key)
         *  @param toIgnore can be null
         */
        public FloodfillSelectionCollector(Hash key, Set<Hash> toIgnore, int wanted) {
            _key = key;
            _sorted = new TreeSet(new XORComparator(key));
            _floodfillMatches = new ArrayList(8);
            _toIgnore = toIgnore;
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
            //if (entry.equals(_context.routerHash()))
            //    return;
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
                    _sorted.add(entry);
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
         *  Will return non-floodfills only if there aren't enough floodfills.
         *
         *  The list is in 3 groups - unsorted (shuffled) within each group.
         *  Group 1: If preferConnected = true, the peers we are directly
         *           connected to, that meet the group 2 criteria
         *  Group 2: Netdb published less than 3h ago, no bad send in last 30m.
         *  Group 3: All others
         *  Group 4: Non-floodfills, sorted by closest-to-the-key
         */
        public List<Hash> get(int howMany, boolean preferConnected) {
            List<Hash> rv = new ArrayList(howMany);
            List<Hash> badff = new ArrayList(howMany);
            List<Hash> unconnectedff = new ArrayList(howMany);
            int found = 0;
            long now = _context.clock().now();
            // Only add in "good" floodfills here...
            // Let's say published in last 3h and no failed sends in last 30m
            // (Forever shitlisted ones are excluded in add() above)
            for (Iterator<Hash> iter = new RandomIterator(_floodfillMatches); (found < howMany) && iter.hasNext(); ) {
                Hash entry = iter.next();
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
            // are we corrupting _sorted here?
            for (int i = rv.size(); i < howMany; i++) {
                if (_sorted.isEmpty())
                    break;
                Hash entry = _sorted.first();
                rv.add(entry);
                _sorted.remove(entry);
            }
            return rv;
        }
        public int size() { return _matches; }
    }
    
    /**
     * Floodfill peers only. Used only by HandleDatabaseLookupMessageJob to populate the DSRM.
     * UNLESS peersToIgnore contains Hash.FAKE_HASH (all zeros), in which case this is an exploratory
     * lookup, and the response should not include floodfills.
     * List MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the original key (NOT the routing key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected, ordered
     */
    @Override
    List<Hash> selectNearest(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet kbuckets) {
        Hash rkey = _context.routingKeyGenerator().getRoutingKey(key);
        if (peersToIgnore != null && peersToIgnore.contains(Hash.FAKE_HASH)) {
            // return non-ff
            peersToIgnore.addAll(selectFloodfillParticipants(peersToIgnore, kbuckets));
            // TODO this is very slow
            FloodfillSelectionCollector matches = new FloodfillSelectionCollector(rkey, peersToIgnore, maxNumRouters);
            kbuckets.getAll(matches);
            return matches.get(maxNumRouters);
        } else {
            // return ff
            return selectFloodfillParticipantsIncludingUs(rkey, maxNumRouters, peersToIgnore, kbuckets);
        }
    }
}
