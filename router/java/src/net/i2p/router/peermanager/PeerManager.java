package net.i2p.router.peermanager;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Manage the current state of the statistics
 *
 * Also maintain Sets for each of the capabilities in TRACKED_CAPS.
 *
 */
class PeerManager {
    private final Log _log;
    private final RouterContext _context;
    private final ProfileOrganizer _organizer;
    private final ProfilePersistenceHelper _persistenceHelper;
    private final Map<Character, Set<Hash>> _peersByCapability;
    /** value strings are lower case */
    private final Map<Hash, String> _capabilitiesByPeer;
    private static final long REORGANIZE_TIME = 45*1000;
    private static final long REORGANIZE_TIME_MEDIUM = 123*1000;
    /**
     *  We don't want this much longer than the average connect time,
     *  as the CapacityCalculator now includes connection as a factor.
     *  This must also be less than 10 minutes, which is the shortest
     *  Rate contained in the profile, as the Rates must be coalesced.
     */
    private static final long REORGANIZE_TIME_LONG = 351*1000;
    
    public static final String TRACKED_CAPS = "" +
        FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL +
        RouterInfo.CAPABILITY_HIDDEN +
        Router.CAPABILITY_BW12 +
        Router.CAPABILITY_BW32 +
        Router.CAPABILITY_BW64 +
        Router.CAPABILITY_BW128 +
        Router.CAPABILITY_BW256 +
        Router.CAPABILITY_REACHABLE +
        Router.CAPABILITY_UNREACHABLE;

    /**
     *  Profiles are now loaded in a separate thread,
     *  so this should return quickly.
     */
    public PeerManager(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(PeerManager.class);
        _persistenceHelper = new ProfilePersistenceHelper(context);
        _organizer = context.profileOrganizer();
        _organizer.setUs(context.routerHash());
        _capabilitiesByPeer = new ConcurrentHashMap(256);
        _peersByCapability = new HashMap(TRACKED_CAPS.length());
        for (int i = 0; i < TRACKED_CAPS.length(); i++)
            _peersByCapability.put(Character.valueOf(Character.toLowerCase(TRACKED_CAPS.charAt(i))), new ConcurrentHashSet());
        loadProfilesInBackground();
        ////_context.jobQueue().addJob(new EvaluateProfilesJob(_context));
        //SimpleScheduler.getInstance().addPeriodicEvent(new Reorg(), 0, REORGANIZE_TIME);
        //new Reorg();
        //_context.jobQueue().addJob(new PersistProfilesJob(_context, this));
    }
    
    private class Reorg extends SimpleTimer2.TimedEvent {
        public Reorg() {
            super(_context.simpleTimer2(), REORGANIZE_TIME);
        }
        public void timeReached() {
            try {
                _organizer.reorganize(true);
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error evaluating profiles", t);
            }
            long uptime = _context.router().getUptime();
            long delay;
            if (uptime > 2*60*60*1000)
                delay = REORGANIZE_TIME_LONG;
            else if (uptime > 10*60*1000)
                delay = REORGANIZE_TIME_MEDIUM;
            else
                delay = REORGANIZE_TIME;
            schedule(delay);
        }
    }
    
    void storeProfiles() {
        Set<Hash> peers = selectPeers();
        for (Hash peer : peers) {
            storeProfile(peer);
        }
    }

    /** @since 0.8.8 */
    void clearProfiles() {
        _organizer.clearProfiles();
        _capabilitiesByPeer.clear();
        for (Set p : _peersByCapability.values())
            p.clear();
    }

    Set<Hash> selectPeers() {
        return _organizer.selectAllPeers();
    }

    void storeProfile(Hash peer) {
        if (peer == null) return;
        PeerProfile prof = _organizer.getProfile(peer);
        if (prof == null) return;
        if (true)
            _persistenceHelper.writeProfile(prof);
    }

    /**
     *  Load the profiles in a separate thread, so we don't spend
     *  forever in the constructor (slowing down the Router constructor
     *  via RouterContext.initAll()).
     *  This also instantiates Reorg, so only call this once
     *
     *  @since 0.8.8
     */
    private void loadProfilesInBackground() {
        (new Thread(new ProfileLoader())).start();
    }

    /**
     *  Load the profiles and instantiate Reorg
     *
     *  @since 0.8.8
     */
    private class ProfileLoader implements Runnable {
        public void run() {
            loadProfiles();
            new Reorg();
        }
    }

