package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.networkdb.HandleDatabaseLookupMessageJob;
import net.i2p.util.Log;

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
