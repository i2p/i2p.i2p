package net.i2p.router.tunnel;

import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Batching preprocessor that will briefly delay the sending of a message 
 * if it doesn't fill up a full tunnel message, in which case it queues up
 * an additional flush task.  This is a very simple threshold algorithm - 
 * as soon as there is enough data for a full tunnel message, it is sent.  If
 * after the delay there still isn't enough data, what is available is sent
 * and padded.
 *
 * As explained in the tunnel document, the preprocessor has a lot of
 * potential flexibility in delay, padding, or even reordering.
 * We keep things relatively simple for now.
 *
 * However much of the efficiency results from the clients selecting
 * the correct MTU in the streaming lib such that the maximum-size
 * streaming lib message fits in an integral number of tunnel messages.
 * See ConnectionOptions in the streaming lib for details.
 *
 * Aside from obvious goals of minimizing delay and padding, we also
 * want to minimize the number of tunnel messages a message occupies,
 * to minimize the impact of a router dropping a tunnel message.
 * So there is some benefit in starting a message in a new tunnel message,
 * especially if it will fit perfectly if we do that (a 964 or 1956 byte
 * message, for example).
 *
 * An idea for the future...
 *
 * If we are in the middle of a tunnel msg and starting a new i2np msg,
 * and this one won't fit,
 * let's look to see if we have somthing that would fit instead
 * by reordering:
 *   if (allocated > 0 && msg.getFragment == 0) {
 *       for (j = i+1, j < pending.size(); j++) {
 *           if it will fit and it is fragment 0 {
 *               msg = pending.remove(j)
 *               pending.add(0, msg)
 *           }
 *       }
 *   }
 */
public class BatchedPreprocessor extends TrivialPreprocessor {
    private long _pendingSince;
    private final String _name;
    
    public BatchedPreprocessor(RouterContext ctx, String name) {
        super(ctx);
        _name = name;
        // all createRateStat() moved to TunnelDispatcher
    }
    
    /** 1003 */
    private static final int FULL_SIZE = PREPROCESSED_SIZE 
                                         - IV_SIZE 
                                         - 1  // 0x00 ending the padding
                                         - 4; // 4 byte checksum

    //private static final boolean DISABLE_BATCHING = false;
    
    /* not final or private so the test code can adjust */
    static long DEFAULT_DELAY = 100;
    /**
     *  Wait up to this long before sending (flushing) a small tunnel message
     *  Warning - overridden in BatchedRouterPreprocessor
     */
    protected long getSendDelay() { return DEFAULT_DELAY; }
    
    /**
     *  if we have this many messages queued that are too small, flush them anyway
     *  Even small messages take up about 200 bytes or so.
     */
    private static final int FORCE_BATCH_FLUSH = 5;
    
    /** If we have this much allocated, flush anyway.
     *  Tune this to trade off padding vs. fragmentation.
     *  The lower the value, the more we are willing to send off
     *  a tunnel msg that isn't full so the next message can start
     *  in a new tunnel msg to minimize fragmentation.
     *
     *  This should be at most FULL_SIZE - (39 + a few), since
     *  you want to at least fit in the instructions and a few bytes.
     */
    private static final int FULL_ENOUGH_SIZE = (FULL_SIZE * 80) / 100;
    
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
    
    /* See TunnelGateway.QueuePreprocessor for Javadoc */ 
    @Override
    public boolean preprocessQueue(List<TunnelGateway.Pending> pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        if (_log.shouldLog(Log.INFO))
            display(0, pending, "Starting");
        StringBuilder timingBuf = null;
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Preprocess queue with " + pending.size() + " to send");
            timingBuf = new StringBuilder(128);
            timingBuf.append("Preprocess with " + pending.size() + " to send. ");
        }
        //if (DISABLE_BATCHING) {
        //    if (_log.shouldLog(Log.INFO))
        //        _log.info("Disabled batching, pushing " + pending + " immediately");
        //    return super.preprocessQueue(pending, sender, rec);
        //}
        long start = System.currentTimeMillis();
        
        int batchCount = 0;
        int beforeLooping = pending.size();
        
