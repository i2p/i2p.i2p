package net.i2p.router;
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

/**
 * Wrap an I2NP message received from the network prior to handling and processing.
 *
 */
public class InNetMessage {
    private RouterContext _context;
    private I2NPMessage _message;
    private RouterIdentity _fromRouter;
    private Hash _fromRouterHash;
    private SourceRouteBlock _replyBlock;
    private long _created;
    
    public InNetMessage(RouterContext context) {
        _context = context;
        setMessage(null);
        setFromRouter(null);
        setFromRouterHash(null);
        setReplyBlock(null);
        context.messageStateMonitor().inboundMessageAdded();
        _created = context.clock().now();
        _context.statManager().createRateStat("inNetMessage.timeToDiscard", 
                                              "How long until we discard an inbound msg?",
                                              "InNetMessage", new long[] { 5*60*1000, 30*60*1000, 60*60*1000 });
    }
    
    /**
     * Retrieve the message
     *
     */
    public I2NPMessage getMessage() { return _message; }
    public void setMessage(I2NPMessage msg) { _message = msg; }
    
    /**
     * Hash of the router identity from which this message was received, if availale
     *
     */
    public Hash getFromRouterHash() { return _fromRouterHash; }
    public void setFromRouterHash(Hash routerIdentHash) { _fromRouterHash = routerIdentHash; }
    
    /**
     * Router identity from which this message was received, if availale
     *
     */
    public RouterIdentity getFromRouter() { return _fromRouter; }
    public void setFromRouter(RouterIdentity router) { _fromRouter = router; }
    
    /**
     * Retrieve any source route block supplied with this message for replies
     *
     * @return source route block, or null if it was not supplied /or/ if it was already
     *         used in an ack
     */
    public SourceRouteBlock getReplyBlock() { return _replyBlock; }
    public void setReplyBlock(SourceRouteBlock block) { _replyBlock = block; }
    
    /**
     * Call this after we're done dealing with this message (when we no
     * longer need its data)
     *
     */
    public void processingComplete() {
        _message = null;
        _context.messageStateMonitor().inboundMessageRead();
        long timeToDiscard = _context.clock().now() - _created;
        _context.statManager().addRateData("inNetMessage.timeToDiscard", 
                                           timeToDiscard, timeToDiscard);
    }
    
    public void finalize() {
        _context.messageStateMonitor().inboundMessageFinalized();
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(512);
        buf.append("InNetMessage: from [").append(getFromRouter());
        buf.append("] aka [").append(getFromRouterHash());
        buf.append("] message: ").append(getMessage());
        return buf.toString();
    }
}
