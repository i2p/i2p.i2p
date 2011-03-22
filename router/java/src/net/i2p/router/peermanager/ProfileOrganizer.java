package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Keep the peer profiles organized according to the tiered model.  This does not
 * actively update anything - the reorganize() method should be called periodically
 * to recalculate thresholds and move profiles into the appropriate tiers, and addProfile()
 * should be used to add new profiles (placing them into the appropriate groupings).
 */
public class ProfileOrganizer {
    private final Log _log;
    private final RouterContext _context;
    /** H(routerIdentity) to PeerProfile for all peers that are fast and high capacity*/
    private final Map<Hash, PeerProfile> _fastPeers;
    /** H(routerIdentity) to PeerProfile for all peers that have high capacities */
    private final Map<Hash, PeerProfile> _highCapacityPeers;
    /** H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private final Map<Hash, PeerProfile> _notFailingPeers;
    /** H(routerIdnetity), containing elements in _notFailingPeers */
    private final List<Hash> _notFailingPeersList;
    /** H(routerIdentity) to PeerProfile for all peers that ARE failing horribly (but that we haven't dropped reference to yet) */
    private final Map<Hash, PeerProfile> _failingPeers;
    /** who are we? */
    private Hash _us;
    private final ProfilePersistenceHelper _persistenceHelper;
    
    /** PeerProfile objects for all peers profiled, orderd by the ones with the highest capacity first */
    private Set<PeerProfile> _strictCapacityOrder;
    
    /** threshold speed value, seperating fast from slow */
    private double _thresholdSpeedValue;
    /** threshold reliability value, seperating reliable from unreliable */
    private double _thresholdCapacityValue;
    /** integration value, seperating well integrated from not well integrated */
    private double _thresholdIntegrationValue;
    
