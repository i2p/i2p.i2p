package net.i2p.router.networkdb.kademlia;

import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class FloodOnlyLookupMatchJob extends JobImpl implements ReplyJob {
    private Log _log;
    private FloodOnlySearchJob _search;
    private DatabaseSearchReplyMessage _dsrm;
    public FloodOnlyLookupMatchJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _search = job;
        _dsrm = null;
    }
    public void runJob() { 
        if ( (getContext().netDb().lookupLeaseSetLocally(_search.getKey()) != null) ||
             (getContext().netDb().lookupRouterInfoLocally(_search.getKey()) != null) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": search match and found locally");
            _search.success();
        } else {
            int remaining = _search.getLookupsRemaining();
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": got a DatabaseSearchReply when we were looking for " 
                          + _search.getKey().toBase64() + ", with " + remaining + " outstanding searches");
            // netDb reply pointing us at other people
            // Only process if we don't know enough floodfills
            // This only works if both reply, otherwise we aren't called - should be fixed
            if (_search.shouldProcessDSRM() && _dsrm != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(_search.getJobId() + ": Processing DatabaseSearchReply");
                // Chase the hashes from the reply
                getContext().jobQueue().addJob(new SingleLookupJob(getContext(), _dsrm));
            }
            _search.failed();
        }
    }
    public String getName() { return "NetDb flood search (phase 1) match"; }
    public void setMessage(I2NPMessage message) {
        if (message instanceof DatabaseSearchReplyMessage) {
            // a dsrm is only passed in when there are no more lookups remaining
            // If more than one peer sent one, we only process the last one
            // And sadly if the first peer sends a DRSM and the second one times out,
            // this won't get called...
            _dsrm = (DatabaseSearchReplyMessage) message;
            _search.failed();
            return;
        }
        try {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            if (dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET)
                getContext().netDb().store(dsm.getKey(), dsm.getLeaseSet());
            else
                getContext().netDb().store(dsm.getKey(), dsm.getRouterInfo());
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(_search.getJobId() + ": Received an invalid store reply", iae);
        }
    }
}
