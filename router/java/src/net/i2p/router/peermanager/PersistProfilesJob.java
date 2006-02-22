package net.i2p.router.peermanager;

import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

class PersistProfilesJob extends JobImpl {
    private PeerManager _mgr;
    private final static long PERSIST_DELAY = 10*60*1000;
    
    public PersistProfilesJob(RouterContext ctx, PeerManager mgr) {
        super(ctx);
        _mgr = mgr;
        getTiming().setStartAfter(getContext().clock().now() + PERSIST_DELAY);
    }
    
    public String getName() { return "Persist profiles"; }
    public void runJob() {
        Set peers = _mgr.selectPeers();
        getContext().jobQueue().addJob(new PersistProfileJob(getContext(), this, peers));
    }
    void persist(Hash peer) { _mgr.storeProfile(peer); }
    void requeue() { requeue(PERSIST_DELAY); }
}

class PersistProfileJob extends JobImpl {
    private PersistProfilesJob _job;
    private Iterator _peers;
    public PersistProfileJob(RouterContext enclosingContext, PersistProfilesJob job, Set peers) {
        super(enclosingContext);
        _peers = peers.iterator();
        _job = job;
    }
    public void runJob() {
        if (_peers.hasNext())
            _job.persist((Hash)_peers.next());
        if (_peers.hasNext()) {
            requeue(1000);
        } else {
            // no more left, requeue up the main persist-em-all job
            _job.requeue();
        }
    }
    public String getName() { return "Persist profile"; }
}