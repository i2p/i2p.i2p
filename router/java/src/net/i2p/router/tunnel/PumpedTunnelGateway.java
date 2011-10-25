package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * This is used for all gateways with more than zero hops.
 *
 * Serve as the gatekeeper for a tunnel, accepting messages, coallescing and/or
 * fragmenting them before wrapping them up for tunnel delivery. The flow here
 * is: <ol>
 * <li>add an I2NPMessage (and a target tunnel/router, if necessary)</li>
 * <li>that message is queued up into a TunnelGateway.Pending and offered to the
 *     assigned QueuePreprocessor.</li>
 * <li>that QueuePreprocessor may then take off any of the TunnelGateway.Pending
 *     messages or instruct the TunnelGateway to offer it the messages again in
 *     a short while (in an attempt to coallesce them).
 * <li>when the QueueProcessor accepts a TunnelGateway.Pending, it preprocesses
 *     it into fragments, forwarding each preprocessed fragment group through 
 *     the Sender.</li>
 * <li>the Sender then encrypts the preprocessed data and delivers it to the 
 *     Receiver.</li>
 * <li>the Receiver now has the encrypted message and may do with it as it 
 *     pleases (e.g. wrap it as necessary and enqueue it onto the OutNetMessagePool,
 *     or if debugging, verify that it can be decrypted properly)</li>
 * </ol>
 *
 */
class PumpedTunnelGateway extends TunnelGateway {
    private final BlockingQueue<Pending> _prequeue;
    private final TunnelGatewayPumper _pumper;
    
    /**
     * @param preprocessor this pulls Pending messages off a list, builds some
     *                     full preprocessed messages, and pumps those into the sender
     * @param sender this takes a preprocessed message, encrypts it, and sends it to 
     *               the receiver
     * @param receiver this receives the encrypted message and forwards it off 
     *                 to the first hop
     */
    public PumpedTunnelGateway(RouterContext context, QueuePreprocessor preprocessor, Sender sender, Receiver receiver, TunnelGatewayPumper pumper) {
        super(context, preprocessor, sender, receiver);
        _prequeue = new LinkedBlockingQueue();
        _pumper = pumper;
    }
    
    /**
     * Add a message to be sent down the tunnel, either sending it now (perhaps
     * coallesced with other pending messages) or after a brief pause (_flushFrequency).
     * If it is queued up past its expiration, it is silently dropped
     *
     * @param msg message to be sent through the tunnel
     * @param toRouter router to send to after the endpoint (or null for endpoint processing)
     * @param toTunnel tunnel to send to after the endpoint (or null for endpoint or router processing)
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        _messagesSent++;
        Pending cur = new PendingImpl(msg, toRouter, toTunnel);
        _prequeue.offer(cur);
        _pumper.wantsPumping(this);
    }

    /**
     * run in one of the TunnelGatewayPumper's threads, this pulls pending messages
     * off the prequeue, adds them to the queue and then tries to preprocess the queue,
     * scheduling a later delayed flush as necessary.  this allows the gw.add call to
     * go quickly, rather than blocking its callers on potentially substantial
     * processing.
     *
     * @param queueBuf Empty list for convenience, to use as a temporary buffer.
     *                 Must be empty when called; will always be emptied before return.
     */
    void pump(List<Pending> queueBuf) {
        _prequeue.drainTo(queueBuf);
        if (queueBuf.isEmpty())
            return;

        long startAdd = System.currentTimeMillis();
        long beforeLock = startAdd;
        long afterAdded = -1;
        boolean delayedFlush = false;
        long delayAmount = -1;
        int remaining = 0;
        long afterPreprocess = 0;
        long afterExpire = 0;
        synchronized (_queue) {
            _queue.addAll(queueBuf);
            afterAdded = System.currentTimeMillis();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added before direct flush preprocessing for " + toString() + ": " + _queue);
            delayedFlush = _preprocessor.preprocessQueue(_queue, _sender, _receiver);
            afterPreprocess = System.currentTimeMillis();
            if (delayedFlush)
                delayAmount = _preprocessor.getDelayAmount();
            _lastFlush = _context.clock().now();
            
            // expire any as necessary, even if its framented
            for (int i = 0; i < _queue.size(); i++) {
                Pending m = _queue.get(i);
                if (m.getExpiration() + Router.CLOCK_FUDGE_FACTOR < _lastFlush) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Expire on the queue (size=" + _queue.size() + "): " + m);
                    _queue.remove(i);
                    i--;
                }
            }
            afterExpire = System.currentTimeMillis();
            remaining = _queue.size();
            if ( (remaining > 0) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Remaining after preprocessing: " + _queue);
        }
        
        if (delayedFlush) {
            FlushTimer.getInstance().addEvent(_delayedFlush, delayAmount);
        }
        _context.statManager().addRateData("tunnel.lockedGatewayAdd", afterAdded-beforeLock, remaining);
        long complete = System.currentTimeMillis();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Time to add " + queueBuf.size() + " messages to " + toString() + ": " + (complete-startAdd)
                       + " delayed? " + delayedFlush + " remaining: " + remaining
                       + " add: " + (afterAdded-beforeLock)
                       + " preprocess: " + (afterPreprocess-afterAdded)
                       + " expire: " + (afterExpire-afterPreprocess)
                       + " queue flush: " + (complete-afterExpire));
        queueBuf.clear();
    }
    
}
