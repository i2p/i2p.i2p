package net.i2p.router.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Gather fragments of I2NPMessages at a tunnel endpoint, making them available 
 * for reading when complete.
 *
 * Warning - this is all unsynchronized here - receivers must implement synchronization
 *
 */
class FragmentedMessage {
    private final I2PAppContext _context;
    private final Log _log;
    private long _messageId;
    private Hash _toRouter;
    private TunnelId _toTunnel;
    private ByteArray _fragments[];
    private boolean _lastReceived;
    private int _highFragmentNum;
    private final long _createdOn;
    private boolean _completed;
    private long _releasedAfter;
    private SimpleTimer.TimedEvent _expireEvent;
    
    private static final ByteCache _cache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);
    // 64 is pretty absurd, 32 is too, most likely
    private static final int MAX_FRAGMENTS = 64;
    
    public FragmentedMessage(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(FragmentedMessage.class);
        _messageId = -1;
        _fragments = new ByteArray[MAX_FRAGMENTS];
        _highFragmentNum = -1;
        _releasedAfter = -1;
        _createdOn = ctx.clock().now();
    }
    
    /**
     * Receive a followup fragment, though one of these may arrive at the endpoint
     * prior to the fragment # 0.
     *
     * @param messageId what messageId is this fragment a part of 
     * @param fragmentNum sequence number within the message (must be greater than 1)
     * @param payload data for the fragment
     * @param offset index into the payload where the fragment data starts (past headers/etc)
     * @param length how much past the offset should we snag?
     * @param isLast is this the last fragment in the message?
     */
    public boolean receive(long messageId, int fragmentNum, byte payload[], int offset, int length, boolean isLast) {
        if (fragmentNum < 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Fragment # == " + fragmentNum + " for messageId " + messageId);
            return false;
        }
        if (payload == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Payload is null for messageId " + messageId);
            return false;
        }
        if (length <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Length is impossible (" + length + ") for messageId " + messageId);
            return false;
        }
        if (offset + length > payload.length) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Length is impossible (" + length + "/" + offset + " out of " + payload.length + ") for messageId " + messageId);
            return false;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive message " + messageId + " fragment " + fragmentNum + " with " + length + " bytes (last? " + isLast + ") offset = " + offset);
        _messageId = messageId;
        // we should just use payload[] and use an offset/length on it
        ByteArray ba = _cache.acquire(); //new ByteArray(payload, offset, length); //new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        ba.setValid(length);
        ba.setOffset(0);
        //System.arraycopy(payload, offset, ba.getData(), 0, length);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("fragment[" + fragmentNum + "/" + offset + "/" + length + "]: " 
                       + Base64.encode(ba.getData(), ba.getOffset(), ba.getValid()));

        _fragments[fragmentNum] = ba;
        _lastReceived = _lastReceived || isLast;
        if (fragmentNum > _highFragmentNum)
            _highFragmentNum = fragmentNum;
        if (isLast && fragmentNum <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("hmm, isLast and fragmentNum=" + fragmentNum + " for message " + messageId);
            return false;
        }
        return true;
    }
    
    /**
     * Receive the first fragment and related metadata.  This may not be the first
     * one to arrive at the endpoint however.
     *
     * @param messageId what messageId is this fragment a part of 
     * @param payload data for the fragment
     * @param offset index into the payload where the fragment data starts (past headers/etc)
     * @param length how much past the offset should we snag?
     * @param isLast is this the last fragment in the message?
     * @param toRouter what router is this destined for (may be null)
     * @param toTunnel what tunnel is this destined for (may be null)
     */
    public boolean receive(long messageId, byte payload[], int offset, int length, boolean isLast, Hash toRouter, TunnelId toTunnel) {
        if (payload == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Payload is null for messageId " + messageId);
            return false;
        }
        if (length <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Length is impossible (" + length + ") for messageId " + messageId);
            return false;
        }
        if (offset + length > payload.length) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Length is impossible (" + length + "/" + offset + " out of " + payload.length + ") for messageId " + messageId);
            return false;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive message " + messageId + " with " + length + " bytes (last? " + isLast + ") targetting " + toRouter + " / " + toTunnel + " offset=" + offset);
        _messageId = messageId;
        ByteArray ba = _cache.acquire(); // new ByteArray(payload, offset, length); // new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        ba.setValid(length);
        ba.setOffset(0);
        //System.arraycopy(payload, offset, ba.getData(), 0, length);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("fragment[0/" + offset + "/" + length + "]: " 
                       + Base64.encode(ba.getData(), ba.getOffset(), ba.getValid()));
        _fragments[0] = ba;
        _lastReceived = _lastReceived || isLast;
        _toRouter = toRouter;
        _toTunnel = toTunnel;
        if (_highFragmentNum < 0)
            _highFragmentNum = 0;
        return true;
    }
    
    public long getMessageId() { return _messageId; }
    public Hash getTargetRouter() { return _toRouter; }
    public TunnelId getTargetTunnel() { return _toTunnel; }
    public int getFragmentCount() { 
        int found = 0;
        for (int i = 0; i < _fragments.length; i++)
            if (_fragments[i] != null)
                found++;
        return found;
    }
    /** used in the fragment handler so we can cancel the expire event on success */
    SimpleTimer.TimedEvent getExpireEvent() { return _expireEvent; }
    void setExpireEvent(SimpleTimer.TimedEvent evt) { _expireEvent = evt; }
    
    /** have we received all of the fragments? */
    public boolean isComplete() {
        if (!_lastReceived)
            return false;
        for (int i = 0; i <= _highFragmentNum; i++)
            if (_fragments[i] == null)
                return false;
        return true;
    }
    public int getCompleteSize() {
        if (!_lastReceived) 
            throw new IllegalStateException("wtf, don't get the completed size when we're not complete");
        if (_releasedAfter > 0) {
             RuntimeException e = new RuntimeException("use after free in FragmentedMessage");
             _log.error("FM completeSize()", e);
             throw e;
        }
        int size = 0;
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            // NPE seen here, root cause unknown
            if (ba == null) 
                throw new IllegalStateException("wtf, don't get the completed size when we're not complete - null fragment i=" + i + " of " + _highFragmentNum);
            size += ba.getValid();
        }
        return size;
    }
    
    /** how long has this fragmented message been alive?  */
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    public boolean getReleased() { return _completed; }
    
    
    public void writeComplete(OutputStream out) throws IOException {
        if (_releasedAfter > 0) {
             RuntimeException e = new RuntimeException("use after free in FragmentedMessage");
             _log.error("FM writeComplete()", e);
             throw e;
        }
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            out.write(ba.getData(), ba.getOffset(), ba.getValid());
        }
        _completed = true;
    }
    public void writeComplete(byte target[], int offset) {
        if (_releasedAfter > 0) {
             RuntimeException e = new RuntimeException("use after free in FragmentedMessage");
             _log.error("FM writeComplete() 2", e);
             throw e;
        }
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            System.arraycopy(ba.getData(), ba.getOffset(), target, offset, ba.getValid());
            offset += ba.getValid();
        }
        _completed = true;
    }
    public byte[] toByteArray() {
        synchronized (this) {
            if (_releasedAfter > 0) return null;
            byte rv[] = new byte[getCompleteSize()];
            writeComplete(rv, 0);
            releaseFragments();
            return rv;
        }
    }
    
    public long getReleasedAfter() { return _releasedAfter; }
    public void failed() {
        synchronized (this) {
            releaseFragments();
        }
    }

    /**
     * Called as one of the endpoints for the tunnel cache pipeline (see TunnelDataMessage)
     *
     */
    private void releaseFragments() {
        if (_releasedAfter > 0) {
             RuntimeException e = new RuntimeException("double free in FragmentedMessage");
             _log.error("FM releaseFragments()", e);
             throw e;
        }
        _releasedAfter = getLifetime();
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            if ( (ba != null) && (ba.getData().length == TrivialPreprocessor.PREPROCESSED_SIZE) ) {
                _cache.release(ba);
                _fragments[i] = null;
            }
        }
    }
    
