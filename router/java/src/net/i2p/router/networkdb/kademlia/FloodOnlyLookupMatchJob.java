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

    public FloodOnlyLookupMatchJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _search = job;
    }

    public void runJob() { 
        if (getContext().netDb().lookupLocally(_search.getKey()) != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": search match and found locally");
            _search.success();
        } else {
            // In practice, we always have zero remaining when this is called,
            // because the selector only returns true when there is zero remaining
            _search.failed();
        }
    }

    public String getName() { return "NetDb flood search (phase 1) match"; }

    public void setMessage(I2NPMessage message) {
        if (message instanceof DatabaseSearchReplyMessage) {
            // DSRM processing now in FloodOnlyLookupSelector instead of here,
            // a dsrm is only passed in when there are no more lookups remaining
            // so that all DSRM's are processed, not just the last one.
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
