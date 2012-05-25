package net.i2p.router.tunnel;

import java.util.List;

import net.i2p.I2PAppContext;
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
    private String _name;
    
    public BatchedPreprocessor(I2PAppContext ctx, String name) {
        super(ctx);
        _log = ctx.logManager().getLog(BatchedPreprocessor.class);
        _name = name;
        _pendingSince = 0;
        ctx.statManager().createRateStat("tunnel.batchMultipleCount", "How many messages are batched into a tunnel message", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchDelay", "How many messages were pending when the batching waited", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchDelaySent", "How many messages were flushed when the batching delay completed", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchCount", "How many groups of messages were flushed together", "Tunnels", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchDelayAmount", "How long we should wait before flushing the batch", "Tunnels", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.batchFlushRemaining", "How many messages remain after flushing", "Tunnels", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("tunnel.writeDelay", "How long after a message reaches the gateway is it processed (lifetime is size)", "Tunnels", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    private static final int FULL_SIZE = PREPROCESSED_SIZE 
                                         - IV_SIZE 
                                         - 1  // 0x00 ending the padding
                                         - 4; // 4 byte checksum

    private static final boolean DISABLE_BATCHING = false;
    
    /* not final or private so the test code can adjust */
    static long DEFAULT_DELAY = 100;
    /** wait up to 2 seconds before sending a small message */
    protected long getSendDelay() { return DEFAULT_DELAY; }
    
    /** if we have 50 messages queued that are too small, flush them anyway */
    private static final int FORCE_BATCH_FLUSH = 50;
    
    /** how long do we want to wait before flushing */
    @Override
    public long getDelayAmount() { return getDelayAmount(true); }
    private long getDelayAmount(boolean shouldStat) {
        long rv = -1;
        long defaultAmount = getSendDelay();
        if (_pendingSince > 0)
            rv = _pendingSince + defaultAmount - _context.clock().now();
        if (rv > defaultAmount)
            rv = defaultAmount;
        if (shouldStat)
            _context.statManager().addRateData("tunnel.batchDelayAmount", rv, 0);
        return rv;
    }
    
    @Override
    public boolean preprocessQueue(List pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        StringBuilder timingBuf = null;
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Preprocess queue with " + pending.size() + " to send");
            timingBuf = new StringBuilder(128);
            timingBuf.append("Preprocess with " + pending.size() + " to send. ");
        }
        if (DISABLE_BATCHING) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Disabled batching, pushing " + pending + " immediately");
            return super.preprocessQueue(pending, sender, rec);
        }
        long start = System.currentTimeMillis();
        
        int batchCount = 0;
        int beforeLooping = pending.size();
        
        while (pending.size() > 0) {
            int allocated = 0;
            long beforePendingLoop = System.currentTimeMillis();
            for (int i = 0; i < pending.size(); i++) {
                long pendingStart = System.currentTimeMillis();
                TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.get(i);
                int instructionsSize = getInstructionsSize(msg);
                instructionsSize += getInstructionAugmentationSize(msg, allocated, instructionsSize);
                int curWanted = msg.getData().length - msg.getOffset() + instructionsSize;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("pending " + i + "/" +pending.size() 
                               + " (" + msg.getMessageId() + ") curWanted=" + curWanted 
                               + " instructionSize=" + instructionsSize + " allocated=" + allocated);
                allocated += curWanted;
                if (allocated >= FULL_SIZE) {
                    if (allocated - curWanted + instructionsSize >= FULL_SIZE) {
                        // the instructions alone exceed the size, so we won't get any
                        // of the message into it.  don't include it
                        i--;
                        msg = (TunnelGateway.Pending)pending.get(i);
                        allocated -= curWanted;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Pushback of " + curWanted + " (message " + (i+1) + " in " + pending + ")");
                    }
                    if (_pendingSince > 0) {
                        long waited = _context.clock().now() - _pendingSince;
                        _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), waited);
                    }
                    long beforeSend = System.currentTimeMillis();
                    _pendingSince = 0;
                    send(pending, 0, i, sender, rec);
                    long afterSend = System.currentTimeMillis();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Allocated=" + allocated + " so we sent " + (i+1) 
                                  + " (last complete? " + (msg.getOffset() >= msg.getData().length) 
                                  + ", off=" + msg.getOffset() + ", count=" + pending.size() + ")");

                    for (int j = 0; j < i; j++) {
                        TunnelGateway.Pending cur = (TunnelGateway.Pending)pending.remove(0);
                        if (cur.getOffset() < cur.getData().length)
                            throw new IllegalArgumentException("i=" + i + " j=" + j + " off=" + cur.getOffset() 
                                                               + " len=" + cur.getData().length + " alloc=" + allocated);
                        if (timingBuf != null)
                            timingBuf.append(" sent " + cur);
                        notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), cur.getData().length, cur.getMessageIds(), "flushed allocated");
                        _context.statManager().addRateData("tunnel.writeDelay", cur.getLifetime(), cur.getData().length);
                    }
                    if (msg.getOffset() >= msg.getData().length) {
                        // ok, this last message fit perfectly, remove it too
                        TunnelGateway.Pending cur = (TunnelGateway.Pending)pending.remove(0);
                        if (timingBuf != null)
                            timingBuf.append(" sent perfect fit " + cur).append(".");
                        notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), msg.getData().length, msg.getMessageIds(), "flushed tail, remaining: " + pending);
                        _context.statManager().addRateData("tunnel.writeDelay", cur.getLifetime(), cur.getData().length);
                    }
                    if (i > 0)
                        _context.statManager().addRateData("tunnel.batchMultipleCount", i+1, 0);
                    allocated = 0;
                    batchCount++;
                    long pendingEnd = System.currentTimeMillis();
                    if (timingBuf != null)
                        timingBuf.append(" After sending " + (i+1) + "/"+pending.size() +" in " + (afterSend-beforeSend) 
                                         + " after " + (beforeSend-pendingStart)
                                         + " since " + (beforeSend-beforePendingLoop)
                                         + "/" + (beforeSend-start)
                                         + " pending current " + (pendingEnd-pendingStart)).append(".");
                    break;
                }
                if (timingBuf != null)
                    timingBuf.append(" After pending loop " + (System.currentTimeMillis()-beforePendingLoop)).append(".");
            }
            
            long afterCleared = System.currentTimeMillis();
            if (_log.shouldLog(Log.INFO))
                display(allocated, pending, "after looping to clear " + (beforeLooping - pending.size()));
            long afterDisplayed = System.currentTimeMillis();
            if (allocated > 0) {
                // after going through the entire pending list, we still don't
                // have enough data to send a full message

                if ( (pending.size() > FORCE_BATCH_FLUSH) || ( (_pendingSince > 0) && (getDelayAmount() <= 0) ) ) {
                    // not even a full message, but we want to flush it anyway
                    
                    if (pending.size() > 1)
                        _context.statManager().addRateData("tunnel.batchMultipleCount", pending.size(), 0);
                    _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), 0);

                    send(pending, 0, pending.size()-1, sender, rec);
                    
                    int beforeSize = pending.size();
                    for (int i = 0; i < pending.size(); i++) {
                        TunnelGateway.Pending cur = (TunnelGateway.Pending)pending.get(i);
                        if (cur.getOffset() >= cur.getData().length) {
                            pending.remove(i);
                            notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), cur.getData().length, cur.getMessageIds(), "flushed remaining");
                            _context.statManager().addRateData("tunnel.writeDelay", cur.getLifetime(), cur.getData().length);
                            i--;
                        }
                    }
                    if (pending.size() > 0) {
                        _pendingSince = _context.clock().now();
                        _context.statManager().addRateData("tunnel.batchFlushRemaining", pending.size(), beforeSize);
                        display(allocated, pending, "flushed, some remain");
                        
                        if (timingBuf != null) {
                            timingBuf.append(" flushed, some remain (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                            _log.debug(timingBuf.toString());
                        }
                        return true;
                    } else {
                        long delayAmount = _context.clock().now() - _pendingSince;
                        _pendingSince = 0;
                        if (batchCount > 1)
                            _context.statManager().addRateData("tunnel.batchCount", batchCount, 0);
                        if (_log.shouldLog(Log.INFO))
                            display(allocated, pending, "flushed " + (beforeSize) + ", no remaining after " + delayAmount);
                        
                        if (timingBuf != null) {
                            timingBuf.append(" flushed, none remain (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                            _log.debug(timingBuf.toString());
                        }
                        return false;
                    }
                } else {
                    _context.statManager().addRateData("tunnel.batchDelay", pending.size(), 0);
                    if (_pendingSince <= 0)
                        _pendingSince = _context.clock().now();
                    if (batchCount > 1)
                        _context.statManager().addRateData("tunnel.batchCount", batchCount, 0);
                    // not yet time to send the delayed flush
                    display(allocated, pending, "dont flush");
                    
                    if (timingBuf != null) {
                        timingBuf.append(" dont flush (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                        timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                        _log.debug(timingBuf.toString());
                    }
                    return true;
                }
            } else {
                // ok, we sent some, but haven't gone back for another 
                // pass yet.  keep looping
                
                if (timingBuf != null)
                    timingBuf.append(" Keep looping");
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sent everything on the list (pending=" + pending.size() + ")");

        if (timingBuf != null)
            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
        
        if (timingBuf != null)
            _log.debug(timingBuf.toString());
        // sent everything from the pending list, no need to delayed flush
        return false;
    }
    
    private void display(long allocated, List pending, String title) {
        if (_log.shouldLog(Log.INFO)) {
            long highestDelay = 0;
            StringBuilder buf = new StringBuilder();
            buf.append(_name).append(": ");
            buf.append(title);
            buf.append(" allocated: ").append(allocated);
            buf.append(" pending: ").append(pending.size());
            if (_pendingSince > 0)
                buf.append(" delay: ").append(getDelayAmount(false));
            for (int i = 0; i < pending.size(); i++) {
                TunnelGateway.Pending curPending = (TunnelGateway.Pending)pending.get(i);
                buf.append(" pending[").append(i).append("]: ");
                buf.append(curPending.getOffset()).append("/").append(curPending.getData().length).append('/');
                buf.append(curPending.getLifetime());
                if (curPending.getLifetime() > highestDelay)
                    highestDelay = curPending.getLifetime();
            }
            _log.info(buf.toString());
        }
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
            _log.debug("Sending " + startAt + ":" + sendThrough + " out of " + pending);
        byte preprocessed[] = _dataCache.acquire().getData();
        
        int offset = 0;
        offset = writeFragments(pending, startAt, sendThrough, preprocessed, offset);
        // preprocessed[0:offset] now contains the fragments from the pending,
        // so we need to format, pad, and rearrange according to the spec to
        // generate the final preprocessed data
        
        if (offset <= 0) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("wtf, written offset is ").append(offset);
            buf.append(" for ").append(startAt).append(" through ").append(sendThrough);
            for (int i = startAt; i <= sendThrough; i++) {
                buf.append(" ").append(pending.get(i).toString());
            }
            _log.log(Log.CRIT, buf.toString());
            return;
        }
        
        try {
            preprocess(preprocessed, offset);
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error preprocessing the messages (offset=" + offset + " start=" + startAt + " through=" + sendThrough + " pending=" + pending.size() + " preproc=" + preprocessed.length);
            return;
        }
        
        long msgId = sender.sendPreprocessed(preprocessed, rec);
        for (int i = 0; i < pending.size(); i++) {
            TunnelGateway.Pending cur = (TunnelGateway.Pending)pending.get(i);
            cur.addMessageId(msgId);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sent " + startAt + ":" + sendThrough + " out of " + pending + " in message " + msgId);
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
