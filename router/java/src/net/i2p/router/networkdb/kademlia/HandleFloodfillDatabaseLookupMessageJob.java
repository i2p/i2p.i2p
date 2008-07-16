package net.i2p.router.networkdb.kademlia;
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
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.HandleDatabaseLookupMessageJob;

/**
 * Handle a lookup for a key received from a remote peer.  Needs to be implemented
 * to send back replies, etc
 *
 */
public class HandleFloodfillDatabaseLookupMessageJob extends HandleDatabaseLookupMessageJob {
    public HandleFloodfillDatabaseLookupMessageJob(RouterContext ctx, DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx, receivedMessage, from, fromHash);    
    }
    
    protected boolean answerAllQueries() {
        if (!FloodfillNetworkDatabaseFacade.floodfillEnabled(getContext())) return false;
        return FloodfillNetworkDatabaseFacade.isFloodfill(getContext().router().getRouterInfo());
    }
}
