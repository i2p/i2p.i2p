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
    private I2NPMessage _message;
    private RouterIdentity _fromRouter;
    private Hash _fromRouterHash;
    private SourceRouteBlock _replyBlock;
    
    public InNetMessage() {
	setMessage(null);
	setFromRouter(null);
	setFromRouterHash(null);
	setReplyBlock(null);
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
    
    public String toString() {
	StringBuffer buf = new StringBuffer(512);
	buf.append("InNetMessage: from [").append(getFromRouter()).append("] aka [").append(getFromRouterHash()).append("] message: ").append(getMessage());
	return buf.toString();
    }
}
