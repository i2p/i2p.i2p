package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
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
public class TunnelGateway {
    protected RouterContext _context;
    protected Log _log;
    protected final List<Pending> _queue;
    protected QueuePreprocessor _preprocessor;
    protected Sender _sender;
    protected Receiver _receiver;
    protected long _lastFlush;
    protected int _flushFrequency;
    protected DelayedFlush _delayedFlush;// FIXME Exporting non-public type through public API FIXME
    protected int _messagesSent;
    
    /**
     * @param preprocessor this pulls Pending messages off a list, builds some
     *                     full preprocessed messages, and pumps those into the sender
     * @param sender this takes a preprocessed message, encrypts it, and sends it to 
     *               the receiver
     * @param receiver this receives the encrypted message and forwards it off 
     *                 to the first hop
     */
    public TunnelGateway(RouterContext context, QueuePreprocessor preprocessor, Sender sender, Receiver receiver) {
        _context = context;
        _log = context.logManager().getLog(getClass());
        _queue = new ArrayList(4);
        _preprocessor = preprocessor;
        _sender = sender;
        _receiver = receiver;
        _flushFrequency = 500;
        _delayedFlush = new DelayedFlush();
        _lastFlush = _context.clock().now();
        _context.statManager().createRateStat("tunnel.lockedGatewayAdd", "How long do we block when adding a message to a tunnel gateway's queue", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.lockedGatewayCheck", "How long do we block when flushing a tunnel gateway's queue", "Tunnels", new long[] { 60*1000, 10*60*1000 });
    }
    
    /**
     * Add a message to be sent down the tunnel, where we are the inbound gateway.
     *
     * @param msg message received to be sent through the tunnel
     */
    public void add(TunnelGatewayMessage msg) {
        add(msg.getMessage(), null, null);
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
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        _messagesSent++;
        long startAdd = System.currentTimeMillis();
        boolean delayedFlush = false;
        long delayAmount = -1;
        int remaining = 0;
        Pending cur = new PendingImpl(msg, toRouter, toTunnel);
        long beforeLock = System.currentTimeMillis();
        long afterAdded = -1;
        long afterPreprocess = 0;
        long afterExpire = 0;
        synchronized (_queue) {
            _queue.add(cur);
            afterAdded = System.currentTimeMillis();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added before direct flush preprocessing: " + _queue);
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
        if (_log.shouldLog(Log.DEBUG)) {
            long complete = System.currentTimeMillis();
            _log.debug("Time to add the message " + msg.getUniqueId() + ": " + (complete-startAdd)
                       + " delayed? " + delayedFlush + " remaining: " + remaining
                       + " prepare: " + (beforeLock-startAdd)
                       + " add: " + (afterAdded-beforeLock)
                       + " preprocess: " + (afterPreprocess-afterAdded)
                       + " expire: " + (afterExpire-afterPreprocess)
                       + " queue flush: " + (complete-afterExpire));
        }
    }
    
    public int getMessagesSent() { return _messagesSent; }
    
    public interface Sender {
        /**
         * Take the preprocessed data containing zero or more fragments, encrypt
         * it, and pass it on to the receiver
         *
         * @param preprocessed IV + (rand padding) + 0x0 + Hash[0:3] + {instruction+fragment}*
         * @return message ID it was sent in, or -1 if it was deferred
         */
        public long sendPreprocessed(byte preprocessed[], Receiver receiver);
    }
        
    public interface QueuePreprocessor {
        /** 
         * Caller must synchronize on the list!
         *
         * @param pending list of Pending objects for messages either unsent
         *                or partly sent.  This list should be update with any
         *                values removed (the preprocessor owns the lock)
         *                Messages are not removed from the list until actually sent.
         *                The status of unsent and partially-sent messages is stored in
         *                the Pending structure.
         *
         * @return true if we should delay before preprocessing again 
         */
        public boolean preprocessQueue(List<Pending> pending, Sender sender, Receiver receiver);
        
        /** how long do we want to wait before flushing */
        public long getDelayAmount();
    }
    
    public interface Receiver {
        /**
         * Take the encrypted data and send it off to the next hop
         * @return message ID it was sent in, or -1 if it had to be deferred
         */
        public long receiveEncrypted(byte encrypted[]);
    }
    
    /**
     *  Stores all the state for an unsent or partially-sent message
     */
    public static class Pending {
        protected Hash _toRouter;
        protected TunnelId _toTunnel;
        protected long _messageId;
        protected long _expiration;
        protected byte _remaining[];
        protected int _offset;
        protected int _fragmentNumber;
        protected long _created;
        private List<Long> _messageIds;
        
        public Pending(I2NPMessage message, Hash toRouter, TunnelId toTunnel) { 
            this(message, toRouter, toTunnel, System.currentTimeMillis()); 
        }
        public Pending(I2NPMessage message, Hash toRouter, TunnelId toTunnel, long now) {
            _toRouter = toRouter;
            _toTunnel = toTunnel;
            _messageId = message.getUniqueId();
            _expiration = message.getMessageExpiration();
            _remaining = message.toByteArray();
            _created = now;
        }
        /** may be null */
        public Hash getToRouter() { return _toRouter; }
        /** may be null */
        public TunnelId getToTunnel() { return _toTunnel; }
        public long getMessageId() { return _messageId; }
        public long getExpiration() { return _expiration; }
        /** raw unfragmented message to send */
        public byte[] getData() { return _remaining; }
        /** index into the data to be sent */
        public int getOffset() { return _offset; }
        /** move the offset */
        public void setOffset(int offset) { _offset = offset; }
        public long getLifetime() { return System.currentTimeMillis()-_created; }
        /** which fragment are we working on (0 for the first fragment) */
        public int getFragmentNumber() { return _fragmentNumber; }
        /** ok, fragment sent, increment what the next will be */
        public void incrementFragmentNumber() { _fragmentNumber++; }
        /**
         *  Add an ID to the list of the TunnelDataMssages this message was fragmented into.
         *  Unused except in notePreprocessing() calls for debugging
         */
        public void addMessageId(long id) { 
            synchronized (Pending.this) {
                if (_messageIds == null)
                    _messageIds = new ArrayList();
                _messageIds.add(Long.valueOf(id));
            }
        }
        /**
         *  The IDs of the TunnelDataMssages this message was fragmented into.
         *  Unused except in notePreprocessing() calls for debugging
         */
        public List<Long> getMessageIds() { 
            synchronized (Pending.this) { 
                if (_messageIds != null)
                    return new ArrayList(_messageIds); 
                else
                    return new ArrayList();
            } 
        }
    }

    /** Extend for debugging */
    class PendingImpl extends Pending {
        public PendingImpl(I2NPMessage message, Hash toRouter, TunnelId toTunnel) {
            super(message, toRouter, toTunnel, _context.clock().now());
        }        
        
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Message ").append(_messageId).append(" on ");
            buf.append(TunnelGateway.this.toString());
            if (_toRouter != null) {
                buf.append(" targetting ");
                buf.append(_toRouter.toBase64()).append(" ");
                if (_toTunnel != null)
                    buf.append(_toTunnel.getTunnelId());
            }
            long now = _context.clock().now();
            buf.append(" actual lifetime ");
            buf.append(now - _created).append("ms");
            buf.append(" potential lifetime ");
            buf.append(_expiration - _created).append("ms");
            buf.append(" size ").append(_remaining.length);
            buf.append(" offset ").append(_offset);
            buf.append(" frag ").append(_fragmentNumber);
            return buf.toString();
        }

        @Override
        public long getLifetime() { return _context.clock().now()-_created; }
    }
    
    private class DelayedFlush implements SimpleTimer.TimedEvent {
        public void timeReached() {
            boolean wantRequeue = false;
            int remaining = 0;
            long beforeLock = _context.clock().now();
            long afterChecked = -1;
            long delayAmount = -1;
            //if (_queue.size() > 10000) // stay out of the synchronized block
            //    System.out.println("foo!");
            synchronized (_queue) {
                //if (_queue.size() > 10000) // stay in the synchronized block
                //    System.out.println("foo!");
                afterChecked = _context.clock().now();
                if (!_queue.isEmpty()) {
                    if ( (remaining > 0) && (_log.shouldLog(Log.DEBUG)) )
                        _log.debug("Remaining before delayed flush preprocessing: " + _queue);
                    wantRequeue = _preprocessor.preprocessQueue(_queue, _sender, _receiver);
                    if (wantRequeue)
                        delayAmount = _preprocessor.getDelayAmount();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Remaining after delayed flush preprocessing (requeue? " + wantRequeue + "): " + _queue);
                }
                remaining = _queue.size();
            }
            
            if (wantRequeue)
                FlushTimer.getInstance().addEvent(_delayedFlush, delayAmount);
            else
                _lastFlush = _context.clock().now();
            
            _context.statManager().addRateData("tunnel.lockedGatewayCheck", afterChecked-beforeLock, remaining);
        }
    }
}
