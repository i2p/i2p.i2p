package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.Log;

/**
 * Batching preprocessor that will briefly delay the sending of a message 
 * if it doesn't fill up a full tunnel message, in which case it queues up
 * an additional flush task.  This is a very simple threshold algorithm - 
 * as soon as there is enough data for a full tunnel message, it is sent.  If
 * after the delay there still isn't enough data, what is available is sent
 * and padded.
 *
 */
public class BatchedPreprocessor extends TrivialPreprocessor {
    private Log _log;
    private long _pendingSince;
    
    public BatchedPreprocessor(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(BatchedPreprocessor.class);
        _pendingSince = 0;
        ctx.statManager().createRateStat("tunnel.batchMultipleCount", "How many messages are batched into a tunnel message", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchDelay", "How many messages were pending when the batching waited", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchDelaySent", "How many messages were flushed when the batching delay completed", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    private static final int FULL_SIZE = PREPROCESSED_SIZE 
                                         - IV_SIZE 
                                         - 1  // 0x00 ending the padding
                                         - 4; // 4 byte checksum

    private static final boolean DISABLE_BATCHING = false;
    
    /* not final or private so the test code can adjust */
    static long DEFAULT_DELAY = 500;
    /** wait up to 2 seconds before sending a small message */
    protected long getSendDelay() { return DEFAULT_DELAY; }
    
    public boolean preprocessQueue(List pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Preprocess queue with " + pending.size() + " to send");
        
        if (DISABLE_BATCHING || getSendDelay() <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("No batching, send all messages immediately");
            while (pending.size() > 0) {
                // loops because sends may be partial
                TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.get(0);
                send(pending, 0, 0, sender, rec);
                if (msg.getOffset() >= msg.getData().length)
                    pending.remove(0);
            }
            return false;
        }

        while (pending.size() > 0) {
            int allocated = 0;
            for (int i = 0; i < pending.size(); i++) {
                TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.get(i);
                int instructionsSize = getInstructionsSize(msg);
                instructionsSize += getInstructionAugmentationSize(msg, allocated, instructionsSize);
                int curWanted = msg.getData().length - msg.getOffset() + instructionsSize;
                allocated += curWanted;
                if (allocated >= FULL_SIZE) {
                    if (allocated - curWanted + instructionsSize >= FULL_SIZE) {
                        // the instructions alone exceed the size, so we won't get any
                        // of the message into it.  don't include it
                        i--;
                        msg = (TunnelGateway.Pending)pending.get(i);
                        allocated -= curWanted;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Pushback of " + curWanted + " (message " + (i+1) + ")");
                    }
                    if (_pendingSince > 0)
                        _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), 0);
                    _pendingSince = 0;
                    send(pending, 0, i, sender, rec);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Allocated=" + allocated + " so we sent " + (i+1) 
                                  + " (last complete? " + (msg.getOffset() >= msg.getData().length) + ")");

                    for (int j = 0; j < i; j++)
                        pending.remove(0);
                    if (msg.getOffset() >= msg.getData().length) {
                        // ok, this last message fit perfectly, remove it too
                        pending.remove(0);
                    }
                    if (i > 0)
                        _context.statManager().addRateData("tunnel.batchMultipleCount", i+1, 0);
                    allocated = 0;
                    break;
                }
            }
            
            if (allocated > 0) {
                // after going through the entire pending list, we still don't
                // have enough data to send a full message

                if ( (_pendingSince > 0) && (_pendingSince + getSendDelay() <= _context.clock().now()) ) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Passed through pending list, with " + allocated + "/" + pending.size()
                                  + " left to clean up, but we've waited, so flush");

                    // not even a full message, but we want to flush it anyway
                    
                    if (pending.size() > 1)
                        _context.statManager().addRateData("tunnel.batchMultipleCount", pending.size(), 0);
                    _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), 0);

                    send(pending, 0, pending.size()-1, sender, rec);
                    pending.clear();
                    _pendingSince = 0;
                    return false;
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Passed through pending list, with " + allocated + "/"+ pending.size()
                                  + " left to clean up, but we've haven't waited, so don't flush (wait="
                                  + (_context.clock().now() - _pendingSince) + " / " + _pendingSince + ")");
                    _context.statManager().addRateData("tunnel.batchDelay", pending.size(), 0);
                    if (_pendingSince <= 0)
                        _pendingSince = _context.clock().now();
                    // not yet time to send the delayed flush
                    return true;
                }
            } else {
                // ok, we sent some, but haven't gone back for another 
                // pass yet.  keep looping
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sent everything on the list (pending=" + pending.size() + ")");

        // sent everything from the pending list, no need to delayed flush
        return false;
    }
    
    /**
     * Preprocess the messages from the pending list, grouping items startAt 
     * through sendThrough (though only part of the last one may be fully 
     * sent), delivering them through the sender/receiver.
     *
     * @param startAt first index in pending to send (inclusive)
     * @param sendThrough last index in pending to send (inclusive)
     */
    protected void send(List pending, int startAt, int sendThrough, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending " + startAt + ":" + sendThrough + " out of " + pending.size());
        byte preprocessed[] = _dataCache.acquire().getData();
        
        int offset = 0;
        offset = writeFragments(pending, startAt, sendThrough, preprocessed, offset);
        // preprocessed[0:offset] now contains the fragments from the pending,
        // so we need to format, pad, and rearrange according to the spec to
        // generate the final preprocessed data
        
        if (offset <= 0) {
            StringBuffer buf = new StringBuffer(128);
            buf.append("wtf, written offset is ").append(offset);
            buf.append(" for ").append(startAt).append(" through ").append(sendThrough);
            for (int i = startAt; i <= sendThrough; i++) {
                buf.append(" ").append(pending.get(i).toString());
            }
            _log.log(Log.CRIT, buf.toString());
            return;
        }
        
        preprocess(preprocessed, offset);
        
        sender.sendPreprocessed(preprocessed, rec);
    }
    
    /**
     * Write the fragments out of the pending list onto the target, updating 
     * each of the Pending message's offsets accordingly.
     *
     * @return new offset into the target for further bytes to be written
     */
    private int writeFragments(List pending, int startAt, int sendThrough, byte target[], int offset) {
        for (int i = startAt; i <= sendThrough; i++) {
            TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.get(i);
            int prevOffset = offset;
            if (msg.getOffset() == 0) {
                offset = writeFirstFragment(msg, target, offset);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("writing " + msg.getMessageId() + " fragment 0, ending at " + offset + " prev " + prevOffset
                               + " leaving " + (msg.getData().length - msg.getOffset()) + " bytes for later");
            } else {
                offset = writeSubsequentFragment(msg, target, offset);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("writing " + msg.getMessageId() + " fragment " + (msg.getFragmentNumber()-1) 
                               + ", ending at " + offset + " prev " + prevOffset
                               + " leaving " + (msg.getData().length - msg.getOffset()) + " bytes for later");
            }
        }
        return offset;
    }
}
