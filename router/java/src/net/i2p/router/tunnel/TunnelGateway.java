package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
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
    private I2PAppContext _context;
    private Log _log;
    private List _queue;
    private QueuePreprocessor _preprocessor;
    private Sender _sender;
    private Receiver _receiver;
    private long _lastFlush;
    private int _flushFrequency;
    private DelayedFlush _delayedFlush;
    
    /**
     * @param preprocessor this pulls Pending messages off a list, builds some
     *                     full preprocessed messages, and pumps those into the sender
     * @param sender this takes a preprocessed message, encrypts it, and sends it to 
     *               the receiver
     * @param receiver this receives the encrypted message and forwards it off 
     *                 to the first hop
     */
    public TunnelGateway(I2PAppContext context, QueuePreprocessor preprocessor, Sender sender, Receiver receiver) {
        _context = context;
        _log = context.logManager().getLog(TunnelGateway.class);
        _queue = new ArrayList(4);
        _preprocessor = preprocessor;
        _sender = sender;
        _receiver = receiver;
        _flushFrequency = 500;
        _delayedFlush = new DelayedFlush();
        _lastFlush = _context.clock().now();
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
        boolean delayedFlush = false;
        
        Pending cur = new Pending(msg, toRouter, toTunnel);
        synchronized (_queue) {
            _queue.add(cur);
            delayedFlush = _preprocessor.preprocessQueue(_queue, _sender, _receiver);
            _lastFlush = _context.clock().now();
            
            // expire any as necessary, even if its framented
            for (int i = 0; i < _queue.size(); i++) {
                Pending m = (Pending)_queue.get(i);
                if (m.getExpiration() < _lastFlush) {
                    _queue.remove(i);
                    i--;
                }
            }
        }
        
        if (delayedFlush) {
            SimpleTimer.getInstance().addEvent(_delayedFlush, _flushFrequency);
        }
    }
    
    public interface Sender {
        /**
         * Take the preprocessed data containing zero or more fragments, encrypt
         * it, and pass it on to the receiver
         *
         * @param preprocessed IV + (rand padding) + 0x0 + Hash[0:3] + {instruction+fragment}*
         */
        public void sendPreprocessed(byte preprocessed[], Receiver receiver);
    }
        
    public interface QueuePreprocessor {
        /** 
         * @param pending list of Pending objects for messages either unsent
         *                or partly sent.  This list should be update with any
         *                values removed (the preprocessor owns the lock)
         * @return true if we should delay before preprocessing again 
         */
        public boolean preprocessQueue(List pending, Sender sender, Receiver receiver);
    }
    
    public interface Receiver {
        /**
         * Take the encrypted data and send it off to the next hop
         */
        public void receiveEncrypted(byte encrypted[]);
    }
    
    public static class Pending {
        private Hash _toRouter;
        private TunnelId _toTunnel;
        private long _messageId;
        private long _expiration;
        private byte _remaining[];
        private int _offset;
        private int _fragmentNumber;
        
        public Pending(I2NPMessage message, Hash toRouter, TunnelId toTunnel) {
            _toRouter = toRouter;
            _toTunnel = toTunnel;
            _messageId = message.getUniqueId();
            _expiration = message.getMessageExpiration();
            _remaining = message.toByteArray();
            _offset = 0;
            _fragmentNumber = 0;
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
        /** which fragment are we working on (0 for the first fragment) */
        public int getFragmentNumber() { return _fragmentNumber; }
        /** ok, fragment sent, increment what the next will be */
        public void incrementFragmentNumber() { _fragmentNumber++; }
    }
    
    private class DelayedFlush implements SimpleTimer.TimedEvent {
        public void timeReached() {
            long now = _context.clock().now();
            synchronized (_queue) {
                if ( (_queue.size() > 0) && (_lastFlush + _flushFrequency < now) ) {
                    _preprocessor.preprocessQueue(_queue, _sender, _receiver);
                    _lastFlush = _context.clock().now();
                }
            }
        }
    }
}