    /**
     *  This may take a long time - 30 seconds or more
     */
    void loadProfiles() {
        Set<PeerProfile> profiles = _persistenceHelper.readProfiles();
        for (PeerProfile prof : profiles) {
                _organizer.addProfile(prof);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Profile for " + prof.getPeer().toBase64() + " loaded");
        }
    }
    
    /**
     * Find some peers that meet the criteria and we have the netDb info for locally
     *
     * Only used by PeerTestJob (PURPOSE_TEST)
     */
    List<Hash> selectPeers(PeerSelectionCriteria criteria) {
        Set<Hash> peers = new HashSet(criteria.getMinimumRequired());
        // not a singleton, SANFP adds to it
        Set<Hash> exclude = new HashSet(1);
        exclude.add(_context.routerHash());
        switch (criteria.getPurpose()) {
            case PeerSelectionCriteria.PURPOSE_TEST:
                // for now, the peers we test will be the reliable ones
                //_organizer.selectWellIntegratedPeers(criteria.getMinimumRequired(), exclude, curVals);

                // The PeerTestJob does only run every 5 minutes, but
                // this was helping drive us to connection limits, let's leave the exploration
                // to the ExploratoryPeerSelector, which will restrict to connected peers
                // when we get close to the limit. So let's stick with connected peers here.
                // Todo: what's the point of the PeerTestJob anyway?
                //_organizer.selectNotFailingPeers(criteria.getMinimumRequired(), exclude, peers);
                _organizer.selectActiveNotFailingPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_TUNNEL:
                // pull all of the fast ones, regardless of how many we 
                // want - we'll whittle them down later (40 lines from now)
                // int num = _organizer.countFastPeers();
                // if (num <= 0) 
                //    num = criteria.getMaximumRequired();
                // _organizer.selectFastPeers(num, exclude, curVals);
                _organizer.selectFastPeers(criteria.getMaximumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_SOURCE_ROUTE:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            case PeerSelectionCriteria.PURPOSE_GARLIC:
                _organizer.selectHighCapacityPeers(criteria.getMinimumRequired(), exclude, peers);
                break;
            default:
                break;
        }
        if (peers.isEmpty()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We ran out of peers when looking for reachable ones after finding " 
                          + "0 with "
                          + _organizer.countHighCapacityPeers() + "/" 
                          + _organizer.countFastPeers() + " high capacity/fast peers");
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Peers selected: " + peers);
        return new ArrayList(peers);
    }
    
    /**
     *  @param caps non-null, case is ignored
     */
    public void setCapabilities(Hash peer, String caps) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Setting capabilities for " + peer.toBase64() + " to " + caps);
        caps = caps.toLowerCase(Locale.US);

        String oldCaps = _capabilitiesByPeer.put(peer, caps);
        if (caps.equals(oldCaps))
            return;
            
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    if (caps.indexOf(c) < 0) {
                        Set<Hash> peers = locked_getPeers(c);
                        if (peers != null)
                            peers.remove(peer);
                    }
                }
            }

                for (int i = 0; i < caps.length(); i++) {
                    char c = caps.charAt(i);
                    if ( (oldCaps != null) && (oldCaps.indexOf(c) >= 0) )
                        continue;
                    Set<Hash> peers = locked_getPeers(c);
                    if (peers != null)
                        peers.add(peer);
                }
    }
    
    /** locking no longer req'd */
    private Set<Hash> locked_getPeers(char c) {
        c = Character.toLowerCase(c);
        return _peersByCapability.get(Character.valueOf(c));
    }
    
    public void removeCapabilities(Hash peer) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing capabilities from " + peer.toBase64());

            String oldCaps = _capabilitiesByPeer.remove(peer);
            if (oldCaps != null) {
                for (int i = 0; i < oldCaps.length(); i++) {
                    char c = oldCaps.charAt(i);
                    Set<Hash> peers = locked_getPeers(c);
                    if (peers != null)
                        peers.remove(peer);
                }
            }
    }

/*******
    public Hash selectRandomByCapability(char capability) { 
        int index = _context.random().nextInt(Integer.MAX_VALUE);
        synchronized (_capabilitiesByPeer) {
            List peers = locked_getPeers(capability);
            if ( (peers != null) && (!peers.isEmpty()) ) {
                index = index % peers.size();
                return (Hash)peers.get(index);
            }
        }
        return null;
    }
********/

    /**
     *  @param capability case-insensitive
     *  @return non-null unmodifiable set
     */
    public Set<Hash> getPeersByCapability(char capability) { 
            Set<Hash> peers = locked_getPeers(capability);
            if (peers != null)
                return Collections.unmodifiableSet(peers);
            return Collections.EMPTY_SET;
    }
}
