package net.i2p.router.peermanager;

import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.util.Clock;
import net.i2p.router.RouterContext;

class PersistProfilesJob extends JobImpl {
    private PeerManager _mgr;
    private final static long PERSIST_DELAY = 10*60*1000;
    
    public PersistProfilesJob(RouterContext ctx, PeerManager mgr) {
        super(ctx);
        _mgr = mgr;
        getTiming().setStartAfter(_context.clock().now() + PERSIST_DELAY);
    }
    
    public String getName() { return "Persist profiles"; }
    public void runJob() {
        Set peers = _mgr.selectPeers();
        Hash hashes[] = new Hash[peers.size()];
        int i = 0;
        for (Iterator iter = peers.iterator(); iter.hasNext(); )
            hashes[i] = (Hash)iter.next();
        _context.jobQueue().addJob(new PersistProfileJob(hashes));
    }
    
    private class PersistProfileJob extends JobImpl {
        private Hash _peers[];
        private int _cur;
        public PersistProfileJob(Hash peers[]) {
            super(PersistProfilesJob.this._context);
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
                PersistProfilesJob.this.getTiming().setStartAfter(_context.clock().now() + PERSIST_DELAY);
                PersistProfilesJob.this._context.jobQueue().addJob(PersistProfilesJob.this);
            } else {
                // we've got peers left to persist, so requeue the persist profile job
                PersistProfilesJob.this._context.jobQueue().addJob(PersistProfileJob.this);
            }
        }
        public String getName() { return "Persist profile"; }
    }
}
