package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Retrieve a set of bids for a particular outbound message, and if any are found
 * that meet the message's requirements, register the message as in process and
 * pass it on to the transport for processing
 *
 */
public class GetBidsJob extends JobImpl {
    private Log _log;
    private CommSystemFacadeImpl _facade;
    private OutNetMessage _msg;
    
    public GetBidsJob(RouterContext ctx, CommSystemFacadeImpl facade, OutNetMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(GetBidsJob.class);
        _facade = facade;
        _msg = msg;
    }
    
    public String getName() { return "Fetch bids for a message to be delivered"; }
    public void runJob() {
        Hash to = _msg.getTarget().getIdentity().getHash();
        
        if (getContext().shitlist().isShitlisted(to)) {
            _log.warn("Attempt to send a message to a shitlisted peer - " + to);
            getContext().messageRegistry().peerFailed(to);
            fail();
            return;
        }
        
        Hash us = getContext().routerHash();
        if (to.equals(us)) {
            _log.error("wtf, send a message to ourselves?  nuh uh. msg = " + _msg, getAddedBy());
            fail();
            return;
        }
        
        List bids = _facade.getBids(_msg);
        if (bids.size() <= 0) {
            _log.warn("No bids available for the message " + _msg);
            getContext().shitlist().shitlistRouter(to, "No bids");
            getContext().netDb().fail(to);
            fail();
        } else {
            TransportBid bid = (TransportBid)bids.get(0);
            bid.getTransport().send(_msg);
        }
    }
    
    
    private void fail() {
        if (_msg.getOnFailedSendJob() != null) {
            getContext().jobQueue().addJob(_msg.getOnFailedSendJob());
        }
        if (_msg.getOnFailedReplyJob() != null) {
            getContext().jobQueue().addJob(_msg.getOnFailedReplyJob());
        }
        MessageSelector selector = _msg.getReplySelector();
        if (selector != null) {
            getContext().messageRegistry().unregisterPending(_msg);
        }
        
        getContext().profileManager().messageFailed(_msg.getTarget().getIdentity().getHash());
        
        _msg.discardData();
    }
}
