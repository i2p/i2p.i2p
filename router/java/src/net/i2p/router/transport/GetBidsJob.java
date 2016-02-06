package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

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
class GetBidsJob extends JobImpl {
    private final Log _log;
    private final TransportManager _tmgr;
    private final OutNetMessage _msg;
    
    /**
     *  @deprecated unused, see static getBids()
     */
    @Deprecated
    public GetBidsJob(RouterContext ctx, TransportManager tmgr, OutNetMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(GetBidsJob.class);
        _tmgr = tmgr;
        _msg = msg;
    }
    
    public String getName() { return "Fetch bids for a message to be delivered"; }
    public void runJob() {
        getBids(getContext(), _tmgr, _msg);
    }
    
    static void getBids(RouterContext context, TransportManager tmgr, OutNetMessage msg) {
        Log log = context.logManager().getLog(GetBidsJob.class);
        Hash to = msg.getTarget().getIdentity().getHash();
        msg.timestamp("bid");
        
        if (context.banlist().isBanlisted(to)) {
            if (log.shouldLog(Log.WARN))
                log.warn("Attempt to send a message to a banlisted peer - " + to);
            //context.messageRegistry().peerFailed(to);
            context.statManager().addRateData("transport.bidFailBanlisted", msg.getLifetime());
            fail(context, msg);
            return;
        }
        
        Hash us = context.routerHash();
        if (to.equals(us)) {
            if (log.shouldLog(Log.ERROR))
                log.error("send a message to ourselves?  nuh uh. msg = " + msg);
            context.statManager().addRateData("transport.bidFailSelf", msg.getLifetime());
            fail(context, msg);
            return;
        }
        
        TransportBid bid = tmgr.getNextBid(msg);
        if (bid == null) {
            int failedCount = msg.getFailedTransports().size();
            if (failedCount == 0) {
                context.statManager().addRateData("transport.bidFailNoTransports", msg.getLifetime());
                // This used to be "no common transports" but it is almost always no transports at all
                context.banlist().banlistRouter(to, _x("No transports (hidden or starting up?)"));
            } else if (failedCount >= tmgr.getTransportCount()) {
                context.statManager().addRateData("transport.bidFailAllTransports", msg.getLifetime());
                // fail after all transports were unsuccessful
                context.netDb().fail(to);
            }
            fail(context, msg);
        } else {
            if (log.shouldLog(Log.INFO))
                log.info("Attempting to send on transport " + bid.getTransport().getStyle() + ": " + bid);
            bid.getTransport().send(msg);
        }
    }
    
    
    static void fail(RouterContext context, OutNetMessage msg) {
        if (msg.getOnFailedSendJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedSendJob());
        }
        if (msg.getOnFailedReplyJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedReplyJob());
        }
        MessageSelector selector = msg.getReplySelector();
        if (selector != null) {
            context.messageRegistry().unregisterPending(msg);
        }
        
        context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash());
        
        msg.discardData();
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }
}
