package net.i2p.router.peermanager;

import net.i2p.data.Hash;
import net.i2p.data.DataHelper;
import net.i2p.util.Clock;
import net.i2p.util.Log;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Keep the peer profiles organized according to the tiered model.  This does not 
 * actively update anything - the reorganize() method should be called periodically
 * to recalculate thresholds and move profiles into the appropriate tiers, and addProfile()
 * should be used to add new profiles (placing them into the appropriate groupings).
 */
public class ProfileOrganizer {
    private final static Log _log = new Log(ProfileOrganizer.class);
    private final static ProfileOrganizer _instance = new ProfileOrganizer();
    final static ProfileOrganizer getInstance() { return _instance; }
    /** This data should not be exposed */
    public static final ProfileOrganizer _getInstance() { return _instance; }
    /** H(routerIdentity) to PeerProfile for all peers that are fast and reliable */
    private Map _fastAndReliablePeers;
    /** H(routerIdentity) to PeerProfile for all peers that are reliable */
    private Map _reliablePeers;
    /** H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private Map _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private Map _notFailingPeers;
    /** H(routerIdentity) to PeerProfile for all peers that ARE failing horribly (but that we haven't dropped reference to yet) */
    private Map _failingPeers;
    /** who are we? */
    private Hash _us;
    
    /** PeerProfile objects for all peers profiled, orderd by most reliable first */
    private Set _strictReliabilityOrder;
    
    /** threshold speed value, seperating fast from slow */
    private double _thresholdSpeedValue;
    /** threshold reliability value, seperating reliable from unreliable */
    private double _thresholdReliabilityValue;
    /** integration value, seperating well integrated from not well integrated */
    private double _thresholdIntegrationValue;
    
    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private Object _reorganizeLock = new Object();
    
    /** incredibly weak PRNG, just used for shuffling peers.  no need to waste the real PRNG on this */
    private Random _random = new Random();
    
    private ProfileOrganizer() {
	_fastAndReliablePeers = new HashMap(64);
	_reliablePeers = new HashMap(512);
	_wellIntegratedPeers = new HashMap(256);
	_notFailingPeers = new HashMap(1024);
	_failingPeers = new HashMap(4096);
	_strictReliabilityOrder = new TreeSet(new InverseReliabilityComparator());
	_thresholdSpeedValue = 0.0d;
	_thresholdReliabilityValue = 0.0d;
	_thresholdIntegrationValue = 0.0d;
    }
    