/****
    public InputStream getInputStream() { return new FragmentInputStream(); }
    private class FragmentInputStream extends InputStream {
        private int _fragment;
        private int _offset;
        public FragmentInputStream() {
            _fragment = 0;
            _offset = 0;
        }
        public int read() throws IOException {
            while (true) {
                ByteArray ba = _fragments[_fragment];
                if (ba == null) return -1;
                if (_offset >= ba.getValid()) {
                    _fragment++;
                    _offset = 0;
                } else {
                    byte rv = ba.getData()[ba.getOffset()+_offset];
                    _offset++;
                    return rv;
                }
            }
        }
    }
****/
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("Fragments for ").append(_messageId).append(": ");
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            if (ba != null)
                buf.append(i).append(":").append(ba.getValid()).append(" bytes ");
            else
                buf.append(i).append(":missing ");
        }
        buf.append(" highest received: ").append(_highFragmentNum);
        buf.append(" last received? ").append(_lastReceived);
        buf.append(" lifetime: ").append(DataHelper.formatDuration(_context.clock().now()-_createdOn));
        if (_toRouter != null) {
            buf.append(" targetting ").append(_toRouter.toBase64().substring(0,4));
            if (_toTunnel != null)
                buf.append(":").append(_toTunnel.getTunnelId());
        }
        if (_completed)
            buf.append(" completed");
        if (_releasedAfter > 0)
            buf.append(" released after " + DataHelper.formatDuration(_releasedAfter));
        return buf.toString();
    }
    
/*****
    public static void main(String args[]) {
        try {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            DataMessage m = new DataMessage(ctx);
            m.setData(new byte[1024]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(ctx.clock().now() + 60*1000);
            m.setUniqueId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            byte data[] = m.toByteArray();
            
            I2NPMessage r0 = new I2NPMessageHandler(ctx).readMessage(data);
            System.out.println("peq? " + r0.equals(m));
            
            FragmentedMessage msg = new FragmentedMessage(ctx);
            msg.receive(m.getUniqueId(), data, 0, 500, false, null, null);
            msg.receive(m.getUniqueId(), 1, data, 500, 500, false);
            msg.receive(m.getUniqueId(), 2, data, 1000, data.length-1000, true);
            if (!msg.isComplete()) throw new RuntimeException("Not complete?");
            
            byte recv[] = msg.toByteArray();
            I2NPMessage r = new I2NPMessageHandler(ctx).readMessage(recv);
            System.out.println("eq? " + m.equals(r));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
******/
}
