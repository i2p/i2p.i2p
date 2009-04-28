package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Build a HandleDatabaseLookupMessageJob whenever a DatabaseLookupMessage arrives
 *
 */
public class DatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private Log _log;
    public DatabaseLookupMessageHandler(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(DatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "How many netDb lookups have we received?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        // FIXME: is netDb.lookupsDropped actually used elsewhere?
        _context.statManager().createRateStat("netDb.lookupsDropped", "How many netDb lookups did we drop due to throttling?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _context.statManager().addRateData("netDb.lookupsReceived", 1, 0);
        return new HandleDatabaseLookupMessageJob(_context, (DatabaseLookupMessage)receivedMessage, from, fromHash);
    }
}