    private final InverseCapacityComparator _comp;

    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  See
     * {@link ProfileOrganizer#getMinimumFastPeers}
     *
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 8;
    /** this is misnamed, it is really the max minimum number. */
    private static final int DEFAULT_MAXIMUM_FAST_PEERS = 16;
    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should 
     * select when using the mean - if less than this many are available, select the 
     * capacity by the median.  
     *
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 10;
    
    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(true);
    
    /** incredibly weak PRNG, just used for shuffling peers.  no need to waste the real PRNG on this */
    private Random _random = new Random();
    
    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap(16);
        _highCapacityPeers = new HashMap(32);
        _wellIntegratedPeers = new HashMap(16);
        _notFailingPeers = new HashMap(256);
        _notFailingPeersList = new ArrayList(256);
        _failingPeers = new HashMap(16);
        _strictCapacityOrder = new TreeSet(_comp);
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        
        _context.statManager().createRateStat("peer.profileSortTime", "How long the reorg takes sorting peers", "Peers", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("peer.profileCoalesceTime", "How long the reorg takes coalescing peer stats", "Peers", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("peer.profileThresholdTime", "How long the reorg takes determining the tier thresholds", "Peers", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("peer.profilePlaceTime", "How long the reorg takes placing peers in the tiers", "Peers", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("peer.profileReorgTime", "How long the reorg takes overall", "Peers", new long[] { 10*60*1000 });
        // used in DBHistory
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "Net DB Lookup fail rate", "Peers", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    private void getReadLock() {
        _reorganizeLock.readLock().lock();
    }

    private void releaseReadLock() {
        _reorganizeLock.readLock().unlock();
    }

    /** @return true if the lock was acquired */
    private boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldLog(Log.WARN))
                _log.warn("no lock, size is: " + _reorganizeLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) {}
        return false;
    }

    private void releaseWriteLock() {
        _reorganizeLock.writeLock().unlock();
    }

    public void setUs(Hash us) { _us = us; }
    public Hash getUs() { return _us; }
    
    public double getSpeedThreshold() { return _thresholdSpeedValue; }
    public double getCapacityThreshold() { return _thresholdCapacityValue; }
    public double getIntegrationThreshold() { return _thresholdIntegrationValue; }
    
    /**
     * Retrieve the profile for the given peer, if one exists (else null)
     *
     */
    public PeerProfile getProfile(Hash peer) {
        getReadLock();
        try {
            return locked_getProfile(peer);
        } finally { releaseReadLock(); }
    }
    
    /**
     * Add the new profile, returning the old value (or null if no profile existed)
     *
     */
    public PeerProfile addProfile(PeerProfile profile) {
        if ( (profile == null) || (profile.getPeer() == null) ) return null;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("New profile created for " + profile.getPeer().toBase64());
        
        PeerProfile old = getProfile(profile.getPeer());
        profile.coalesceStats();
        if (!getWriteLock())
            return old;
        try {
            locked_placeProfile(profile);
            _strictCapacityOrder.add(profile);
        } finally { releaseWriteLock(); }
        return old;
    }
    
    private int count(Map m) {
        getReadLock();
        try {
            return m.size();
        } finally { releaseReadLock(); }
    }

    public int countFastPeers() { return count(_fastPeers); }
    public int countHighCapacityPeers() { return count(_highCapacityPeers); }
    public int countWellIntegratedPeers() { return count(_wellIntegratedPeers); }
    public int countNotFailingPeers() { return count(_notFailingPeers); }
    public int countFailingPeers() { return count(_failingPeers); }
    
    public int countActivePeers() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 6*60*60*1000;
       
        getReadLock();
        try {
            for (Iterator<PeerProfile> iter = _failingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            for (Iterator<PeerProfile> iter = _notFailingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
        } finally { releaseReadLock(); }
        return activePeers;
    }
    
    private boolean isX(Map<Hash, PeerProfile> m, Hash peer) {
        getReadLock();
        try {
            return m.containsKey(peer);
        } finally { releaseReadLock(); }
    }

    public boolean isFast(Hash peer) { return isX(_fastPeers, peer); }
    public boolean isHighCapacity(Hash peer) { return isX(_highCapacityPeers, peer); }
    public boolean isWellIntegrated(Hash peer) { return isX(_wellIntegratedPeers, peer); }
    public boolean isFailing(Hash peer) { return isX(_failingPeers, peer); }
        
    /** 
     * if a peer sends us more than 5 replies in a searchReply that we cannot
     * fetch, stop listening to them.
     *
     */
    private final static int MAX_BAD_REPLIES_PER_HOUR = 5;
    
    /**
     * Does the given peer send us bad replies - either invalid store messages 
     * (expired, corrupt, etc) or unreachable replies (pointing towards routers
     * that don't exist).
     *
     */
    public boolean peerSendsBadReplies(Hash peer) {
        PeerProfile profile = getProfile(peer);
        if (profile != null && profile.getIsExpandedDB()) {
            RateStat invalidReplyRateStat = profile.getDBHistory().getInvalidReplyRate();
            Rate invalidReplyRate = invalidReplyRateStat.getRate(30*60*1000l);
            if ( (invalidReplyRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ||
                 (invalidReplyRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ) {
                return true;
            }
        }
        return false;
    }
    
    /**
     *  @return true if successful, false if not found
     */
    public boolean exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        boolean rv = prof != null;
        if (rv)
            _persistenceHelper.writeProfile(prof, out);
        return rv;
    }
    
    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto high capacity peers, and if that
     * doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     *
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectFastPeers(howMany, exclude, matches, 0);
    }
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            locked_selectPeers(_fastPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectFastPeers("+howMany+"), not enough fast (" + matches.size() + ") going on to highCap");
            selectHighCapacityPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectFastPeers("+howMany+"), found enough fast (" + matches.size() + ")");
        }
        return;
    }
    
    /**
     * Return a set of Hashes for peers that have a high capacity
     *
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0);
    }
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            // we only use selectHighCapacityPeers when we are selecting for PURPOSE_TEST
            // or we are falling back due to _fastPeers being too small, so we can always 
            // exclude the fast peers
            /*
            if (exclude == null)
                exclude = new HashSet(_fastPeers.keySet());
            else
                exclude.addAll(_fastPeers.keySet());
             */
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectHighCap("+howMany+"), not enough highcap (" + matches.size() + ") going on to ANFP2");
            selectActiveNotFailingPeers2(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectHighCap("+howMany+"), found enough highCap (" + matches.size() + ")");
        }
        return;
    }
    /**
     * Return a set of Hashes for peers that are well integrated into the network.
     *
     */
    public void selectWellIntegratedPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectWellIntegratedPeers(howMany, exclude, matches, 0);
    }
    public void selectWellIntegratedPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            locked_selectPeers(_wellIntegratedPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectWellIntegrated("+howMany+"), not enough integrated (" + matches.size() + ") going on to notFailing");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {            
            if (_log.shouldLog(Log.INFO))
                _log.info("selectWellIntegrated("+howMany+"), found enough well integrated (" + matches.size() + ")");
        }
        
        return;
    }
    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0);
    }
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask);
    }
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }
    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     * @param howMany how many peers to find
     * @param exclude what peers to skip (may be null)
     * @param matches set to store the matches in
     * @param onlyNotFailing if true, don't include any high capacity peers
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany)
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);
        return;
    }

    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     * We use commSystem().isEstablished(), not profile.getIsActive(), as the
     * NTCP idle time is now shorter than the 5 minute getIsActive() threshold,
     * and we're using this to try and limit connections.
     *
     * Caution, this does NOT cascade further to non-connected peers, so it should only
     * be used when there is a good number of connected peers.
     *
     * @param exclude non-null
     * No mask parameter, to be fixed
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        if (matches.size() < howMany) {
            getReadLock();
            try {
                for (Iterator<Hash> iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = iter.next();
                    if (!_context.commSystem().isEstablished(peer))
                        exclude.add(peer);
                }
                locked_selectPeers(_notFailingPeers, howMany, exclude, matches, 0);
            } finally { releaseReadLock(); }
        }
    }

    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     * We use commSystem().isEstablished(), not profile.getIsActive(), as the
     * NTCP idle time is now shorter than the 5 minute getIsActive() threshold,
     * and we're using this to try and limit connections.
     *
     * This DOES cascade further to non-connected peers.
     */
    private void selectActiveNotFailingPeers2(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        if (matches.size() < howMany) {
            Map<Hash, PeerProfile> activePeers = new HashMap();
            getReadLock();
            try {
                for (Iterator<Map.Entry<Hash, PeerProfile>> iter = _notFailingPeers.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Hash, PeerProfile> e = iter.next();
                    if (_context.commSystem().isEstablished(e.getKey()))
                        activePeers.put(e.getKey(), e.getValue());
                }
                locked_selectPeers(activePeers, howMany, exclude, matches, mask);
            } finally { releaseReadLock(); }
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectANFP2("+howMany+"), not enough ANFP (" + matches.size() + ") going on to notFailing");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectANFP2("+howMany+"), found enough ANFP (" + matches.size() + ")");
        }
    }

    /**
     * Return a set of Hashes for peers that are not failing.
     *
     */
    public void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }
    /**
     * @param mask ignored, should call locked_selectPeers, to be fixed
     *
     */
    private void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany) {
            int orig = matches.size();
            int needed = howMany - orig;
            int start = 0;
            List selected = new ArrayList(needed);
            getReadLock();
            try {
                // we randomize the whole list when rebuilding it, but randomizing 
                // the entire list on each peer selection is a bit crazy
                start = _context.random().nextInt(_notFailingPeersList.size());
                for (int i = 0; i < _notFailingPeersList.size() && selected.size() < needed; i++) {
                    int curIndex = (i+start) % _notFailingPeersList.size();
                    Hash cur = (Hash)_notFailingPeersList.get(curIndex);
                    if (matches.contains(cur) ||
                        (exclude != null && exclude.contains(cur))) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("matched? " + matches.contains(cur) + " exclude: " + exclude + " cur=" + cur.toBase64());
                        continue;
                    } else if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) {
                        // we dont want the good peers, just random ones
                        continue;
                    } else {
                        if (isSelectable(cur))
                            selected.add(cur);
                        else if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Not selectable: " + cur.toBase64());
                    }
                }
            } finally { releaseReadLock(); }
            if (_log.shouldLog(Log.INFO))
                _log.info("Selecting all not failing (strict? " + onlyNotFailing + " start=" + start 
                          + ") found " + selected.size() + " new peers: " + selected + " all=" + _notFailingPeersList.size() + " strict=" + _strictCapacityOrder.size());
            matches.addAll(selected);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectAllNotFailing("+howMany+"), not enough (" + matches.size() + ") going on to failing");
            selectFailingPeers(howMany, exclude, matches);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("selectAllNotFailing("+howMany+"), enough (" + matches.size() + ")");
        }
        return;
    }
    /**
     * I'm not quite sure why you'd want this... (other than for failover from the better results)
     *
     */
    public void selectFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        getReadLock();
        try {
            locked_selectPeers(_failingPeers, howMany, exclude, matches);
        } finally { releaseReadLock(); }
        return;        
    }                  

    /**                
     * Get the peers the transport layer thinks are unreachable, and
     * add in the peers with the SSU peer testing bug,
     * and peers requiring introducers.
     *                 
     */                
    public List<Hash> selectPeersLocallyUnreachable() { 
        List<Hash> n;
        int count;
        getReadLock();
        try {
            count = _notFailingPeers.size();
            n = new ArrayList(_notFailingPeers.keySet());
        } finally { releaseReadLock(); }
        List<Hash> l = new ArrayList(count / 4);
        for (Iterator<Hash> iter = n.iterator(); iter.hasNext(); ) {
            Hash peer = iter.next();
            if (_context.commSystem().wasUnreachable(peer))
                l.add(peer);
            else {
                // Blacklist <= 0.6.1.32 SSU-only peers, they don't know if they are unreachable,
                // and we may not know either if they contacted us first, so assume they are.
                // Also blacklist all peers requiring SSU introducers, because either
                //  a) it's slow; or
                //  b) it doesn't work very often; or
                //  c) in the event they are advertising NTCP, it probably won't work because
                //     they probably don't have a TCP hole punched in their firewall either.
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null) {
                    String v = info.getOption("router.version");
                    // this only works if there is no 0.6.1.34!
                    if (v != null && (!v.equals("0.6.1.33")) &&
                        v.startsWith("0.6.1.") && info.getTargetAddress("NTCP") == null)
                        l.add(peer);
                    else {
                        RouterAddress ra = info.getTargetAddress("SSU");
                        // peers with no SSU address at all are fine.
                        // as long as they have NTCP
                        if (ra == null) {
                            if (info.getTargetAddress("NTCP") == null)
                                l.add(peer);
                            continue;
                        }
                        // This is the quick way of doing UDPAddress.getIntroducerCount() > 0
                        Properties props = ra.getOptions();
                        if (props != null && props.getProperty("ihost0") != null)
                            l.add(peer);
                    }
                }
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Unreachable: " + l);
        return l;
    }

    /**
     * Get the peers that have recently rejected us for bandwidth
     * recent == last 20s
     *
     */
    public List<Hash> selectPeersRecentlyRejecting() { 
        getReadLock();
        try {
            long cutoff = _context.clock().now() - (20*1000);
            int count = _notFailingPeers.size();
            List<Hash> l = new ArrayList(count / 128);
            for (Iterator<PeerProfile> iter = _notFailingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile prof = iter.next();
                if (prof.getTunnelHistory().getLastRejectedBandwidth() > cutoff)
                    l.add(prof.getPeer());
            }
            return l;
        } finally { releaseReadLock(); }
    }

    /**
     * Find the hashes for all peers we are actively profiling
     *
     */
    public Set<Hash> selectAllPeers() {
        getReadLock();
        try {
            Set<Hash> allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_failingPeers.keySet());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        } finally { releaseReadLock(); }
    }
    
    private static final long MIN_EXPIRE_TIME = 3*60*60*1000;
    private static final long MAX_EXPIRE_TIME = 6*60*60*1000;
    private static final long ADJUST_EXPIRE_TIME = 60*1000;
    private static final int ENOUGH_PROFILES = 600;
    private long _currentExpireTime = MAX_EXPIRE_TIME;

    /**
     * Place peers into the correct tier, as well as expand/contract and even drop profiles
     * according to whatever limits are in place.  Peer profiles are not coalesced during
     * this method, but the averages are recalculated.
     *
     */
    public void reorganize() { reorganize(false); }
    public void reorganize(boolean shouldCoalesce) {
        long sortTime = 0;
        int coalesceTime = 0;
        long thresholdTime = 0;
        long placeTime = 0;
        int profileCount = 0;
        
        long uptime = _context.router().getUptime();
        long expireOlderThan = -1;
        if (uptime > 60*60*1000) {
            // dynamically adjust expire time to control memory usage
            if (countNotFailingPeers() > ENOUGH_PROFILES)
                _currentExpireTime = Math.max(_currentExpireTime - ADJUST_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _currentExpireTime = Math.min(_currentExpireTime + ADJUST_EXPIRE_TIME, MAX_EXPIRE_TIME);
            // drop profiles that we haven't spoken to in a while
            expireOlderThan = _context.clock().now() - _currentExpireTime;
        }
            
        if (!getWriteLock())
            return;
        long start = System.currentTimeMillis();
        try {
            Set<PeerProfile> allPeers = _strictCapacityOrder; //new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            //allPeers.addAll(_failingPeers.values());
            //allPeers.addAll(_notFailingPeers.values());
            //allPeers.addAll(_highCapacityPeers.values());
            //allPeers.addAll(_fastPeers.values());

            Set<PeerProfile> reordered = new TreeSet(_comp);
            long sortStart = System.currentTimeMillis();
            for (Iterator<PeerProfile> iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile prof = iter.next();
                if ( (expireOlderThan > 0) && (prof.getLastSendSuccessful() <= expireOlderThan) )
                    continue; // drop, but no need to delete, since we don't periodically reread
                
                if (shouldCoalesce) {
                    long coalesceStart = System.currentTimeMillis();
                    prof.coalesceStats();
                    coalesceTime += (int)(System.currentTimeMillis()-coalesceStart);
                }
                reordered.add(prof);
                profileCount++;
            }
            sortTime = System.currentTimeMillis() - sortStart;
            _strictCapacityOrder = reordered;

            long thresholdStart = System.currentTimeMillis();
            locked_calculateThresholds(allPeers);
            thresholdTime = System.currentTimeMillis()-thresholdStart;

            _failingPeers.clear();
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();

            long placeStart = System.currentTimeMillis();

            for (Iterator<PeerProfile> iter = allPeers.iterator(); iter.hasNext(); ) {
                PeerProfile profile = iter.next();
                locked_placeProfile(profile);
            }

            locked_unfailAsNecessary();
            locked_demoteHighCapAsNecessary();
            locked_promoteFastAsNecessary();
            locked_demoteFastAsNecessary();

            Collections.shuffle(_notFailingPeersList, _context.random());

            placeTime = System.currentTimeMillis()-placeStart;
        } finally { releaseWriteLock(); }


        if (_log.shouldLog(Log.INFO))
            _log.info("Profiles reorganized.  averages: [integration: " + _thresholdIntegrationValue 
                       + ", capacity: " + _thresholdCapacityValue + ", speed: " + _thresholdSpeedValue + "]");
            /*****
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(512);
                for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                    PeerProfile prof = (PeerProfile)iter.next();
                    buf.append('[').append(prof.toString()).append('=').append(prof.getCapacityValue()).append("] ");
                }
                _log.debug("Strictly organized (highest capacity first): " + buf.toString());
                _log.debug("fast: " + _fastPeers.values());
            }
            *****/
        
        long total = System.currentTimeMillis()-start;
        _context.statManager().addRateData("peer.profileSortTime", sortTime, profileCount);
        _context.statManager().addRateData("peer.profileCoalesceTime", coalesceTime, profileCount);
        _context.statManager().addRateData("peer.profileThresholdTime", thresholdTime, profileCount);
        _context.statManager().addRateData("peer.profilePlaceTime", placeTime, profileCount);
        _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);
    }
    
    /**
     * As with locked_unfailAsNecessary, I'm not sure how much I like this - if there
     * aren't enough fast peers, move some of the not-so-fast peers into the fast group.
     * This picks the not-so-fast peers based on capacity, not speed, and skips over any
     * failing peers.  Perhaps it should build a seperate strict ordering by speed?  Nah, not
     * worth the maintenance and memory overhead, at least not for now.
     *
     */
    private void locked_promoteFastAsNecessary() {
        int minFastPeers = getMinimumFastPeers();
        int numToPromote = minFastPeers - _fastPeers.size();
        if (numToPromote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly promote " + numToPromote + " peers to the fast group");
            for (Iterator<PeerProfile> iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile cur = iter.next();
                if ( (!_fastPeers.containsKey(cur.getPeer())) && (!cur.getIsFailing()) ) {
                    if (!isSelectable(cur.getPeer())) {
                        // skip peers we dont have in the netDb
                        // if (_log.shouldLog(Log.INFO))   
                        //     _log.info("skip unknown peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (!cur.getIsActive()) {
                        // skip inactive
                        // if (_log.shouldLog(Log.INFO))
                        //     _log.info("skip inactive peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Fast promoting: " + cur.getPeer().toBase64());
                    _fastPeers.put(cur.getPeer(), cur);
                    // no need to remove it from any of the other groups, since if it is 
                    // fast, it has a high capacity, and it is not failing
                    numToPromote--;
                    if (numToPromote <= 0)
                        break;
                }
            }
        }
        return;
    }
    
    /**
     * We want to put a cap on the fast pool, to use only a small set of routers
     * for client tunnels for anonymity reasons. Also, unless we use only a small
     * number, we don't really find out who the fast ones are.
     * @since 0.7.10
     */
    private void locked_demoteFastAsNecessary() {
        int maxFastPeers = getMaximumFastPeers();
        int numToDemote = _fastPeers.size() - maxFastPeers;
        if (numToDemote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly demote " + numToDemote + " peers from the fast group");
            // sort by speed, slowest-first
            Set<PeerProfile> sorted = new TreeSet(new SpeedComparator());
            sorted.addAll(_fastPeers.values());
            Iterator<PeerProfile> iter = sorted.iterator();
            for (int i = 0; i < numToDemote; i++) {
                _fastPeers.remove(iter.next().getPeer());
            }
        }
    }
    
    /**
     * We want to put a limit on the high cap pool, to use only a small set of routers
     * for expl. tunnels for anonymity reasons. Also, unless we use only a small
     * number, we don't really find out who the high capacity ones are.
     * @since 0.7.11
     */
    private void locked_demoteHighCapAsNecessary() {
        int maxHighCapPeers = getMaximumHighCapPeers();
        int numToDemote = _highCapacityPeers.size() - maxHighCapPeers;
        if (numToDemote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly demote " + numToDemote + " peers from the high cap group");
            // sorted by capacity, highest-first
            Iterator<PeerProfile> iter = _strictCapacityOrder.iterator();
            for (int i = 0; iter.hasNext() && i < maxHighCapPeers; ) {
                if (_highCapacityPeers.containsKey(iter.next().getPeer()))
                    i++;
            }
            for (int i = 0; iter.hasNext() && i < numToDemote; ) {
                Hash h = iter.next().getPeer();
                if (_highCapacityPeers.remove(h) != null) {
                    _fastPeers.remove(h);
                    i++;
                }
            }
        }
    }
    
    /** how many not failing/active peers must we have? */
    private final static int MIN_NOT_FAILING_ACTIVE = 3;
    /**
     * I'm not sure how much I dislike the following - if there aren't enough
     * active and not-failing peers, pick the most reliable active peers and
     * override their 'failing' flag, resorting them into the not-failing buckets
     *
     */
    private void locked_unfailAsNecessary() {
        int notFailingActive = 0;
        for (Iterator<Hash> iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
            Hash key = iter.next();
            PeerProfile peer = _notFailingPeers.get(key);
            if (peer.getIsActive())
                notFailingActive++;
            if (notFailingActive >= MIN_NOT_FAILING_ACTIVE) {
                // we've got enough, no need to try further
                return;
            }
        }
        
        // we dont have enough, lets unfail our best ones remaining
        int needToUnfail = MIN_NOT_FAILING_ACTIVE - notFailingActive;
        if (needToUnfail > 0) {
            int unfailed = 0;
            for (Iterator<PeerProfile> iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile best = iter.next();
                if ( (best.getIsActive()) && (best.getIsFailing()) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("All peers were failing, so we have overridden the failing flag for one of the most reliable active peers (" + best.getPeer().toBase64() + ")");
                    best.setIsFailing(false);
                    locked_placeProfile(best);
                    unfailed++;
                }
                if (unfailed >= needToUnfail)
                    break;
            }
        }
    }
    
    ////////
    // no more public stuff below
    ////////
    
    /**
     * Update the thresholds based on the profiles in this set.  currently
     * implements the capacity threshold based on the mean capacity of active
     * and nonfailing peers (falling back on the median if that results in too
     * few peers.  We then use the median speed from that group to define the 
     * speed threshold, and use the mean integration value from the 
     * high capacity group to define the integration threshold.
     *
     */
    private void locked_calculateThresholds(Set<PeerProfile> allPeers) {
        double totalCapacity = 0;
        double totalIntegration = 0;
        Set<PeerProfile> reordered = new TreeSet(_comp);
        for (Iterator<PeerProfile> iter = allPeers.iterator(); iter.hasNext(); ) {
            PeerProfile profile = iter.next();
            
            if (_us.equals(profile.getPeer())) continue;
            
            // only take into account active peers that aren't failing
            if (profile.getIsFailing() || (!profile.getIsActive()))
                continue;
        
            // dont bother trying to make sense of things below the baseline
            // otoh, keep them in the threshold calculation, so we can adapt
            ////if (profile.getCapacityValue() <= CapacityCalculator.GROWTH_FACTOR)
            ////    continue;
            
            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }
        
        locked_calculateCapacityThreshold(totalCapacity, reordered);
        locked_calculateSpeedThreshold(reordered);
        
        if (totalIntegration > 0)
            _thresholdIntegrationValue = 1.0d * avg(totalIntegration, reordered.size());
        else    // Make nobody rather than everybody well-integrated
            _thresholdIntegrationValue = 1.0d;
    }
    
    /**
     * Update the _thresholdCapacityValue by using a few simple formulas run 
     * against the specified peers.  Ideally, we set the threshold capacity to
     * the mean, as long as that gives us enough peers and is greater than the
     * median.
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers whose 
     *                  capacity is greater than the growth factor
     */
    private void locked_calculateCapacityThreshold(double totalCapacity, Set<PeerProfile> reordered) {
        int numNotFailing = reordered.size();
        
        double meanCapacity = avg(totalCapacity, numNotFailing);
        
        int minHighCapacityPeers = getMinimumHighCapacityPeers();
        
        int numExceedingMean = 0;
        double thresholdAtMedian = 0;
        double thresholdAtMinHighCap = 0;
        double thresholdAtLowest = CapacityCalculator.GROWTH_FACTOR;
        int cur = 0;
        for (Iterator<PeerProfile> iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = iter.next();
            double val = profile.getCapacityValue();
            if (val > meanCapacity)
                numExceedingMean++;
            if (cur == reordered.size()/2)
                thresholdAtMedian = val;
            if (cur == minHighCapacityPeers - 1)
                thresholdAtMinHighCap = val;
            if (cur == reordered.size() -1)
                thresholdAtLowest = val;
            cur++;
        }
        
        if (numExceedingMean >= minHighCapacityPeers) {
            // our average is doing well (growing, not recovering from failures)
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity is doing well [" + meanCapacity 
                          + "], and includes " + numExceedingMean);
            _thresholdCapacityValue = meanCapacity;
        } else if (meanCapacity > thresholdAtMedian &&
                   reordered.size()/2 > minHighCapacityPeers) {
            // avg > median, get the min High Cap peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity [" + meanCapacity + "] is greater than the median,"
                          + " so threshold is that reqd to get the min high cap peers " + thresholdAtMinHighCap);
            _thresholdCapacityValue = thresholdAtMinHighCap;
        } else if (reordered.size()/2 >= minHighCapacityPeers) {
            // ok mean is skewed low, but we still have enough to use the median
            // We really don't want to be here, since the default is 5.0 and the median
            // is inevitably 5.01 or so.
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity [" + meanCapacity + "] is skewed under the median,"
                          + " so use the median threshold " + thresholdAtMedian);
            _thresholdCapacityValue = thresholdAtMedian;
        } else {
            // our average is doing well, but not enough peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity is doing well [" + meanCapacity 
                          + "], but there aren't enough of them " + numExceedingMean);
            _thresholdCapacityValue = Math.max(thresholdAtMinHighCap, thresholdAtLowest);
        }
        
        // the base growth factor is the value we give to new routers that we don't
        // know anything about.  dont go under that limit unless you want to expose
        // the selection to simple ident flooding attacks
        if (_thresholdCapacityValue <= CapacityCalculator.GROWTH_FACTOR)
            _thresholdCapacityValue = CapacityCalculator.GROWTH_FACTOR + 0.0001;
    }
    
    /**
     * Update the _thresholdSpeedValue by calculating the median speed of all
     * high capacity peers. 
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers
     */
    private void locked_calculateSpeedThreshold(Set<PeerProfile> reordered) {
        if (true) {
            locked_calculateSpeedThresholdMean(reordered);
            return;
        }
/*****
        Set speeds = new TreeSet();
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                speeds.add(new Double(0-profile.getSpeedValue()));
            } else {
                // its ordered
                break;
            }
        }

        // calc the median speed of high capacity peers
        int i = 0;
        for (Iterator iter = speeds.iterator(); iter.hasNext(); i++) {
            Double speed = (Double)iter.next();
            if (i >= (speeds.size() / 2)) {
                _thresholdSpeedValue = 0-speed.doubleValue();
                break;
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " out of speeds: " + speeds);
*****/
    }
    
    private void locked_calculateSpeedThresholdMean(Set<PeerProfile> reordered) {
        double total = 0;
        int count = 0;
        for (Iterator<PeerProfile> iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = iter.next();
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                total += profile.getSpeedValue();
                count++;
            } else {
                // its ordered
                break;
            }
        }

        if (count > 0)
            _thresholdSpeedValue = total / count;
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " out of speeds: " + count);
    }
    
    
    /** simple average, or 0 if NaN */
    private final static double avg(double total, double quantity) {
        if ( (total > 0) && (quantity > 0) )
            return total/quantity;
        else
            return 0.0d;
    }
    
    /** called after locking the reorganizeLock */
    private PeerProfile locked_getProfile(Hash peer) {
        PeerProfile cur = (PeerProfile)_notFailingPeers.get(peer);
        if (cur != null) 
            return cur;
        cur = (PeerProfile)_failingPeers.get(peer);
        return cur;
    }
    
    /**
     * Select peers from the peer mapping, excluding appropriately and increasing the
     * matches set until it has howMany elements in it.
     *
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches) {
        locked_selectPeers(peers, howMany, toExclude, matches, 0);
    }

    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches, int mask) {
        List<Hash> all = new ArrayList(peers.keySet());
        Set<Integer> IPSet = new HashSet(8);
        // use RandomIterator to avoid shuffling the whole thing
        for (Iterator<Hash> iter = new RandomIterator(all); (matches.size() < howMany) && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer))
                continue;
            if (matches.contains(peer))
                continue;
            if (_us.equals(peer))
                continue;
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, IPSet, mask);
                if ((!ok) && _log.shouldLog(Log.WARN))
                    _log.warn("IP restriction prevents " + peer + " from joining " + matches);
            }
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }
    
    /**
     * Does the peer's IP address NOT match the IP address of any peer already in the set,
     * on any transport, within a given mask?
     * @param mask is 1-4 (number of bytes to match)
     * @param IPMatches all IPs so far, modified by this routine
     */
    private boolean notRestricted(Hash peer, Set<Integer> IPSet, int mask) {
        Set<Integer> peerIPs = maskedIPSet(peer, mask);
        if (containsAny(IPSet, peerIPs))
            return false;
        IPSet.addAll(peerIPs);
        return true;
    }

    /**
      * The Set of IPs for this peer, with a given mask.
      * Includes the comm system's record of the IP, and all netDb addresses.
      *
      * @return an opaque set of masked IPs for this peer
      */
    private Set<Integer> maskedIPSet(Hash peer, int mask) {
        Set<Integer> rv = new HashSet(2);
        byte[] commIP = _context.commSystem().getIP(peer);
        if (commIP != null)
            rv.add(maskedIP(commIP, mask));
        RouterInfo pinfo = _context.netDb().lookupRouterInfoLocally(peer);
        if (pinfo == null)
            return rv;
        Set<RouterAddress> paddr = pinfo.getAddresses();
        if (paddr == null)
            return rv;
        for (RouterAddress pa : paddr) {
            Properties pprops = pa.getOptions();
            if (pprops == null) continue;
            String phost = pprops.getProperty("host");
            if (phost == null) continue;
            InetAddress pi;
            try {
                pi = InetAddress.getByName(phost);
            } catch (UnknownHostException uhe) {
                continue;
            }
            if (pi == null) continue;
            byte[] pib = pi.getAddress();
            rv.add(maskedIP(pib, mask));
        }
        return rv;
    }

    /** generate an arbitrary unique value for this ip/mask (mask = 1-4) */
    private Integer maskedIP(byte[] ip, int mask) {
        int rv = 0;
        for (int i = 0; i < mask; i++)
             rv = (rv << 8) | (ip[i] & 0xff);
        return Integer.valueOf(rv);
    }

    /** does a contain any of the elements in b? */
    private boolean containsAny(Set a, Set b) {
        for (Object o : b) {
            if (a.contains(o))
                return true;
        }
        return false;
    }

    public boolean isSelectable(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        // the CLI shouldn't depend upon the netDb
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if ( (_context.shitlist() != null) && (_context.shitlist().isShitlisted(peer)) ) {
            // if (_log.shouldLog(Log.DEBUG))
            //     _log.debug("Peer " + peer.toBase64() + " is shitlisted, dont select it");
            return false; // never select a shitlisted peer
        }
            
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (null != info) {
            if (info.getIdentity().isHidden()) {
               if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer " + peer.toBase64() + " is marked as hidden, disallowing its use");
                return false;
            } else {
                boolean exclude = TunnelPeerSelector.shouldExclude(_context, info);
                if (exclude) {
                    // if (_log.shouldLog(Log.WARN))
                    //     _log.warn("Peer " + peer.toBase64() + " has capabilities or other stats suggesting we avoid it");
                    return false;
                } else {
                    // if (_log.shouldLog(Log.INFO))
                    //     _log.info("Peer " + peer.toBase64() + " is locally known, allowing its use");
                    return true;
                }
            }
        } else {
            // if (_log.shouldLog(Log.WARN))
            //    _log.warn("Peer " + peer.toBase64() + " is NOT locally known, disallowing its use");
            return false;
        }
    }
    
    /**
     * called after locking the reorganizeLock, place the profile in the appropriate tier.
     * This is where we implement the (betterThanAverage ? goToTierX : goToTierY) algorithms
     *
     */
    private void locked_placeProfile(PeerProfile profile) {
        if (profile.getIsFailing()) {
            if (!shouldDrop(profile))
                _failingPeers.put(profile.getPeer(), profile);
            _fastPeers.remove(profile.getPeer());
            _highCapacityPeers.remove(profile.getPeer());
            _wellIntegratedPeers.remove(profile.getPeer());
            _notFailingPeers.remove(profile.getPeer());
            _notFailingPeersList.remove(profile.getPeer());
        } else {
            _failingPeers.remove(profile.getPeer());
            _fastPeers.remove(profile.getPeer());
            _highCapacityPeers.remove(profile.getPeer());
            _wellIntegratedPeers.remove(profile.getPeer());
            
            _notFailingPeers.put(profile.getPeer(), profile);
            _notFailingPeersList.add(profile.getPeer());
            // if not selectable for a tunnel (shitlisted for example),
            // don't allow them in the high-cap pool, what would the point of that be?
            if (_thresholdCapacityValue <= profile.getCapacityValue() &&
                isSelectable(profile.getPeer())) {
                _highCapacityPeers.put(profile.getPeer(), profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("High capacity: \t" + profile.getPeer().toBase64());
                if (_thresholdSpeedValue <= profile.getSpeedValue()) {
                    if (!profile.getIsActive()) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Skipping fast mark [!active] for " + profile.getPeer().toBase64());
                    } else {
                        _fastPeers.put(profile.getPeer(), profile);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Fast: \t" + profile.getPeer().toBase64());
                    }
                }
                
            } else {
                // not high capacity, but not failing (yet)
            }
            // We aren't using the well-integrated list yet...
            // But by observation, the floodfill peers are often not in the
            // high-capacity group, so let's not require a peer to be high-capactiy
            // to call him well-integrated.
            // This could be used later to see if a floodfill peer is for real.
            if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
                _wellIntegratedPeers.put(profile.getPeer(), profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Integrated: \t" + profile.getPeer().toBase64());
            }
        }
    }
    
    /**
     * This is where we determine whether a failing peer is so poor and we're so overloaded
     * that we just want to forget they exist.  This algorithm won't need to be implemented until
     * after I2P 1.0, most likely, since we should be able to handle thousands of peers profiled
     * without ejecting any of them, but anyway, this is how we'd do it.  Most likely.
     *
     */
    private boolean shouldDrop(PeerProfile profile) { return false; }
    
    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some 
     * peers are particularly fast.
     *
     * Increase default by two for every local destination, up to a max.
     *
     * @return minimum number of peers to be placed in the 'fast' group
     */
    protected int getMinimumFastPeers() {
        int def = Math.min(DEFAULT_MAXIMUM_FAST_PEERS,
                           (2 *_context.clientManager().listClients().size()) + DEFAULT_MINIMUM_FAST_PEERS - 2);
        return _context.getProperty(PROP_MINIMUM_FAST_PEERS, def);
    }
    
    /** fixme add config  @since 0.7.10 */
    protected int getMaximumFastPeers() {
        return 30;
    }
    
    /** fixme add config  @since 0.7.11 */
    protected int getMaximumHighCapPeers() {
        return 75;
    }
    
    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some 
     * peers are particularly fast.
     *
     * @return minimum number of peers to be placed in the 'fast' group
     */
    protected int getMinimumHighCapacityPeers() {
        return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    
    /**
     * Read in all of the profiles specified and print out 
     * their calculated values.  Usage: <pre>
     *  ProfileOrganizer [filename]*
     * </pre>
     */
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(null); // new net.i2p.router.Router());
        ProfileOrganizer organizer = new ProfileOrganizer(ctx);
        organizer.setUs(Hash.FAKE_HASH);
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new java.io.File(args[i]));
            if (profile == null) {
                System.err.println("Could not load profile " + args[i]);
                continue;
            }
            organizer.addProfile(profile);
        }
        organizer.reorganize();
        DecimalFormat fmt = new DecimalFormat("0,000.0");
        fmt.setPositivePrefix("+");
        
        for (Iterator<Hash> iter = organizer.selectAllPeers().iterator(); iter.hasNext(); ) {
            Hash peer = iter.next();
            PeerProfile profile = organizer.getProfile(peer);
            if (!profile.getIsActive()) {
                System.out.println("Peer " + profile.getPeer().toBase64().substring(0,4) 
                           + " [" + (organizer.isFast(peer) ? "IF+R" : 
                                     organizer.isHighCapacity(peer) ? "IR  " :
                                     organizer.isFailing(peer) ? "IX  " : "I   ") + "]: "
                           + "\t Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive() 
                           + " Failing?\t" + profile.getIsFailing());
            } else {
                System.out.println("Peer " + profile.getPeer().toBase64().substring(0,4) 
                           + " [" + (organizer.isFast(peer) ? "F+R " : 
                                     organizer.isHighCapacity(peer) ? "R   " :
                                     organizer.isFailing(peer) ? "X   " : "    ") + "]: "
                           + "\t Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive() 
                           + " Failing?\t" + profile.getIsFailing());
            }
        }
        
        System.out.println("Thresholds:");
        System.out.println("Speed:       " + num(organizer.getSpeedThreshold()) + " (" + organizer.countFastPeers() + " fast peers)");
        System.out.println("Capacity:    " + num(organizer.getCapacityThreshold()) + " (" + organizer.countHighCapacityPeers() + " reliable peers)");
    }
    
}