        // loop until the queue is empty
        while (!pending.isEmpty()) {
            int allocated = 0;
            long beforePendingLoop = System.currentTimeMillis();

            // loop until we fill up a single message
            for (int i = 0; i < pending.size(); i++) {
                long pendingStart = System.currentTimeMillis();
                TunnelGateway.Pending msg = pending.get(i);
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
                        msg = pending.get(i);
                        allocated -= curWanted;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Pushback of " + curWanted + " (message " + (i+1) + " in " + pending + ")");
                    }
                    if (_pendingSince > 0) {
                        long waited = _context.clock().now() - _pendingSince;
                        _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), waited);
                    }

                    // Send the message
                    long beforeSend = System.currentTimeMillis();
                    _pendingSince = 0;
                    send(pending, 0, i, sender, rec);
                    _context.statManager().addRateData("tunnel.batchFullFragments", 1, 0);
                    long afterSend = System.currentTimeMillis();
                    if (_log.shouldLog(Log.INFO))
                        display(allocated, pending, "Sent the message with " + (i+1));
                        //_log.info(_name + ": Allocated=" + allocated + "B, Sent " + (i+1) 
                        //          + " msgs (last complete? " + (msg.getOffset() >= msg.getData().length) 
                        //          + ", off=" + msg.getOffset() + ", pending=" + pending.size() + ")");

                    // Remove what we sent from the pending queue
                    for (int j = 0; j < i; j++) {
                        TunnelGateway.Pending cur = pending.remove(0);
                        if (cur.getOffset() < cur.getData().length)
                            throw new IllegalArgumentException("i=" + i + " j=" + j + " off=" + cur.getOffset() 
                                                               + " len=" + cur.getData().length + " alloc=" + allocated);
                        if (timingBuf != null)
                            timingBuf.append(" sent " + cur);
                        notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), cur.getData().length, cur.getMessageIds(), "flushed allocated");
                        _context.statManager().addRateData("tunnel.batchFragmentation", cur.getFragmentNumber() + 1, 0);
                        _context.statManager().addRateData("tunnel.writeDelay", cur.getLifetime(), cur.getData().length);
                    }
                    if (msg.getOffset() >= msg.getData().length) {
                        // ok, this last message fit perfectly, remove it too
                        TunnelGateway.Pending cur = pending.remove(0);
                        if (timingBuf != null)
                            timingBuf.append(" sent perfect fit " + cur).append(".");
                        notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), msg.getData().length, msg.getMessageIds(), "flushed tail, remaining: " + pending);
                        _context.statManager().addRateData("tunnel.batchFragmentation", cur.getFragmentNumber() + 1, 0);
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
                }  // if >= full size
                if (timingBuf != null)
                    timingBuf.append(" After pending loop " + (System.currentTimeMillis()-beforePendingLoop)).append(".");
            }  // for
            
            if (_log.shouldLog(Log.INFO))
                display(allocated, pending, "after looping to clear " + (beforeLooping - pending.size()));
            long afterDisplayed = System.currentTimeMillis();
            if (allocated > 0) {
                // After going through the entire pending list, we have only a partial message.
                // We might flush it or might not, but we are returning either way.

                if ( (pending.size() > FORCE_BATCH_FLUSH) ||                    // enough msgs - or
                     ( (_pendingSince > 0) && (getDelayAmount() <= 0) ) ||      // time to flush - or
                     (allocated >= FULL_ENOUGH_SIZE)) {                         // full enough
                     //(pending.get(0).getFragmentNumber() > 0)) {                // don't delay anybody's last fragment,
                     //                                                           // which would be the first fragment in the message

                    // not even a full message, but we want to flush it anyway
                    
                    if (pending.size() > 1)
                        _context.statManager().addRateData("tunnel.batchMultipleCount", pending.size(), 0);
                    _context.statManager().addRateData("tunnel.batchDelaySent", pending.size(), 0);

                    send(pending, 0, pending.size()-1, sender, rec);
                    _context.statManager().addRateData("tunnel.batchSmallFragments", FULL_SIZE - allocated, 0);
                    
                    // Remove everything in the outgoing message from the pending queue
                    int beforeSize = pending.size();
                    for (int i = 0; i < beforeSize; i++) {
                        TunnelGateway.Pending cur = pending.get(0);
                        if (cur.getOffset() < cur.getData().length)
                            break;
                        pending.remove(0);
                        notePreprocessing(cur.getMessageId(), cur.getFragmentNumber(), cur.getData().length, cur.getMessageIds(), "flushed remaining");
                        _context.statManager().addRateData("tunnel.batchFragmentation", cur.getFragmentNumber() + 1, 0);
                        _context.statManager().addRateData("tunnel.writeDelay", cur.getLifetime(), cur.getData().length);
                    }

                    if (!pending.isEmpty()) {
                        // rare
                        _pendingSince = _context.clock().now();
                        _context.statManager().addRateData("tunnel.batchFlushRemaining", pending.size(), beforeSize);
                        if (_log.shouldLog(Log.INFO))
                            display(allocated, pending, "flushed, some remain");
                        
                        if (timingBuf != null) {
                            timingBuf.append(" flushed, some remain (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                            _log.debug(timingBuf.toString());
                        }
                        return true;
                    } else {
                        long delayAmount = 0;
                        if (_pendingSince > 0) {
                            delayAmount = _context.clock().now() - _pendingSince;
                            _pendingSince = 0;
                        }
                        if (batchCount > 1)
                            _context.statManager().addRateData("tunnel.batchCount", batchCount, 0);
                        if (_log.shouldLog(Log.INFO))
                            display(allocated, pending, "flushed " + (beforeSize) + ", no remaining after " + delayAmount + "ms");
                        
                        if (timingBuf != null) {
                            timingBuf.append(" flushed, none remain (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                            _log.debug(timingBuf.toString());
                        }
                        return false;
                    }
                    // won't get here, we returned
                } else {
                     // We didn't flush. Note that the messages remain on the pending list.
                    _context.statManager().addRateData("tunnel.batchDelay", pending.size(), 0);
                    if (_pendingSince <= 0)
                        _pendingSince = _context.clock().now();
                    if (batchCount > 1)
                        _context.statManager().addRateData("tunnel.batchCount", batchCount, 0);
                    // not yet time to send the delayed flush
                    if (_log.shouldLog(Log.INFO))
                        display(allocated, pending, "dont flush");
                    
                    if (timingBuf != null) {
                        timingBuf.append(" dont flush (displayed to now: " + (System.currentTimeMillis()-afterDisplayed) + ")");
                        timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
                        _log.debug(timingBuf.toString());
                    }
                    return true;
                }
                // won't get here, we returned
            } else {
                // ok, we sent some, but haven't gone back for another 
                // pass yet.  keep looping
                
                if (timingBuf != null)
                    timingBuf.append(" Keep looping");
            }  // if allocated
        }  // while
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sent everything on the list (pending=" + pending.size() + ")");

        if (timingBuf != null)
            timingBuf.append(" total time: " + (System.currentTimeMillis()-start));
        
        if (timingBuf != null)
            _log.debug(timingBuf.toString());
        // sent everything from the pending list, no need to delayed flush
        return false;
    }
    
    /*
     * Only if Log.INFO
     *
     * title: allocated: X pending: X (delay: X) [0]:offset/length/lifetime [1]:etc.
     */
    private void display(long allocated, List<TunnelGateway.Pending> pending, String title) {
        if (_log.shouldLog(Log.INFO)) {
            long highestDelay = 0;
            StringBuilder buf = new StringBuilder(128);
            buf.append(_name).append(": ");
            buf.append(title);
            buf.append(" - allocated: ").append(allocated);
            buf.append(" pending: ").append(pending.size());
            if (_pendingSince > 0)
                buf.append(" delay: ").append(getDelayAmount(false));
            for (int i = 0; i < pending.size(); i++) {
                TunnelGateway.Pending curPending = pending.get(i);
                buf.append(" [").append(i).append("]:");
                buf.append(curPending.getOffset()).append('/').append(curPending.getData().length).append('/');
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
    protected void send(List<TunnelGateway.Pending> pending, int startAt, int sendThrough, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending " + startAt + ":" + sendThrough + " out of " + pending);

        // Might as well take a buf from the cache;
        // However it will never be returned to the cache.
        // (TunnelDataMessage will not wrap the buffer in a new ByteArray and release() it)
        // See also TDM for more discussion.
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
            TunnelGateway.Pending cur = pending.get(i);
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
    private int writeFragments(List<TunnelGateway.Pending> pending, int startAt, int sendThrough, byte target[], int offset) {
        for (int i = startAt; i <= sendThrough; i++) {
            TunnelGateway.Pending msg = pending.get(i);
            int prevOffset = offset;
            if (msg.getOffset() == 0) {
                offset = writeFirstFragment(msg, target, offset);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("writing " + msg.getMessageId() + " fragment 0, ending at " + offset + " prev " + prevOffset
                               + " leaving " + (msg.getData().length - msg.getOffset()) + " bytes for later");
            } else {
                offset = writeSubsequentFragment(msg, target, offset);
                if (_log.shouldLog(Log.DEBUG)) {
                    int frag = msg.getFragmentNumber();
                    int later = msg.getData().length - msg.getOffset();
                    if (later > 0)
                        frag--;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("writing " + msg.getMessageId() + " fragment " + frag
                               + ", ending at " + offset + " prev " + prevOffset
                               + " leaving " + later + " bytes for later");
                }
            }
        }
        return offset;
    }
}
