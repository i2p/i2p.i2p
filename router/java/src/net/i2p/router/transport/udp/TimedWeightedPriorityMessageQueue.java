package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Weighted priority queue implementation for the outbound messages, coupled
 * with code to fail messages that expire.  
 *
 */
public class TimedWeightedPriorityMessageQueue implements MessageQueue {
    private RouterContext _context;
    private Log _log;
    /** FIFO queue of messages in a particular priority */
    private List _queue[];
    /** all messages in the indexed queue are at or below the given priority. */
    private int _priorityLimits[];
    /** weighting for each queue */
    private int _weighting[];
    /** how many bytes are enqueued */
    private long _bytesQueued[];
    /** how many messages have been pushed out in this pass */
    private int _messagesFlushed[];
    /** how many bytes total have been pulled off the given queue */
    private long _bytesTransferred[];
    /** lock to notify message enqueue/removal (and block for getNext()) */
    private Object _nextLock;
    /** have we shut down or are we still alive? */
    private boolean _alive;
    /** which queue should we pull out of next */
    private int _nextQueue;
    /** true if a message is enqueued while the getNext() call is in progress */
    private volatile boolean _addedSincePassBegan;
    private Expirer _expirer;
    private FailedListener _listener;
    
    /**
     * Build up a new queue
     *
     * @param priorityLimits ordered breakpoint for the different message 
     *                       priorities, with the lowest limit first.
     * @param weighting how much to prefer a given priority grouping.  
     *                  specifically, this means how many messages in this queue
     *                  should be pulled off in a row before moving on to the next.
     */
    public TimedWeightedPriorityMessageQueue(RouterContext ctx, int[] priorityLimits, int[] weighting, FailedListener lsnr) {
        _context = ctx;
        _log = ctx.logManager().getLog(TimedWeightedPriorityMessageQueue.class);
        _queue = new List[weighting.length];
        _priorityLimits = new int[weighting.length];
        _weighting = new int[weighting.length];
        _bytesQueued = new long[weighting.length];
        _bytesTransferred = new long[weighting.length];
        _messagesFlushed = new int[weighting.length];
        for (int i = 0; i < weighting.length; i++) {
            _queue[i] = new ArrayList(8);
            _weighting[i] = weighting[i];
            _priorityLimits[i] = priorityLimits[i];
            _messagesFlushed[i] = 0;
            _bytesQueued[i] = 0;
            _bytesTransferred[i] = 0;
        }
        _alive = true;
        _nextLock = this;
        _nextQueue = 0;
        _listener = lsnr;
        _context.statManager().createRateStat("udp.timeToEntrance", "Message lifetime until it reaches the UDP system", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.messageQueueSize", "How many messages are on the current class queue at removal", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _expirer = new Expirer();
        I2PThread t = new I2PThread(_expirer, "UDP outbound expirer");
        t.setDaemon(true);
        t.start();
    }
    
    public void add(OutNetMessage message) {
        if (message == null) return;
        
        _context.statManager().addRateData("udp.timeToEntrance", message.getLifetime(), message.getLifetime());
                    
        int queue = pickQueue(message);
        long size = message.getMessageSize();
        synchronized (_queue[queue]) {
            _queue[queue].add(message);
            _bytesQueued[queue] += size;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Added a " + size + " byte message to queue " + queue);
        
        synchronized (_nextLock) {
            _addedSincePassBegan = true;
            _nextLock.notifyAll();
        }
    }
    
    /**
     * Grab the next message out of the next queue.  This only advances
     * the _nextQueue var after pushing _weighting[currentQueue] messages
     * or the queue is empty.  This call blocks until either a message 
     * becomes available or the queue is shut down.
     *
     * @param blockUntil expiration, or -1 if indefinite
     * @return message dequeued, or null if the queue was shut down
     */
    public OutNetMessage getNext(long blockUntil) {
        while (_alive) {
            _addedSincePassBegan = false;
            for (int i = 0; i < _queue.length; i++) {
                int currentQueue = (_nextQueue + i) % _queue.length;
                synchronized (_queue[currentQueue]) {
                    if (_queue[currentQueue].size() > 0) {
                        OutNetMessage msg = (OutNetMessage)_queue[currentQueue].remove(0);
                        long size = msg.getMessageSize();
                        _bytesQueued[currentQueue] -= size;
                        _bytesTransferred[currentQueue] += size;
                        _messagesFlushed[currentQueue]++;
                        if (_messagesFlushed[currentQueue] >= _weighting[currentQueue]) {
                            _messagesFlushed[currentQueue] = 0;
                            _nextQueue = (currentQueue + 1) % _queue.length;
                        }
                        _context.statManager().addRateData("udp.messageQueueSize", _queue[currentQueue].size(), currentQueue);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Pulling a message off queue " + currentQueue + " with " 
                                       + _queue[currentQueue].size() + " remaining");
                        return msg;
                    } else {
                        // nothing waiting
                        _messagesFlushed[currentQueue] = 0;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Nothing on queue " + currentQueue);
                    }
                }
            }
            
            long remaining = blockUntil - _context.clock().now();
            if ( (blockUntil > 0) && (remaining < 0) ) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Nonblocking, or block time has expired");
                return null;
            }
            
            try {
                synchronized (_nextLock) {
                    if (!_addedSincePassBegan && _alive) {
                        // nothing added since we begun iterating through, 
                        // so we can safely wait for the full period.  otoh, 
                        // even if this is true, we might be able to safely 
                        // wait, but it doesn't hurt to loop again.
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Wait for activity (up to " + remaining + "ms)");
                        if (blockUntil < 0)
                            _nextLock.wait();
                        else
                            _nextLock.wait(remaining);
                    }
                }
            } catch (InterruptedException ie) {}
        }
        
        return null;
    }
    
    public void shutdown() {
        _alive = false;
        synchronized (_nextLock) {
            _nextLock.notifyAll();
        }
    }
    
    private int pickQueue(OutNetMessage message) {
        int target = message.getPriority();
        for (int i = 0; i < _priorityLimits.length; i++) {
            if (_priorityLimits[i] <= target) {
                if (i == 0)
                    return 0;
                else
                    return i - 1;
            }
        }
        return _priorityLimits.length-1;
    }
    
    public interface FailedListener {
        public void failed(OutNetMessage msg);
    }
    
    /**
     * Drop expired messages off the queues
     */
    private class Expirer implements Runnable {
        public void run() {
            List removed = new ArrayList(1);
            while (_alive) {
                long now = _context.clock().now();
                for (int i = 0; i < _queue.length; i++) {
                    synchronized (_queue[i]) {
                        for (int j = 0; j < _queue[i].size(); j++) {
                            OutNetMessage m = (OutNetMessage)_queue[i].get(j);
                            if (m.getExpiration() < now) {
                                _bytesQueued[i] -= m.getMessageSize();
                                removed.add(m);
                                _queue[i].remove(j);
                                j--;
                                continue;
                            }
                        }
                    }
                }
                
                for (int i = 0; i < removed.size(); i++) {
                    OutNetMessage m = (OutNetMessage)removed.get(i);
                    _listener.failed(m);
                }
                removed.clear();
                
                try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            }
        }
    }
}
