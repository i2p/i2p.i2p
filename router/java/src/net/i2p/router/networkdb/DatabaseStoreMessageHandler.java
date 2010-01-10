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
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 * Create a HandleDatabaseStoreMessageJob whenever a DatabaseStoreMessage arrives
 * Unused - see kademlia/ for replacement
 */
public class DatabaseStoreMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    public DatabaseStoreMessageHandler(RouterContext context) {
        _context = context;
    }
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        return new HandleDatabaseStoreMessageJob(_context, (DatabaseStoreMessage)receivedMessage, from, fromHash);
    }
}
