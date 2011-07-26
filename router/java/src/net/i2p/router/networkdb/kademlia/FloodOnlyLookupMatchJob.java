package net.i2p.router.networkdb.kademlia;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class FloodOnlyLookupMatchJob extends JobImpl implements ReplyJob {
    private final Log _log;
    private final FloodOnlySearchJob _search;
    private DatabaseSearchReplyMessage _dsrm;

    public FloodOnlyLookupMatchJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _search = job;
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
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": got a DSM for " 
                          + dsm.getKey().toBase64());
            // This store will be duplicated by HFDSMJ
            // We do it here first to make sure it is in the DB before
            // runJob() and search.success() is called???
            // Should we just pass the DataStructure directly back to somebody?
            if (dsm.getEntry().getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                // Since HFDSMJ wants to setReceivedAsPublished(), we have to
                // set a flag saying this was really the result of a query,
                // so don't do that.
                LeaseSet ls = (LeaseSet) dsm.getEntry();
                ls.setReceivedAsReply();
                getContext().netDb().store(dsm.getKey(), ls);
            } else {
                getContext().netDb().store(dsm.getKey(), (RouterInfo) dsm.getEntry());
            }
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(_search.getJobId() + ": Received an invalid store reply", iae);
        }
    }
}
