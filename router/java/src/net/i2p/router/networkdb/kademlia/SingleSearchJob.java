package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.OutNetMessage;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Ask a single peer for a single key.
 * This isn't really a flood-only search job at all, but we extend
 * FloodOnlySearchJob so we can use the same selectors, etc.
 *
 */
class SingleSearchJob extends FloodOnlySearchJob {
    private final Hash _to;
    private OutNetMessage _onm;

    private static final int TIMEOUT = 8*1000;

    public SingleSearchJob(RouterContext ctx, Hash key, Hash to) {
        // warning, null FloodfillNetworkDatabaseFacade ...
        // define our own failed() and success() below so _facade isn't used.
        super(ctx, null, key, null, null, TIMEOUT, false);
        _to = to;
    }

    @Override
    public String getName() { return "NetDb search key from DSRM"; }

    @Override
    public boolean shouldProcessDSRM() { return false; } // don't loop

    @Override
    public void runJob() {
        _onm = getContext().messageRegistry().registerPending(_replySelector, _onReply, _onTimeout, _timeoutMs);
        DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
        TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
        if ( (replyTunnel == null) || (outTunnel == null) ) {
            failed();
            return;
        }
        dlm.setFrom(replyTunnel.getPeer(0));
        dlm.setMessageExpiration(getContext().clock().now()+5*1000);
        dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
        dlm.setSearchKey(_key);
        
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Single search for " + _key.toBase64() + " to " + _to.toBase64());
        getContext().tunnelDispatcher().dispatchOutbound(dlm, outTunnel.getSendTunnelId(0), _to);
        _lookupsRemaining = 1;
    }

    @Override
    void failed() {
        getContext().messageRegistry().unregisterPending(_onm);
        getContext().profileManager().dbLookupFailed(_to);
    }

    @Override
    void success() {
        getContext().profileManager().dbLookupSuccessful(_to, System.currentTimeMillis()-_created);
    }
}
