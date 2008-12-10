package net.i2p.router.peermanager;

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
