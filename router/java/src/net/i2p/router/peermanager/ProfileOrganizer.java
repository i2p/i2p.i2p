package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
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
    private Log _log;
    private RouterContext _context;
    /** H(routerIdentity) to PeerProfile for all peers that are fast and high capacity*/
    private Map _fastPeers;
    /** H(routerIdentity) to PeerProfile for all peers that have high capacities */
    private Map _highCapacityPeers;
    /** H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private Map _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private Map _notFailingPeers;
    /** H(routerIdentity) to PeerProfile for all peers that ARE failing horribly (but that we haven't dropped reference to yet) */
    private Map _failingPeers;
    /** who are we? */
    private Hash _us;
    private ProfilePersistenceHelper _persistenceHelper;
    
    /** PeerProfile objects for all peers profiled, orderd by the ones with the highest capacity first */
    private Set _strictCapacityOrder;
    
    /** threshold speed value, seperating fast from slow */
    private double _thresholdSpeedValue;
    /** threshold reliability value, seperating reliable from unreliable */
    private double _thresholdCapacityValue;
    /** integration value, seperating well integrated from not well integrated */
    private double _thresholdIntegrationValue;
    
    private InverseCapacityComparator _comp;

    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  See
     * {@link ProfileOrganizer#getMinimumFastPeers}
     *
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 8;
    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should 
     * select when using the mean - if less than this many are available, select the 
     * capacity by the median.  
     *
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 10;
    
    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private Object _reorganizeLock = new Object();
    
    /** incredibly weak PRNG, just used for shuffling peers.  no need to waste the real PRNG on this */
    private Random _random = new Random();
    
    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap(16);
        _highCapacityPeers = new HashMap(16);
        _wellIntegratedPeers = new HashMap(16);
        _notFailingPeers = new HashMap(16);
        _failingPeers = new HashMap(16);
        _strictCapacityOrder = new TreeSet(_comp);
        _thresholdSpeedValue = 0.0d;
        _thresholdCapacityValue = 0.0d;
        _thresholdIntegrationValue = 0.0d;
        _persistenceHelper = new ProfilePersistenceHelper(_context);
    }
    
    /**
     * Order profiles by their capacity, but backwards (highest capacity / value first).
     *
     */
    private final class InverseCapacityComparator implements Comparator {
        /**
         * Compare the two objects backwards.  The standard comparator returns
         * -1 if lhs is less than rhs, 1 if lhs is greater than rhs, or 0 if they're
         * equal.  To keep a strict ordering, we measure peers with equal capacity
         * values according to their hashes
         *
         * @return -1 if the right hand side is smaller, 1 if the left hand side is
         *         smaller, or 0 if they are the same peer (Comparator.compare() inverted)
         */
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) || (!(lhs instanceof PeerProfile)) || (!(rhs instanceof PeerProfile)) )
                throw new ClassCastException("Only profiles can be compared - lhs = " + lhs + " rhs = " + rhs);
            PeerProfile left = (PeerProfile)lhs;
            PeerProfile right= (PeerProfile)rhs;
             
            double rval = right.getCapacityValue();
            double lval = left.getCapacityValue();
            
            if (lval == rval) // note the following call inverts right and left (see: classname)
                return DataHelper.compareTo(right.getPeer().getData(), left.getPeer().getData());
            
            boolean rightBigger = rval > lval;

            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("The capacity of " + right.getPeer().toBase64() 
            //               + " and " + left.getPeer().toBase64() + " marks " + (rightBigger ? "right" : "left")
            //               + " as larger: r=" + right.getCapacityValue() 
            //               + " l="
            //               + left.getCapacityValue());
                           
            if (rightBigger)
                return 1;
            else
                return -1;
        }
    }
    
    public void setUs(Hash us) { _us = us; }
    
    /**
     * Retrieve the profile for the given peer, if one exists (else null)
     *
     */
    public PeerProfile getProfile(Hash peer) {
        synchronized (_reorganizeLock) {
            return locked_getProfile(peer);
        }
    }
    
    /**
     * Add the new profile, returning the old value (or null if no profile existed)
     *
     */
    public PeerProfile addProfile(PeerProfile profile) throws IllegalStateException {
        if ( (profile == null) || (profile.getPeer() == null) ) return null;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("New profile created for " + profile.getPeer().toBase64());
        
        synchronized (_reorganizeLock) {
            PeerProfile old = locked_getProfile(profile.getPeer());
            profile.coallesceStats();
            locked_placeProfile(profile);
            _strictCapacityOrder.add(profile);
            return old;
        }
    }
    
    public int countFastPeers() { synchronized (_reorganizeLock) { return _fastPeers.size(); } }
    public int countHighCapacityPeers() { synchronized (_reorganizeLock) { return _highCapacityPeers.size(); } }
    public int countWellIntegratedPeers() { synchronized (_reorganizeLock) { return _wellIntegratedPeers.size(); } }
    public int countNotFailingPeers() { synchronized (_reorganizeLock) { return _notFailingPeers.size(); } }
    public int countFailingPeers() { synchronized (_reorganizeLock) { return _failingPeers.size(); } }
    
    public int countActivePeers() {
        synchronized (_reorganizeLock) {
            int activePeers = 0;
            
            long hideBefore = _context.clock().now() - 6*60*60*1000;
            
            for (Iterator iter = _failingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            for (Iterator iter = _notFailingPeers.values().iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            
            return activePeers;
        }
    }
    
    public boolean isFast(Hash peer) { synchronized (_reorganizeLock) { return _fastPeers.containsKey(peer); } }
    public boolean isHighCapacity(Hash peer) { synchronized (_reorganizeLock) { return _highCapacityPeers.containsKey(peer); } }
    public boolean isWellIntegrated(Hash peer) { synchronized (_reorganizeLock) { return _wellIntegratedPeers.containsKey(peer); } }
    public boolean isFailing(Hash peer) { synchronized (_reorganizeLock) { return _failingPeers.containsKey(peer); } }
    
        
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
        if (profile != null) {
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
    public void selectFastPeers(int howMany, Set exclude, Set matches) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_fastPeers, howMany, exclude, matches);
        }
        if (matches.size() < howMany)
            selectHighCapacityPeers(howMany, exclude, matches);
        return;
    }
    
    /**
     * Return a set of Hashes for peers that have a high capacity
     *
     */
    public void selectHighCapacityPeers(int howMany, Set exclude, Set matches) {
        synchronized (_reorganizeLock) {
            // we only use selectHighCapacityPeers when we are selecting for PURPOSE_TEST
            // or we are falling back due to _fastPeers being too small, so we can always 
            // exclude the fast peers
            if (exclude == null)
                exclude = new HashSet(_fastPeers.keySet());
            else
                exclude.addAll(_fastPeers.keySet());
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches);
        }
        if (matches.size() < howMany)
            selectNotFailingPeers(howMany, exclude, matches);
        return;
    }
    /**
     * Return a set of Hashes for peers that are well integrated into the network.
     *
     */
    public void selectWellIntegratedPeers(int howMany, Set exclude, Set matches) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_wellIntegratedPeers, howMany, exclude, matches);
        }
        if (matches.size() < howMany)
            selectNotFailingPeers(howMany, exclude, matches);
        return;
    }
    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     */
    public void selectNotFailingPeers(int howMany, Set exclude, Set matches) {
        if (matches.size() < howMany)
            selectActiveNotFailingPeers(howMany, exclude, matches);
        return;
    }
    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     */
    private void selectActiveNotFailingPeers(int howMany, Set exclude, Set matches) {
        if (true) {
            selectAllNotFailingPeers(howMany, exclude, matches);
            return;
        }
        // pick out the not-failing peers that we're actively talking with
        if (matches.size() < howMany) {
            synchronized (_reorganizeLock) {
                for (Iterator iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    if ( (exclude != null) && exclude.contains(peer) ) continue;
                    if (matches.contains(peer)) continue;
                    PeerProfile prof = (PeerProfile)_notFailingPeers.get(peer);
                    if (prof.getIsActive())
                        matches.add(peer);
                    if (matches.size() >= howMany)
                        return;
                }
            }
        }
        // ok, still not enough, pick out the not-failing peers that we aren't talking with
        if (matches.size() < howMany)
            selectAllNotFailingPeers(howMany, exclude, matches);
        return;
    }
    /**
     * Return a set of Hashes for peers that are not failing.
     *
     */
    private void selectAllNotFailingPeers(int howMany, Set exclude, Set matches) {
        if (matches.size() < howMany) {
            int orig = matches.size();
            int needed = howMany - orig;
            List selected = new ArrayList(needed);
            synchronized (_reorganizeLock) {
                for (Iterator iter = _strictCapacityOrder.iterator(); selected.size() < needed && iter.hasNext(); ) {
                    PeerProfile prof = (PeerProfile)iter.next();
                    if (matches.contains(prof.getPeer()) ||
                    (exclude != null && exclude.contains(prof.getPeer())) ||
                    _failingPeers.containsKey(prof.getPeer()))
                        continue;
                    else
                        selected.add(prof.getPeer());
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Selecting all not failing found " + (matches.size()-orig) + " new peers: " + selected);
            matches.addAll(selected);
        }
        if (matches.size() < howMany)
            selectFailingPeers(howMany, exclude, matches);
        return;
    }
    /**
     * I'm not quite sure why you'd want this... (other than for failover from the better results)
     *
     */
    public void selectFailingPeers(int howMany, Set exclude, Set matches) {
        synchronized (_reorganizeLock) {
            locked_selectPeers(_failingPeers, howMany, exclude, matches);
        }
        return;
    }
    
    /**
     * Find the hashes for all peers we are actively profiling
     *
     */
    public Set selectAllPeers() {
        synchronized (_reorganizeLock) {
            Set allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_failingPeers.keySet());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        }
    }
    
    /**
     * Place peers into the correct tier, as well as expand/contract and even drop profiles
     * according to whatever limits are in place.  Peer profiles are not coallesced during
     * this method, but the averages are recalculated.
     *
     */
    public void reorganize() {
        synchronized (_reorganizeLock) {
            Set allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_failingPeers.values());
            allPeers.addAll(_notFailingPeers.values());
            allPeers.addAll(_highCapacityPeers.values());
            allPeers.addAll(_fastPeers.values());
            
            _failingPeers.clear();
            _notFailingPeers.clear();
            _highCapacityPeers.clear();
            _fastPeers.clear();
    
            Set reordered = new TreeSet(_comp);
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile prof = (PeerProfile)iter.next();
                reordered.add(prof);
            }
            _strictCapacityOrder = reordered;
            
            locked_calculateThresholds(allPeers);
            
            _failingPeers.clear();
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _wellIntegratedPeers.clear();
            
            for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
                PeerProfile profile = (PeerProfile)iter.next();
                locked_placeProfile(profile);
            }
            
            locked_unfailAsNecessary();
            locked_promoteFastAsNecessary();
            
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Profiles reorganized.  averages: [integration: " + _thresholdIntegrationValue 
                           + ", capacity: " + _thresholdCapacityValue + ", speed: " + _thresholdSpeedValue + "]");
                StringBuffer buf = new StringBuffer(512);
                for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                    PeerProfile prof = (PeerProfile)iter.next();
                    buf.append('[').append(prof.toString()).append('=').append(prof.getCapacityValue()).append("] ");
                }
                _log.debug("Strictly organized (highest capacity first): " + buf.toString());
                _log.debug("fast: " + _fastPeers.values());
            }
        }
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
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need to explicitly promote " + numToPromote + " peers to the fast group");
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile cur = (PeerProfile)iter.next();
                if ( (!_fastPeers.containsKey(cur.getPeer())) && (!cur.getIsFailing()) ) {
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
        for (Iterator iter = _notFailingPeers.keySet().iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            PeerProfile peer = (PeerProfile)_notFailingPeers.get(key);
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
            for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile best = (PeerProfile)iter.next();
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
    
    public double getSpeedThreshold() { return _thresholdSpeedValue; }
    public double getCapacityThreshold() { return _thresholdCapacityValue; }
    
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
    private void locked_calculateThresholds(Set allPeers) {
        double totalCapacity = 0;
        double totalIntegration = 0;
        Set reordered = new TreeSet(_comp);
        for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            
            if (_us.equals(profile.getPeer())) continue;
            
            // only take into account active peers that aren't failing
            if (profile.getIsFailing() || (!profile.getIsActive()))
                continue;
        
            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }
        
        locked_calculateCapacityThreshold(totalCapacity, reordered);
        locked_calculateSpeedThreshold(reordered);
        
        _thresholdIntegrationValue = 1.0d * avg(totalIntegration, reordered.size());
    }
    
    
    /**
     * Update the _thresholdCapacityValue by using a few simple formulas run 
     * against the specified peers.  Ideally, we set the threshold capacity to
     * the mean, as long as that gives us enough peers and the mean is a "growth"
     * value.  We fall back on the capacity of the top K-th capacity, or the 
     * mean, or the base growth value, depending on various circumstances.
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers
     */
    private void locked_calculateCapacityThreshold(double totalCapacity, Set reordered) {
        int numNotFailing = reordered.size();
        
        double meanCapacity = avg(totalCapacity, numNotFailing);
        
        long baseline = CapacityCalculator.GROWTH_FACTOR;
        int minHighCapacityPeers = getMinimumHighCapacityPeers();
        
        int numExceedingMean = 0;
        int numExceedingBaseline = 0;
        double thresholdAtMedian = 0;
        double thresholdAtMinHighCap = 0;
        int cur = 0;
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            double val = profile.getCapacityValue();
            if (val > meanCapacity)
                numExceedingMean++;
            if (val > baseline)
                numExceedingBaseline++;
            if (cur == reordered.size()/2)
                thresholdAtMedian = val;
            if (cur == minHighCapacityPeers)
                thresholdAtMinHighCap = val;
            cur++;
        }
        
        if (meanCapacity > baseline) {
            // our average is doing well (growing, not recovering from failures)
            if (numExceedingMean > minHighCapacityPeers) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our average capacity is doing well [" + meanCapacity 
                              + "], and includes " + numExceedingMean);
                _thresholdCapacityValue = meanCapacity;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our average capacity is doing well [" + meanCapacity 
                              + "], but it is skewed to only have " + numExceedingMean
                              + " so falling back on the top few to " + thresholdAtMinHighCap);
                _thresholdCapacityValue = thresholdAtMinHighCap;
            }
        } else {
            // our average isn't doing well (its recovering from failures)
            if (numExceedingBaseline > minHighCapacityPeers) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our average capacity isn't doing well [" + meanCapacity 
                              + "], but the baseline has " + numExceedingBaseline);
                _thresholdCapacityValue = baseline+.0001;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our average capacity isn't doing well [" + meanCapacity 
                              + "], and the baseline has " + numExceedingBaseline 
                              + " so falling back on the median of " + thresholdAtMedian);
                _thresholdCapacityValue = thresholdAtMedian;
            }
        }
    }
    
    /**
     * Update the _thresholdSpeedValue by calculating the median speed of all
     * high capacity peers. 
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers
     */
    private void locked_calculateSpeedThreshold(Set reordered) {
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
        if (_notFailingPeers.containsKey(peer))
            return (PeerProfile)_notFailingPeers.get(peer);
        else if (_failingPeers.containsKey(peer))
            return (PeerProfile)_failingPeers.get(peer);
        else
            return null;
    }
    
    /**
     * Select peers from the peer mapping, excluding appropriately and increasing the
     * matches set until it has howMany elements in it.
     *
     */
    private void locked_selectPeers(Map peers, int howMany, Set toExclude, Set matches) {
        List all = new ArrayList(peers.keySet());
        if (toExclude != null)
            all.removeAll(toExclude);
        all.removeAll(matches);
        all.remove(_us);
        howMany -= matches.size();
        Collections.shuffle(all, _random);
        for (int i = 0; i < howMany && i < all.size(); i++) {
            matches.add(all.get(i));
        }
    }
    
    /**
     * called after locking the reorganizeLock, place the profile in the appropriate tier.
     * This is where we implement the (betterThanAverage ? goToPierX : goToPierY) algorithms
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
        } else {
            _failingPeers.remove(profile.getPeer());
            _fastPeers.remove(profile.getPeer());
            _highCapacityPeers.remove(profile.getPeer());
            _wellIntegratedPeers.remove(profile.getPeer());
            
            _notFailingPeers.put(profile.getPeer(), profile);
            if (_thresholdCapacityValue <= profile.getCapacityValue()) { 
                _highCapacityPeers.put(profile.getPeer(), profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("High capacity: \t" + profile.getPeer().toBase64());
                if (_thresholdSpeedValue <= profile.getSpeedValue()) {
                    _fastPeers.put(profile.getPeer(), profile);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Fast: \t" + profile.getPeer().toBase64());
                }
                
                if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
                    _wellIntegratedPeers.put(profile.getPeer(), profile);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Integrated: \t" + profile.getPeer().toBase64());
                }
            } else {
                // not high capacity, but not failing (yet)
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
    
    public void exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        if (prof != null)
            _persistenceHelper.writeProfile(prof, out);
    }
    
    public void renderStatusHTML(OutputStream out) throws IOException {
        Set peers = selectAllPeers();
        
        long hideBefore = _context.clock().now() - 6*60*60*1000;
        
        TreeMap order = new TreeMap();
        for (Iterator iter = peers.iterator(); iter.hasNext();) {
            Hash peer = (Hash)iter.next();
            if (_us.equals(peer)) continue;
            PeerProfile prof = getProfile(peer);
            if (prof.getLastSendSuccessful() <= hideBefore) continue;
            order.put(peer.toBase64(), prof);
        }
        
        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        int failing = 0;
        StringBuffer buf = new StringBuffer(16*1024);
        buf.append("<h2>Peer Profiles</h2>\n");
        buf.append("<table border=\"1\">");
        buf.append("<tr>");
        buf.append("<td><b>Peer</b> (").append(order.size()).append(", hiding ").append(peers.size()-order.size()).append(")</td>");
        buf.append("<td><b>Groups</b></td>");
        buf.append("<td><b>Speed</b></td>");
        buf.append("<td><b>Capacity</b></td>");
        buf.append("<td><b>Integration</b></td>");
        buf.append("<td><b>Failing?</b></td>");
        buf.append("<td>&nbsp;</td>");
        buf.append("</tr>");
        for (Iterator iter = order.keySet().iterator(); iter.hasNext();) {
            String name = (String)iter.next();
            PeerProfile prof = (PeerProfile)order.get(name);
            Hash peer = prof.getPeer();
            
            buf.append("<tr>");
            buf.append("<td><code>");
            if (prof.getIsFailing()) {
                buf.append("<font color=\"red\">--").append(peer.toBase64().substring(0,6)).append("</font>");
            } else {
                if (prof.getIsActive()) {
                    buf.append("<font color=\"blue\">++").append(peer.toBase64().substring(0,6)).append("</font>");
                } else {
                    buf.append("__").append(peer.toBase64().substring(0,6));
                }
            }
            buf.append("</code></td>");
            buf.append("<td>");
            int tier = 0;
            boolean isIntegrated = false;
            synchronized (_reorganizeLock) {
                if (_fastPeers.containsKey(peer)) {
                    tier = 1;
                    fast++;
                    reliable++;
                } else if (_highCapacityPeers.containsKey(peer)) {
                    tier = 2;
                    reliable++;
                } else if (_notFailingPeers.containsKey(peer)) {
                    tier = 3;
                } else {
                    failing++;
                }
                
                if (_wellIntegratedPeers.containsKey(peer)) {
                    isIntegrated = true;
                    integrated++;
                }
            }
            
            switch (tier) {
                case 1: buf.append("Fast"); break;
                case 2: buf.append("High Capacity"); break;
                case 3: buf.append("Not Failing"); break;
                default: buf.append("Failing"); break;
            }
            if (isIntegrated) buf.append(", Integrated");
            
            buf.append("<td align=\"right\">").append(num(prof.getSpeedValue())).append("</td>");
            buf.append("<td align=\"right\">").append(num(prof.getCapacityValue())).append("</td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            buf.append("<td align=\"right\">").append(prof.getIsFailing()).append("</td>");
            //buf.append("<td><a href=\"/profile/").append(prof.getPeer().toBase64().substring(0, 32)).append("\">profile.txt</a> ");
            //buf.append("    <a href=\"#").append(prof.getPeer().toBase64().substring(0, 32)).append("\">netDb</a></td>");
            buf.append("<td><a href=\"netdb.jsp#").append(peer.toBase64().substring(0,6)).append("\">netDb</a></td>\n");
            buf.append("</tr>");
        }
        buf.append("</table>");
        buf.append("<i>Definitions:<ul>");
        buf.append("<li><b>speed</b>: how many round trip messages can we pump through the peer per minute?</li>");
        buf.append("<li><b>capacity</b>: how many tunnels can we ask them to join in an hour?</li>");
        buf.append("<li><b>integration</b>: how many new peers have they told us about lately?</li>");
        buf.append("<li><b>failing?</b>: is the peer currently swamped (and if possible we should avoid nagging them)?</li>");
        buf.append("</ul></i>");
        buf.append("Red peers prefixed with '--' means the peer is failing, and blue peers prefixed ");
        buf.append("with '++' means we've sent or received a message from them ");
        buf.append("in the last five minutes</i><br />");
        buf.append("<b>Thresholds:</b><br />");
        buf.append("<b>Speed:</b> ").append(num(_thresholdSpeedValue)).append(" (").append(fast).append(" fast peers)<br />");
        buf.append("<b>Capacity:</b> ").append(num(_thresholdCapacityValue)).append(" (").append(reliable).append(" high capacity peers)<br />");
        buf.append("<b>Integration:</b> ").append(num(_thresholdIntegrationValue)).append(" (").append(integrated).append(" well integrated peers)<br />");
        out.write(buf.toString().getBytes());
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
    protected int getMinimumFastPeers() {
        if (_context.router() != null) {
            String val = _context.router().getConfigSetting(PROP_MINIMUM_FAST_PEERS);
            if (val != null) {
                try {
                    int rv = Integer.parseInt(val);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug("router config said " + PROP_MINIMUM_FAST_PEERS + '=' + val);
                    return rv;
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Minimum fast peers improperly set in the router config [" + val + "]", nfe);
                }
            }
        }
        String val = _context.getProperty(PROP_MINIMUM_FAST_PEERS, ""+DEFAULT_MINIMUM_FAST_PEERS);
        if (val != null) {
            try {
                int rv = Integer.parseInt(val);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("router context said " + PROP_MINIMUM_FAST_PEERS + '=' + val);
                return rv;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Minimum fast peers improperly set in the router environment [" + val + "]", nfe);
            }
        }
        
        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug("no config for " + PROP_MINIMUM_FAST_PEERS + ", using " + DEFAULT_MINIMUM_FAST_PEERS);
        return DEFAULT_MINIMUM_FAST_PEERS;
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
        if (_context.router() != null) {
            String val = _context.router().getConfigSetting(PROP_MINIMUM_HIGH_CAPACITY_PEERS);
            if (val != null) {
                try {
                    int rv = Integer.parseInt(val);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug("router config said " + PROP_MINIMUM_HIGH_CAPACITY_PEERS + '=' + val);
                    return rv;
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Minimum high capacity peers improperly set in the router config [" + val + "]", nfe);
                }
            }
        }
        String val = _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, ""+DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
        if (val != null) {
            try {
                int rv = Integer.parseInt(val);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("router context said " + PROP_MINIMUM_HIGH_CAPACITY_PEERS + '=' + val);
                return rv;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Minimum high capacity peers improperly set in the router environment [" + val + "]", nfe);
            }
        }
        
        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug("no config for " + PROP_MINIMUM_HIGH_CAPACITY_PEERS + ", using " + DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
        return DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS;
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
        RouterContext ctx = new RouterContext(new net.i2p.router.Router());
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
        
        for (Iterator iter = organizer.selectAllPeers().iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            PeerProfile profile = organizer.getProfile(peer);
            if (!profile.getIsActive()) continue;
            System.out.println("Peer " + profile.getPeer().toBase64().substring(0,4) 
                       + " [" + (organizer.isFast(peer) ? "F+R" : 
                                 organizer.isHighCapacity(peer) ? "R  " :
                                 organizer.isFailing(peer) ? "X  " : "   ") + "]: "
                       + "\t Speed:\t" + fmt.format(profile.getSpeedValue())
                       + " Reliability:\t" + fmt.format(profile.getReliabilityValue())
                       + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                       + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                       + " Active?\t" + profile.getIsActive() 
                       + " Failing?\t" + profile.getIsFailing());

        }
        
        System.out.println("Thresholds:");
        System.out.println("Speed:       " + num(organizer.getSpeedThreshold()) + " (" + organizer.countFastPeers() + " fast peers)");
        System.out.println("Capacity:    " + num(organizer.getCapacityThreshold()) + " (" + organizer.countHighCapacityPeers() + " reliable peers)");
    }
    
}
