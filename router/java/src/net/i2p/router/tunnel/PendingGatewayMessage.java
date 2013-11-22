package net.i2p.router.tunnel;
    
import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.util.CDQEntry;

/**
 *  Stores all the state for an unsent or partially-sent message
 *
 *  @since 0.9.3 refactored from TunnelGateway.Pending
 */
class PendingGatewayMessage implements CDQEntry {
    protected final Hash _toRouter;
    protected final TunnelId _toTunnel;
    protected final long _messageId;
    protected final long _expiration;
    protected final byte _remaining[];
    protected int _offset;
    protected int _fragmentNumber;
    protected final long _created;
    private List<Long> _messageIds;
    private long _enqueueTime;
    
    public PendingGatewayMessage(I2NPMessage message, Hash toRouter, TunnelId toTunnel) {
        _toRouter = toRouter;
        _toTunnel = toTunnel;
        _messageId = message.getUniqueId();
        _expiration = message.getMessageExpiration();
        _remaining = message.toByteArray();
        _created = System.currentTimeMillis();
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
        synchronized (this) {
            if (_messageIds == null)
                _messageIds = new ArrayList<Long>();
            _messageIds.add(Long.valueOf(id));
        }
    }

    /**
     *  The IDs of the TunnelDataMssages this message was fragmented into.
     *  Unused except in notePreprocessing() calls for debugging
     */
    public List<Long> getMessageIds() { 
        synchronized (this) { 
            if (_messageIds != null)
                return new ArrayList<Long>(_messageIds); 
            else
                return new ArrayList<Long>();
        } 
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void drop() {
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("Message ").append(_messageId); //.append(" on ");
        //buf.append(TunnelGateway.this.toString());
        if (_toRouter != null) {
            buf.append(" targetting ");
            buf.append(_toRouter.toBase64()).append(" ");
            if (_toTunnel != null)
                buf.append(_toTunnel.getTunnelId());
        }
        buf.append(" actual lifetime ");
        buf.append(getLifetime()).append("ms");
        buf.append(" potential lifetime ");
        buf.append(_expiration - _created).append("ms");
        buf.append(" size ").append(_remaining.length);
        buf.append(" offset ").append(_offset);
        buf.append(" frag ").append(_fragmentNumber);
        return buf.toString();
    }
}
    
