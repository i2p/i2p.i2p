package net.i2p.router.tunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Gather fragments of I2NPMessages at a tunnel endpoint, making them available 
 * for reading when complete.
 *
 */
public class FragmentedMessage {
    private I2PAppContext _context;
    private Log _log;
    private long _messageId;
    private Hash _toRouter;
    private TunnelId _toTunnel;
    private Map _fragments;
    private boolean _lastReceived;
    private int _highFragmentNum;
    private long _createdOn;
    private SimpleTimer.TimedEvent _expireEvent;
    
    public FragmentedMessage(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(FragmentedMessage.class);
        _messageId = -1;
        _toRouter = null;
        _toTunnel = null;
        _fragments = new HashMap(1);
        _lastReceived = false;
        _highFragmentNum = -1;
        _createdOn = ctx.clock().now();
        _expireEvent = null;
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
    public void receive(long messageId, int fragmentNum, byte payload[], int offset, int length, boolean isLast) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive message " + messageId + " fragment " + fragmentNum + " with " + length + " bytes (last? " + isLast + ") offset = " + offset);
        _messageId = messageId;
        ByteArray ba = new ByteArray(new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        _log.debug("fragment[" + fragmentNum + "/" + offset + "/" + length + "]: " + Base64.encode(ba.getData()));

        _fragments.put(new Integer(fragmentNum), ba);
        _lastReceived = isLast;
        if (isLast)
            _highFragmentNum = fragmentNum;
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
    public void receive(long messageId, byte payload[], int offset, int length, boolean isLast, Hash toRouter, TunnelId toTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive message " + messageId + " with " + length + " bytes (last? " + isLast + ") targetting " + toRouter + " / " + toTunnel + " offset=" + offset);
        _messageId = messageId;
        ByteArray ba = new ByteArray(new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        _log.debug("fragment[0/" + offset + "/" + length + "]: " + Base64.encode(ba.getData()));
        _fragments.put(new Integer(0), ba);
        _lastReceived = isLast;
        _toRouter = toRouter;
        _toTunnel = toTunnel;
        if (isLast)
            _highFragmentNum = 0;
    }
    
    public long getMessageId() { return _messageId; }
    public Hash getTargetRouter() { return _toRouter; }
    public TunnelId getTargetTunnel() { return _toTunnel; }
    /** used in the fragment handler so we can cancel the expire event on success */
    SimpleTimer.TimedEvent getExpireEvent() { return _expireEvent; }
    void setExpireEvent(SimpleTimer.TimedEvent evt) { _expireEvent = evt; }
    
    /** have we received all of the fragments? */
    public boolean isComplete() {
        if (!_lastReceived)
            return false;
        for (int i = 0; i <= _highFragmentNum; i++)
            if (!_fragments.containsKey(new Integer(i)))
                return false;
        return true;
    }
    public int getCompleteSize() {
        if (!_lastReceived) 
            throw new IllegalStateException("wtf, don't get the completed size when we're not complete");
        int size = 0;
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = (ByteArray)_fragments.get(new Integer(i));
            size += ba.getData().length;
        }
        return size;
    }
    
    /** how long has this fragmented message been alive?  */
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    
    
    public void writeComplete(OutputStream out) throws IOException {
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = (ByteArray)_fragments.get(new Integer(i));
            out.write(ba.getData());
        }
    }
    public void writeComplete(byte target[], int offset) {
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = (ByteArray)_fragments.get(new Integer(i));
            System.arraycopy(ba.getData(), 0, target, offset, ba.getData().length);
            offset += ba.getData().length;
        }
    }
    public byte[] toByteArray() {
        byte rv[] = new byte[getCompleteSize()];
        writeComplete(rv, 0);
        return rv;
    }
    
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
                ByteArray ba = (ByteArray)_fragments.get(new Integer(_fragment));
                if (ba == null) return -1;
                if (_offset >= ba.getData().length) {
                    _fragment++;
                    _offset = 0;
                } else {
                    byte rv = ba.getData()[_offset];
                    _offset++;
                    return rv;
                }
            }
        }
    }
    
    public static void main(String args[]) {
        try {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            DataMessage m = new DataMessage(ctx);
            m.setData(new byte[1024]);
            java.util.Arrays.fill(m.getData(), (byte)0xFF);
            m.setMessageExpiration(new Date(ctx.clock().now() + 60*1000));
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
}
