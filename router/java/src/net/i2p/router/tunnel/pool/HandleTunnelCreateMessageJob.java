package net.i2p.router.tunnel.pool;

import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.RouterIdentity;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.util.Log;

/**
 * Receive a request to join a tunnel, and if we aren't overloaded (per the
 * throttle), join it (updating the tunnelDispatcher), then send back the
 * agreement.  Even if we are overloaded, send back a reply stating how 
 * overloaded we are.
 *
 */
public class HandleTunnelCreateMessageJob extends JobImpl {
    private Log _log;
    private TunnelCreateMessage _request;
    private boolean _alreadySearched;
    
    /** job builder to redirect all tunnelCreateMessages through this job type */
    static class Builder implements HandlerJobBuilder {
        private RouterContext _ctx;
        public Builder(RouterContext ctx) { _ctx = ctx; }
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            return new HandleTunnelCreateMessageJob(_ctx, (TunnelCreateMessage)receivedMessage);
        }
    }
    
    
    public HandleTunnelCreateMessageJob(RouterContext ctx, TunnelCreateMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleTunnelCreateMessageJob.class);
        _request = msg;
        _alreadySearched = false;
    }
    
    private static final int STATUS_DEFERRED = 10000;
    
    public String getName() { return "Handle tunnel join request"; }
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("handle join request: " + _request);
        int status = shouldAccept();
        if (status == STATUS_DEFERRED) {
            return;
        } else if (status > 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("reject(" + status + ") join request: " + _request);
            sendRejection(status);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("accept join request: " + _request);
            accept();
        }
    }
    
    /** don't accept requests to join for 15 minutes or more */
    public static final int MAX_DURATION_SECONDS = 15*60;
    
    private int shouldAccept() {
        if (_request.getDurationSeconds() >= MAX_DURATION_SECONDS)
            return TunnelHistory.TUNNEL_REJECT_CRIT;
        Hash nextRouter = _request.getNextRouter();
        if (nextRouter != null) {
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(nextRouter);
            if (ri == null) {
                if (_alreadySearched) // only search once
                    return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
                getContext().netDb().lookupRouterInfo(nextRouter, new DeferredAccept(getContext(), true), new DeferredAccept(getContext(), false), 5*1000);
                _alreadySearched = true;
                return STATUS_DEFERRED;
            }
        }
        return getContext().throttle().acceptTunnelRequest(_request); 
    }
    
    private class DeferredAccept extends JobImpl {
        private boolean _shouldAccept;
        public DeferredAccept(RouterContext ctx, boolean shouldAccept) {
            super(ctx);
            _shouldAccept = shouldAccept;
        }
        public void runJob() {
            HandleTunnelCreateMessageJob.this.runJob();
        }
        private static final String NAME_OK = "Deferred tunnel accept";
        private static final String NAME_REJECT = "Deferred tunnel reject";
        public String getName() { return _shouldAccept ? NAME_OK : NAME_REJECT; }
    }
    
    private void accept() {
        byte recvId[] = new byte[4];
        getContext().random().nextBytes(recvId);
        
        HopConfig cfg = new HopConfig();
        long expiration = _request.getDurationSeconds()*1000 + getContext().clock().now();
        cfg.setExpiration(expiration);
        cfg.setIVKey(_request.getIVKey());
        cfg.setLayerKey(_request.getLayerKey());
        cfg.setOptions(_request.getOptions());
        cfg.setReceiveTunnelId(recvId);
        
        if (_request.getIsGateway()) {
            if (_log.shouldLog(Log.INFO))
                _log.info("join as inbound tunnel gateway pointing at " 
                           + _request.getNextRouter().toBase64().substring(0,4) + ":" 
                           + _request.getNextTunnelId().getTunnelId()
                           + " (nonce=" + _request.getNonce() + ")");
            // serve as the inbound tunnel gateway
            cfg.setSendTo(_request.getNextRouter());
            cfg.setSendTunnelId(DataHelper.toLong(4, _request.getNextTunnelId().getTunnelId()));
            getContext().tunnelDispatcher().joinInboundGateway(cfg);
        } else if (_request.getNextRouter() == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("join as outbound tunnel endpoint (nonce=" + _request.getNonce() + ")");
            // serve as the outbound tunnel endpoint
            getContext().tunnelDispatcher().joinOutboundEndpoint(cfg);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("join as tunnel participant pointing at " 
                           + _request.getNextRouter().toBase64().substring(0,4) + ":" 
                           + _request.getNextTunnelId().getTunnelId()
                           + " (nonce=" + _request.getNonce() + ")");
            // serve as a general participant
            cfg.setSendTo(_request.getNextRouter());
            cfg.setSendTunnelId(DataHelper.toLong(4, _request.getNextTunnelId().getTunnelId()));
            getContext().tunnelDispatcher().joinParticipant(cfg);
        }
        
        sendAcceptance(recvId);
    }
    
    private static final byte[] REJECTION_TUNNEL_ID = new byte[] { (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };
    private static final int REPLY_TIMEOUT = 30*1000;
    private static final int REPLY_PRIORITY = 500;
    
    private void sendAcceptance(byte recvId[]) {
        sendReply(recvId, TunnelCreateStatusMessage.STATUS_SUCCESS);
    }
    private void sendRejection(int severity) {
        sendReply(REJECTION_TUNNEL_ID, severity);
    }
    private void sendReply(byte recvId[], int status) {
        TunnelCreateStatusMessage reply = new TunnelCreateStatusMessage(getContext());
        reply.setNonce(_request.getNonce());
        reply.setReceiveTunnelId(new TunnelId(DataHelper.fromLong(recvId, 0, 4)));
        reply.setStatus(status);
        
        GarlicMessage msg = createReply(reply);
        if (msg == null)
            throw new RuntimeException("wtf, couldn't create reply? to " + _request);
        
        TunnelGatewayMessage gw = new TunnelGatewayMessage(getContext());
        gw.setMessage(msg);
        gw.setTunnelId(_request.getReplyTunnel());
        gw.setMessageExpiration(msg.getMessageExpiration());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending (" + status + ") to the tunnel " 
                       + _request.getReplyGateway().toBase64().substring(0,4) + ":"
                       + _request.getReplyTunnel() + " wrt " + _request);
        getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), gw, _request.getReplyGateway(), 
                                                                REPLY_TIMEOUT, REPLY_PRIORITY));
    }
    
    private GarlicMessage createReply(TunnelCreateStatusMessage reply) {
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        
        PayloadGarlicConfig cfg = new PayloadGarlicConfig();
        cfg.setPayload(reply);
        cfg.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        cfg.setDeliveryInstructions(instructions);
        cfg.setRequestAck(false);
        cfg.setExpiration(getContext().clock().now() + REPLY_TIMEOUT);
        cfg.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        GarlicMessage msg = GarlicMessageBuilder.buildMessage(getContext(), cfg, 
                                                              null, // we dont care about the tags 
                                                              null, // or keys sent
                                                              null, // and we don't know what public key to use
                                                              _request.getReplyKey(), _request.getReplyTag());
        return msg;
    }
    
}
