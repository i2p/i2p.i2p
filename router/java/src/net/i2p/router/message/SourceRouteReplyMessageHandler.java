package net.i2p.router.message;
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
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.SourceRouteReplyMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 * HandlerJobBuilder to build jobs to handle SourceRouteReplyMessages
 *
 */
public class SourceRouteReplyMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    public SourceRouteReplyMessageHandler(RouterContext context) {
        _context = context;
    }
    
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash, SourceRouteBlock replyBlock) {
        // ignore the replyBlock for now
        HandleSourceRouteReplyMessageJob job = new HandleSourceRouteReplyMessageJob(_context, (SourceRouteReplyMessage)receivedMessage, from, fromHash);
        return job;
    }
    
}
