package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Manage the testing for free, outbound, and active inbound client tunnels
 *
 */
class TunnelTestManager {
    private final static Log _log = new Log(TunnelTestManager.class);
    private TunnelPool _pool;
    private boolean _stopTesting;
    
    private final static long MINIMUM_RETEST_DELAY = 60*1000; // dont test tunnels more than once every 30 seconds
    
    /** avg # tests per tunnel lifetime that we want */
    private final static int TESTS_PER_DURATION = 2;
    /** how many times we'll be able to try the tests (this should take into consideration user prefs, but fsck it for now) */
    private final static int CHANCES_PER_DURATION = 8;
    
    public TunnelTestManager(TunnelPool pool) {
	_pool = pool;
	_stopTesting = false;
	JobQueue.getInstance().addJob(new CoordinateTunnelTestingJob());
    }
    
    private Set selectTunnelsToTest() {
	Set allIds = getAllIds();
	Set toTest = new HashSet(allIds.size());
	long now = Clock.getInstance().now();
	for (Iterator iter = allIds.iterator(); iter.hasNext();) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getTunnelInfo(id);
	    if ( (info != null) && (info.getSettings() != null) ) {
		if (info.getSettings().getExpiration() <= 0) {
		    // skip local tunnels
		} else if (!info.getIsReady()) {
		    // skip not ready tunnels
		} else if (info.getSettings().getExpiration() < now + MINIMUM_RETEST_DELAY) {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Tunnel " + id.getTunnelId() + " will be expiring within the current period (" + new Date(info.getSettings().getExpiration()) + "), so skip testing it");
		} else if (info.getSettings().getCreated() + MINIMUM_RETEST_DELAY < now) {
		    double probability = TESTS_PER_DURATION / (allIds.size() * CHANCES_PER_DURATION);
		    if (RandomSource.getInstance().nextInt(10) <= (probability*10d)) {
			toTest.add(id);
		    } else {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Tunnel " + id.getTunnelId() + " could be tested, but probabilistically isn't going to be");
		    }
		} else {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Tunnel " + id.getTunnelId() + " was just created (" + new Date(info.getSettings().getCreated()) + "), wait until the next pass to test it");
		}
	    } else {
		if (_log.shouldLog(Log.WARN))
		    _log.warn("Hmm, a normally testable tunnel [" + id.getTunnelId() + "] didn't have info or settings: " + info);
	    }
	}
	return toTest;
    }
    
    private Set getAllIds() {
	return _pool.getManagedTunnelIds();
    }
    
    public void stopTesting() { _stopTesting = true; }
    
    private void runTest(TunnelId tunnel) {
	JobQueue.getInstance().addJob(new TestTunnelJob(tunnel, _pool));
    }
    
    private class CoordinateTunnelTestingJob extends JobImpl {
	public CoordinateTunnelTestingJob() {
	    super();
	    getTiming().setStartAfter(Clock.getInstance().now() + MINIMUM_RETEST_DELAY);
	}
	public String getName() { return "Coordinate Tunnel Testing"; }
	public void runJob() { 
	    if (_stopTesting) return;
	    
	    Set toTestIds = selectTunnelsToTest();
	    if (_log.shouldLog(Log.INFO))
		_log.info("Running tests on selected tunnels: " + toTestIds);
	    for (Iterator iter = toTestIds.iterator(); iter.hasNext(); ) {
		TunnelId id = (TunnelId)iter.next();
		runTest(id);
	    }
	    reschedule();
	}
	
	private void reschedule() {
	    long minNext = Clock.getInstance().now() + MINIMUM_RETEST_DELAY;
	    long nxt = minNext + RandomSource.getInstance().nextInt(60*1000); // test tunnels once every 30-90 seconds
	    getTiming().setStartAfter(nxt);
	    if (_log.shouldLog(Log.INFO))
		_log.info("Rescheduling tunnel tests for " + new Date(nxt));
	    JobQueue.getInstance().addJob(CoordinateTunnelTestingJob.this);
	}
    }
}
