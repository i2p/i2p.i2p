package net.i2p.router.peermanager;

import java.util.Set;
import java.util.Iterator;

import net.i2p.data.Hash;
import net.i2p.util.Clock;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;

class PersistProfilesJob extends JobImpl {
    private PeerManager _mgr;
    private final static long PERSIST_DELAY = 10*60*1000;
    
    public PersistProfilesJob(PeerManager mgr) {
	_mgr = mgr;
	getTiming().setStartAfter(Clock.getInstance().now() + PERSIST_DELAY);
    }
    
    public String getName() { return "Persist profiles"; }
    public void runJob() {
	Set peers = _mgr.selectPeers();
	Hash hashes[] = new Hash[peers.size()];
	int i = 0;
	for (Iterator iter = peers.iterator(); iter.hasNext(); ) 
	    hashes[i] = (Hash)iter.next();
	JobQueue.getInstance().addJob(new PersistProfileJob(hashes));
    }
    
    private class PersistProfileJob extends JobImpl {
	private Hash _peers[];
	private int _cur;
	public PersistProfileJob(Hash peers[]) {
	    _peers = peers;
	    _cur = 0;
	}
	public void runJob() {
	    if (_cur < _peers.length) {
		_mgr.storeProfile(_peers[_cur]);
		_cur++;
	    }
	    if (_cur >= _peers.length) {
		// no more left, requeue up the main persist-em-all job
		PersistProfilesJob.this.getTiming().setStartAfter(Clock.getInstance().now() + PERSIST_DELAY);
		JobQueue.getInstance().addJob(PersistProfilesJob.this);
	    } else {
		// we've got peers left to persist, so requeue the persist profile job
		JobQueue.getInstance().addJob(PersistProfileJob.this);
	    }
	}
	public String getName() { return "Persist profile"; }
    }
}
