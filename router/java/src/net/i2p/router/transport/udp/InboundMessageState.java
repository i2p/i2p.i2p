package net.i2p.router.transport.udp;

import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Hold the raw data fragments of an inbound message
 *
 */
public class InboundMessageState {
    private RouterContext _context;
    private Log _log;
    private long _messageId;
    private Hash _from;
    /** 
     * indexed array of fragments for the message, where not yet
     * received fragments are null.
     */
    private ByteArray _fragments[];
    /**
     * what is the last fragment in the message (or -1 if not yet known)
     */
    private int _lastFragment;
    private long _receiveBegin;
    private int _completeSize;
    
    /** expire after 30s */
    private static final long MAX_RECEIVE_TIME = 10*1000;
    private static final int MAX_FRAGMENTS = 32;
    
    private static final ByteCache _fragmentCache = ByteCache.getInstance(64, 2048);
    
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
     *
     * @return true if the data was ok, false if it was corrupt
     */
    public synchronized boolean receiveFragment(UDPPacketReader.DataReader data, int dataFragment) {
        int fragmentNum = data.readMessageFragmentNum(dataFragment);
        if ( (fragmentNum < 0) || (fragmentNum > _fragments.length)) {
            StringBuffer buf = new StringBuffer(1024);
            buf.append("Invalid fragment ").append(fragmentNum);
            buf.append(": ").append(data);
            data.toRawString(buf);
            _log.log(Log.CRIT, buf.toString(), new Exception("source"));
            return false;
        }
        if (_fragments[fragmentNum] == null) {
            // new fragment, read it
            ByteArray message = _fragmentCache.acquire();
            data.readMessageFragment(dataFragment, message.getData(), 0);
            int size = data.readMessageFragmentSize(dataFragment);
            message.setValid(size);
            _fragments[fragmentNum] = message;
            if (data.readMessageIsLast(dataFragment))
                _lastFragment = fragmentNum;
        }
        return true;
    }
    
    public synchronized boolean isComplete() {
        if (_lastFragment < 0) return false;
        for (int i = 0; i <= _lastFragment; i++)
            if (_fragments[i] == null)
                return false;
        return true;
    }
    public synchronized boolean isExpired() { 
        return _context.clock().now() > _receiveBegin + MAX_RECEIVE_TIME;
    }
    public long getLifetime() {
        return _context.clock().now() - _receiveBegin;
    }
    public Hash getFrom() { return _from; }
    public long getMessageId() { return _messageId; }
    public synchronized int getCompleteSize() {
        if (_completeSize < 0) {
            int size = 0;
            for (int i = 0; i <= _lastFragment; i++)
                size += _fragments[i].getValid();
            _completeSize = size;
        }
        return _completeSize;
    }
    
    public void releaseResources() {
        if (_fragments != null)
            for (int i = 0; i < _fragments.length; i++)
                _fragmentCache.release(_fragments[i]);
        //_fragments = null;
    }
    
    public ByteArray[] getFragments() {
        return _fragments;
    }
    public int getFragmentCount() { return _lastFragment+1; }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(32);
        buf.append("Message: ").append(_messageId);
        //if (isComplete()) {
        //    buf.append(" completely received with ");
        //    buf.append(getCompleteSize()).append(" bytes");
        //}
        buf.append(" lifetime: ").append(getLifetime());
        return buf.toString();
    }
}
