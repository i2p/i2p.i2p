package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Send a DeliveryStatusMessage to the location specified in the source route block
 * acknowledging the ackId given.  This uses the simplest technique (don't garlic, and
 * send direct to where the SourceRouteBlock requested), but it could instead garlic it
 * and send it via a tunnel or garlic route it additionally)
 *
 */
public class SendMessageAckJob extends JobImpl {
    private SourceRouteBlock _block;
    private long _ackId;
    
    public final static int ACK_PRIORITY = 100;
    
    public SendMessageAckJob(RouterContext ctx, SourceRouteBlock block, long ackId) {
        super(ctx);
        _block = block;
        _ackId = ackId;
    }
    
    public void runJob() {
        _context.jobQueue().addJob(new SendReplyMessageJob(_context, _block, createAckMessage(), ACK_PRIORITY));
    }
    
    /**
     * Create whatever should be delivered to the intermediary hop so that
     * a DeliveryStatusMessage gets to the intended recipient.
     *
     * Currently this doesn't garlic encrypt the DeliveryStatusMessage with
     * the block's tag and sessionKey, but it could.
     *
     */
    protected I2NPMessage createAckMessage() {
        DeliveryStatusMessage statusMessage = new DeliveryStatusMessage(_context);
        statusMessage.setArrival(new Date(_context.clock().now()));
        statusMessage.setMessageId(_ackId);
        return statusMessage;
    }
    
    public String getName() { return "Send Message Ack"; }
}
