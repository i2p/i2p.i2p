package net.i2p.router.transport.udp;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Hold the raw data fragments of an inbound message.
 *
 * Warning - there is no synchronization in this class, take care in
 * InboundMessageFragments to avoid use-after-release, etc.
 */
class InboundMessageState {
    private final RouterContext _context;
    private final Log _log;
    private final long _messageId;
    private final Hash _from;
    /** 
     * indexed array of fragments for the message, where not yet
     * received fragments are null.
     */
    private final ByteArray _fragments[];

    /**
     * what is the last fragment in the message (or -1 if not yet known)
     * Fragment count is _lastFragment + 1
     */
    private int _lastFragment;
    private final long _receiveBegin;
    private int _completeSize;
    private boolean _released;
    
    /** expire after 10s */
    private static final long MAX_RECEIVE_TIME = 10*1000;
    public static final int MAX_FRAGMENTS = 64;
    
    private static final int MAX_FRAGMENT_SIZE = UDPPacket.MAX_PACKET_SIZE;
    private static final ByteCache _fragmentCache = ByteCache.getInstance(64, MAX_FRAGMENT_SIZE);
    
    public InboundMessageState(RouterContext ctx, long messageId, Hash from) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageState.class);
        _messageId = messageId;
        _from = from;
        _fragments = new ByteArray[MAX_FRAGMENTS];
        _lastFragment = -1;
        _completeSize = -1;
        _receiveBegin = ctx.clock().now();
    }
    
    /**
     * Read in the data from the fragment.
     * Caller should synchronize.
     *
     * @return true if the data was ok, false if it was corrupt
     */
    public boolean receiveFragment(UDPPacketReader.DataReader data, int dataFragment) {
        int fragmentNum = data.readMessageFragmentNum(dataFragment);
        if ( (fragmentNum < 0) || (fragmentNum >= MAX_FRAGMENTS)) {
            _log.warn("Invalid fragment " + fragmentNum + '/' + MAX_FRAGMENTS);
            return false;
        }
        if (_fragments[fragmentNum] == null) {
            // new fragment, read it
            ByteArray message = _fragmentCache.acquire();
            try {
                data.readMessageFragment(dataFragment, message.getData(), 0);
                int size = data.readMessageFragmentSize(dataFragment);
                if (size <= 0) {
                    // Bug in routers prior to 0.8.12
                    // If the msg size was an exact multiple of the fragment size,
                    // it would send a zero-length last fragment.
                    // This message is almost certainly doomed.
                    // We might as well ack it, keep going, and pass it along to I2NP where it
                    // will get dropped as corrupted.
                    // If we don't ack the fragment he will just send a zero-length fragment again.
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Zero-length fragment " + fragmentNum + " for message " + _messageId + " from " + _from);
                }
                message.setValid(size);
                _fragments[fragmentNum] = message;
                boolean isLast = data.readMessageIsLast(dataFragment);
                if (isLast) {
                    // don't allow _lastFragment to be set twice
                    if (_lastFragment >= 0) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Multiple last fragments for message " + _messageId + " from " + _from);
                        return false;
                    }
                    // TODO - check for non-last fragments after this one?
                    _lastFragment = fragmentNum;
                } else if (_lastFragment >= 0 && fragmentNum >= _lastFragment) {
                    // don't allow non-last after last
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Non-last fragment " + fragmentNum + " when last is " + _lastFragment + " for message " + _messageId + " from " + _from);
                    return false;
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New fragment " + fragmentNum + " for message " + _messageId 
                               + ", size=" + size
                               + ", isLast=" + isLast
                          /*   + ", data=" + Base64.encode(message.getData(), 0, size)   */  );
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                _log.warn("Corrupt SSU fragment " + fragmentNum, aioobe);
                return false;
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received fragment " + fragmentNum + " for message " + _messageId 
                           + " again, old size=" + _fragments[fragmentNum].getValid() 
                           + " and new size=" + data.readMessageFragmentSize(dataFragment));
        }
        return true;
    }
    
    /**
     *  May not be valid after released.
     *  Probably doesn't need to be synced by caller, given the order of
     *  events in receiveFragment() above, but you might want to anyway
     *  to be safe.
     */
    public boolean isComplete() {
        int last = _lastFragment;
        if (last < 0) return false;
        for (int i = 0; i <= last; i++)
            if (_fragments[i] == null)
                return false;
        return true;
    }

    public boolean isExpired() { 
        return _context.clock().now() > _receiveBegin + MAX_RECEIVE_TIME;
    }

    public long getLifetime() {
        return _context.clock().now() - _receiveBegin;
    }

    public Hash getFrom() { return _from; }

    public long getMessageId() { return _messageId; }

    /**
     *  @throws IllegalStateException if released or not isComplete()
     */
    public int getCompleteSize() {
        if (_completeSize < 0) {
            if (_lastFragment < 0)
                throw new IllegalStateException("last fragment not set");
            if (_released)
                throw new IllegalStateException("SSU IMS 2 Use after free");
            int size = 0;
            for (int i = 0; i <= _lastFragment; i++) {
                ByteArray frag = _fragments[i];
                if (frag == null)
                    throw new IllegalStateException("null fragment " + i + '/' + _lastFragment);
                size += frag.getValid();
            }
            _completeSize = size;
        }
        return _completeSize;
    }

    public ACKBitfield createACKBitfield() {
        return new PartialBitfield(_messageId, _fragments);
    }
    
    /**
     *  A true partial bitfield that is not complete.
     */
    private static final class PartialBitfield implements ACKBitfield {
        private final long _bitfieldMessageId;
        private final boolean _fragmentsReceived[];
        
        /**
         *  @param data each element is non-null or null for received or not
         */
        public PartialBitfield(long messageId, Object data[]) {
            _bitfieldMessageId = messageId;
            boolean fragmentsRcvd[] = null;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] != null) {
                    if (fragmentsRcvd == null)
                        fragmentsRcvd = new boolean[i+1];
                    fragmentsRcvd[i] = true;
                }
            }
            if (fragmentsRcvd == null)
                _fragmentsReceived = new boolean[0];
            else
                _fragmentsReceived = fragmentsRcvd;
        }

        public int fragmentCount() { return _fragmentsReceived.length; }

        public long getMessageId() { return _bitfieldMessageId; }

        public boolean received(int fragmentNum) { 
            if ( (fragmentNum < 0) || (fragmentNum >= _fragmentsReceived.length) )
                return false;
            return _fragmentsReceived[fragmentNum];
        }

        /** @return false always */
        public boolean receivedComplete() { return false; }
        
        @Override
        public String toString() { 
            StringBuilder buf = new StringBuilder(64);
            buf.append("Partial ACK of ");
            buf.append(_bitfieldMessageId);
            buf.append(" with ACKs for: ");
            for (int i = 0; i < _fragmentsReceived.length; i++)
                if (_fragmentsReceived[i])
                    buf.append(i).append(" ");
            return buf.toString();
        }
    }
    
    public void releaseResources() {
        _released = true;
        for (int i = 0; i < MAX_FRAGMENTS; i++) {
            if (_fragments[i] != null) {
                _fragmentCache.release(_fragments[i]);
                _fragments[i] = null;
            }
        }
    }
    
    /**
     *  @throws IllegalStateException if released
     */
    public ByteArray[] getFragments() {
        if (_released) {
            RuntimeException e = new IllegalStateException("Use after free: " + _messageId);
            _log.error("SSU IMS", e);
            throw e;
        }
        return _fragments;
    }

    public int getFragmentCount() { return _lastFragment+1; }
    
    /**
     *  May not be valid if released, or may NPE on race with release, use with care in exception text
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("IB Message: ").append(_messageId);
        buf.append(" from ").append(_from.toString());
        if (isComplete()) {
            buf.append(" completely received with ");
            //buf.append(getCompleteSize()).append(" bytes");
            // may display -1 but avoid cascaded exceptions after release
            buf.append(_completeSize).append(" bytes");
        } else {
            for (int i = 0; i <= _lastFragment; i++) {
                buf.append(" fragment ").append(i);
                if (_fragments[i] != null)
                    buf.append(": known at size ").append(_fragments[i].getValid());
                else
                    buf.append(": unknown");
            }
        }
        buf.append(" lifetime: ").append(getLifetime());
        return buf.toString();
    }
}