    /**
     * Order profiles by their reliability, but backwards (most reliable / highest value first).
     * 
     */
    private static final class InverseReliabilityComparator implements Comparator {
	private static final Comparator _comparator = new InverseReliabilityComparator();
	public int compare(Object lhs, Object rhs) {
	    if ( (lhs == null) || (rhs == null) || (!(lhs instanceof PeerProfile)) || (!(rhs instanceof PeerProfile)) )
		throw new ClassCastException("Only profiles can be compared - lhs = " + lhs + " rhs = " + rhs);
	    PeerProfile left = (PeerProfile)lhs;
	    PeerProfile right= (PeerProfile)rhs;
	    // note below that yes, we are treating left and right backwards.  see: classname
	    int diff = (int)(right.getReliabilityValue() - left.getReliabilityValue());
	    // we can't just return that, since the set would b0rk on equal values (just because two profiles 
	    // rank the same way doesn't mean they're the same peer!)  So if they reliabilities are equal, we 
	    // order them by the peer's hash
	    if (diff != 0) 
		return diff;
	    if (left.getPeer().equals(right.getPeer()))
		return 0;
	    else
		return DataHelper.compareTo(right.getPeer().getData(), left.getPeer().getData());
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
    public PeerProfile addProfile(PeerProfile profile) {
	if ( (profile == null) || (profile.getPeer() == null) || (_us.equals(profile.getPeer())) ) return null;
	
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("New profile created for " + profile.getPeer().toBase64());
	
	synchronized (_reorganizeLock) {
	    PeerProfile old = locked_getProfile(profile.getPeer());
	    profile.coallesceStats();
	    locked_placeProfile(profile);
	    _strictReliabilityOrder.add(profile);
	    return old;
	}
    }
    
    public int countFastAndReliablePeers() { synchronized (_reorganizeLock) { return _fastAndReliablePeers.size(); } }
    public int countReliablePeers() { synchronized (_reorganizeLock) { return _reliablePeers.size(); } }
    public int countWellIntegratedPeers() { synchronized (_reorganizeLock) { return _wellIntegratedPeers.size(); } }
    public int countNotFailingPeers() { synchronized (_reorganizeLock) { return _notFailingPeers.size(); } }
    public int countFailingPeers() { synchronized (_reorganizeLock) { return _failingPeers.size(); } }
    
    public boolean isFastAndReliable(Hash peer) { synchronized (_reorganizeLock) { return _fastAndReliablePeers.containsKey(peer); } }
    public boolean isReliable(Hash peer) { synchronized (_reorganizeLock) { return _reliablePeers.containsKey(peer); } }
    public boolean isWellIntegrated(Hash peer) { synchronized (_reorganizeLock) { return _wellIntegratedPeers.containsKey(peer); } }
    public boolean isFailing(Hash peer) { synchronized (_reorganizeLock) { return _failingPeers.containsKey(peer); } }
    
    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto reliable peers, and if reliable
     * peers doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     *
     */
    public void selectFastAndReliablePeers(int howMany, Set exclude, Set matches) {
	synchronized (_reorganizeLock) {
	    locked_selectPeers(_fastAndReliablePeers, howMany, exclude, matches);
	}
	if (matches.size() < howMany) 
	    selectReliablePeers(howMany, exclude, matches);
	return;
    }
    
    /**
     * Return a set of Hashes for peers that are reliable.
     *
     */
    public void selectReliablePeers(int howMany, Set exclude, Set matches) {
	synchronized (_reorganizeLock) {
	    locked_selectPeers(_reliablePeers, howMany, exclude, matches);
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
		for (Iterator iter = _strictReliabilityOrder.iterator(); selected.size() < needed && iter.hasNext(); ) {
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
	    Set allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _reliablePeers.size() + _fastAndReliablePeers.size());
	    allPeers.addAll(_failingPeers.keySet());
	    allPeers.addAll(_notFailingPeers.keySet());
	    allPeers.addAll(_reliablePeers.keySet());
	    allPeers.addAll(_fastAndReliablePeers.keySet());
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
	    Set allPeers = new HashSet(_failingPeers.size() + _notFailingPeers.size() + _reliablePeers.size() + _fastAndReliablePeers.size());
	    allPeers.addAll(_failingPeers.values());
	    allPeers.addAll(_notFailingPeers.values());
	    allPeers.addAll(_reliablePeers.values());
	    allPeers.addAll(_fastAndReliablePeers.values());
	    
	    _failingPeers.clear();
	    _notFailingPeers.clear();
	    _reliablePeers.clear();
	    _fastAndReliablePeers.clear();
	    
	    calculateThresholds(allPeers);
	    
	    for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
		PeerProfile profile = (PeerProfile)iter.next();
		locked_placeProfile(profile);
	    }
	    
	    Set reordered = new TreeSet(InverseReliabilityComparator._comparator);
	    reordered.addAll(_strictReliabilityOrder);
	    
	    _strictReliabilityOrder = reordered;
	    
	    locked_unfailAsNecessary();
	}
	
	if (_log.shouldLog(Log.DEBUG)) {
	    _log.debug("Profiles reorganized.  averages: [integration: " + _thresholdIntegrationValue + ", reliability: " + _thresholdReliabilityValue + ", speed: " + _thresholdSpeedValue + "]");
	    _log.debug("Strictly organized: " + _strictReliabilityOrder);
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
	    for (Iterator iter = _strictReliabilityOrder.iterator(); iter.hasNext(); ) {
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
    
    ////////
    // no more public stuff below
    ////////
    
    /**
     * Update the thresholds based on the profiles in this set.  currently 
     * implements the thresholds based on a simple average (ignoring failing values),
     * with integration and speed being directly equal to the simple average as 
     * calculated over all reliable and active non-failing peers, while the reliability threshold
     * is half the simple average of active non-failing peers.  Lots of room to tune this.  
     * should this instead be top 10%?  top 90%?  top 50?  etc
     *
     */
    private void calculateThresholds(Set allPeers) {
	double totalReliability = 0;
	int numActive = 0;

	for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
	    PeerProfile profile = (PeerProfile)iter.next();
	    
	    if (_us.equals(profile.getPeer())) continue;
	    
	    // only take into account peers that we're talking to within the last
	    // few minutes
	    if ( (!profile.getIsActive()) || (profile.getIsFailing()) )
		continue;

	    numActive++;

	    if (profile.getReliabilityValue() > 0)
		totalReliability += profile.getReliabilityValue();
	}
	_thresholdReliabilityValue = 0.5d * avg(totalReliability, numActive);

	// now derive the integration and speed thresholds based ONLY on the reliable 
	// and active peers
	numActive = 0;
	double totalIntegration = 0;
	double totalSpeed = 0;
	
	for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
	    PeerProfile profile = (PeerProfile)iter.next();
	    
	    if (_us.equals(profile.getPeer())) continue;
	    
	    // only take into account peers that we're talking to within the last
	    // few minutes, who are reliable, AND who are not failing
	    if ( (!profile.getIsActive()) || (profile.getReliabilityValue() < _thresholdReliabilityValue) || (profile.getIsFailing()) )
		continue;

	    numActive++;
	    
	    if (profile.getIntegrationValue() > 0)
		totalIntegration += profile.getIntegrationValue();
	    if (profile.getSpeedValue() > 0)
		totalSpeed += profile.getSpeedValue();
	}
	
	
	_thresholdIntegrationValue = 1.0d * avg(totalIntegration, numActive);
	_thresholdSpeedValue       = 1.0d * avg(totalSpeed, numActive);
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
	Set rv = new HashSet(howMany);
	for (int i = 0; i < howMany && i < all.size(); i++) {
	    rv.add(all.get(i));
	}
	matches.addAll(rv);
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
	    _fastAndReliablePeers.remove(profile.getPeer());
	    _reliablePeers.remove(profile.getPeer());
	    _wellIntegratedPeers.remove(profile.getPeer());
	    _notFailingPeers.remove(profile.getPeer());
	} else {
	    _failingPeers.remove(profile.getPeer());
	    _fastAndReliablePeers.remove(profile.getPeer());
	    _reliablePeers.remove(profile.getPeer());
	    _wellIntegratedPeers.remove(profile.getPeer());
	    
	    _notFailingPeers.put(profile.getPeer(), profile);
	    if (_thresholdReliabilityValue <= profile.getReliabilityValue()) {
		_reliablePeers.put(profile.getPeer(), profile);
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Reliable: \t" + profile.getPeer().toBase64());
		if (_thresholdSpeedValue <= profile.getSpeedValue()) {
		    _fastAndReliablePeers.put(profile.getPeer(), profile);
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Fast: \t" + profile.getPeer().toBase64());
		}
		
		if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
		    _wellIntegratedPeers.put(profile.getPeer(), profile);
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Integrated: \t" + profile.getPeer().toBase64());
		}
	    } else {
		// not reliable, but not failing (yet)
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
	    ProfilePersistenceHelper.getInstance().writeProfile(prof, out);
    }
    
    public String renderStatusHTML() {
	Set peers = selectAllPeers();
	
	long hideBefore = Clock.getInstance().now() - 6*60*60*1000;
	
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
	StringBuffer buf = new StringBuffer(8*1024);
	buf.append("<h2>Peer Profiles</h2>\n");
	buf.append("<table border=\"1\">");
	buf.append("<tr>");
	buf.append("<td><b>Peer</b> (").append(order.size()).append(", hiding ").append(peers.size()-order.size()).append(" inactive ones)</td>");
	buf.append("<td><b>Groups</b></td>");
	buf.append("<td><b>Speed</b></td>");
	buf.append("<td><b>Reliability</b></td>");
	buf.append("<td><b>Integration</b></td>");
	buf.append("<td><b>Failing?</b></td>");
	buf.append("<td><b>Profile data</b></td>");
	buf.append("</tr>");
	for (Iterator iter = order.keySet().iterator(); iter.hasNext();) {
	    String name = (String)iter.next();
	    PeerProfile prof = (PeerProfile)order.get(name);
	    Hash peer = prof.getPeer();
	    	    
	    buf.append("<tr>");
	    buf.append("<td><code>");
	    if (prof.getIsFailing()) {
		buf.append("<font color=\"red\">--").append(peer.toBase64()).append("</font>");
	    } else {
		if (prof.getIsActive()) {
		    buf.append("<font color=\"blue\">++").append(peer.toBase64()).append("</font>");
		} else {
		    buf.append("__").append(peer.toBase64());
		}
	    }
	    buf.append("</code></td>");
	    buf.append("<td>");
	    int tier = 0;
	    boolean isIntegrated = false;
	    synchronized (_reorganizeLock) {
		if (_fastAndReliablePeers.containsKey(peer)) {
		    tier = 1;
		    fast++;
		    reliable++;
		} else if (_reliablePeers.containsKey(peer)) {
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
		case 1: buf.append("Fast+Reliable"); break;
		case 2: buf.append("Reliable"); break;
		case 3: buf.append("Not Failing"); break;
		default: buf.append("Failing"); break;
	    }
	    if (isIntegrated) buf.append(", Well integrated");
	    
	    buf.append("<td align=\"right\">").append(num(prof.getSpeedValue())).append("</td>");
	    buf.append("<td align=\"right\">").append(num(prof.getReliabilityValue())).append("</td>");
	    buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
	    buf.append("<td align=\"right\">").append(prof.getIsFailing()).append("</td>");
	    buf.append("<td><a href=\"/profile/").append(prof.getPeer().toBase64().substring(0, 32)).append("\">profile.txt</a> ");
	    buf.append("    <a href=\"#").append(prof.getPeer().toBase64().substring(0, 32)).append("\">netDb</a></td>");
	    buf.append("</tr>");
	}
	buf.append("</table>");
	buf.append("<i>Note that the speed, reliability, and integration values are relative");
	buf.append(" - they do NOT correspond with any particular throughput, latency, uptime, ");
	buf.append("or other metric.  Higher numbers are better.  ");
	buf.append("Red peers prefixed with '--' means the peer is failing, and blue peers prefixed ");
	buf.append("with '++' means we've sent or received a message from them ");
	buf.append("in the last five minutes</i><br />");
	buf.append("<b>Thresholds:</b><br />");
	buf.append("<b>Speed:</b> ").append(num(_thresholdSpeedValue)).append(" (").append(fast).append(" fast peers)<br />");
	buf.append("<b>Reliability:</b> ").append(num(_thresholdReliabilityValue)).append(" (").append(reliable).append(" reliable peers)<br />");
	buf.append("<b>Integration:</b> ").append(num(_thresholdIntegrationValue)).append(" (").append(integrated).append(" well integrated peers)<br />");
	return buf.toString();
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
}   
