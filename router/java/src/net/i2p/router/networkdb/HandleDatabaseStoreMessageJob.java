package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Receive DatabaseStoreMessage data and store it in the local net db
 *
 */
public class HandleDatabaseStoreMessageJob extends JobImpl {
    private Log _log;
    private DatabaseStoreMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;

    public HandleDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleDatabaseStoreMessageJob.class);
        ctx.statManager().createRateStat("netDb.storeHandled", "How many netDb store messages have we handled?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database store message");
	
        boolean wasNew = false;
        if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET) {
            Object match = _context.netDb().store(_message.getKey(), _message.getLeaseSet());
            wasNew = (null == match);
        } else if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Handling dbStore of router " + _message.getKey() + " with publishDate of " 
                          + new Date(_message.getRouterInfo().getPublished()));
            Object match = _context.netDb().store(_message.getKey(), _message.getRouterInfo());
            wasNew = (null == match);
            _context.profileManager().heardAbout(_message.getKey());
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid DatabaseStoreMessage data type - " + _message.getValueType() 
                           + ": " + _message);
        }
        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null)
            _context.profileManager().dbStoreReceived(_fromHash, wasNew);
        _context.statManager().addRateData("netDb.storeHandled", 1, 0);
    }

    public String getName() { return "Handle Database Store Message"; }
    
    public void dropped() {
        _context.messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
