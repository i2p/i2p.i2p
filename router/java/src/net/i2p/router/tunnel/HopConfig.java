package net.i2p.router.tunnel;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;

/**
 * Defines the general configuration for a hop in a tunnel.
 *
 * This is used for both participating tunnels and tunnels we create.
 * Data only stored for tunnels we create should be in
 * TunnelCreatorConfig to save space.
 */
public class HopConfig {
    private TunnelId _receiveTunnel;
    private Hash _receiveFrom;
    private TunnelId _sendTunnel;
    private Hash _sendTo;
    private SessionKey _layerKey;
    private SessionKey _ivKey;
    private long _creation;
    private long _expiration;
    //private Map _options;

    // these 4 were longs, let's save some space
    // 2 billion * 1KB / 10 minutes = 3 GBps in a single tunnel
    // we use synchronization instead of an AtomicInteger here to save space
    private int _messagesProcessed;
    private int _oldMessagesProcessed;
    //private int _messagesSent;
    //private int _oldMessagesSent;
    
    public HopConfig() {
        _creation = -1;
        _expiration = -1;
    }
    
    /**
     * What tunnel ID are we receiving on? (0 if uninitialized)
     */
    public long getReceiveTunnelId() { return (_receiveTunnel != null) ? _receiveTunnel.getTunnelId() : 0; }

    /**
     * What tunnel ID are we receiving on? (null if uninitialized)
     */
    public TunnelId getReceiveTunnel() { 
        return _receiveTunnel;
    }

    public void setReceiveTunnelId(TunnelId id) { _receiveTunnel = id; }

    /**
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     *  @since 0.9.48
     */
    public void setReceiveTunnelId(long id) { _receiveTunnel = new TunnelId(id); }

    /** what is the previous peer in the tunnel (null if gateway) */
    public Hash getReceiveFrom() { return _receiveFrom; }

    /**
     *  Do not set for gateway
     */
    public void setReceiveFrom(Hash from) { _receiveFrom = from; }
    
    /**
     * What is the next tunnel ID we are sending to? (0 if endpoint)
     */
    public long getSendTunnelId() { return (_sendTunnel != null) ? _sendTunnel.getTunnelId() : 0; }

    /**
     * What is the next tunnel ID we are sending to? (null if endpoint)
     */
    public TunnelId getSendTunnel() { 
        return _sendTunnel;
    }

    /**
     *  Do not set for endpoint
     *  @since 0.9.48
     */
    public void setSendTunnelId(TunnelId id) { _sendTunnel = id; }

    /**
     *  Do not set for endpoint
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     *  @since 0.9.48
     */
    public void setSendTunnelId(long id) { _sendTunnel = new TunnelId(id); }
    
    /** what is the next peer in the tunnel (null if endpoint) */
    public Hash getSendTo() { return _sendTo; }

    /**
     *  Do not set for endpoint
     */
    public void setSendTo(Hash to) { _sendTo = to; }
    
    /** what key should we use to encrypt the layer before passing it on? */
    public SessionKey getLayerKey() { return _layerKey; }
    public void setLayerKey(SessionKey key) { _layerKey = key; }
    
    /** what key should we use to encrypt the preIV before passing it on? */
    public SessionKey getIVKey() { return _ivKey; }
    public void setIVKey(SessionKey key) { _ivKey = key; }
    
    /** when does this tunnel expire (in ms since the epoch)? */
    public long getExpiration() { return _expiration; }
    public void setExpiration(long when) { _expiration = when; }

    /** when was this tunnel created (in ms since the epoch)? */
    public long getCreation() { return _creation; }
    public void setCreation(long when) { _creation = when; }
    
    /** 
     * what are the configuration options for this tunnel (if any).  keys to
     * this map should be strings and values should be Objects of an 
     * option-specific type (e.g. "maxMessages" would be an Integer, "shouldPad"
     * would be a Boolean, etc).
     *
     */
    //public Map getOptions() { return _options; }
    //public void setOptions(Map options) { _options = options; }
    
    /**
     *  Take note of a message being pumped through this tunnel.
     *  "processed" is for incoming and "sent" is for outgoing (could be dropped in between)
     *  We use synchronization instead of an AtomicInteger here to save space.
     */
    public synchronized void incrementProcessedMessages() { _messagesProcessed++; }

    public synchronized int getProcessedMessagesCount() { return _messagesProcessed; }

    /**
     *  This returns the number of processed messages since
     *  the last time getAndResetRecentMessagesCount() was called.
     *  As of 0.9.23, does NOT reset the count, see getAndResetRecentMessagesCount().
     */
    public synchronized int getRecentMessagesCount() {
        return _messagesProcessed - _oldMessagesProcessed;
    }

    /**
     *  This returns the number of processed messages since the last time this was called,
     *  and resets the count. It should only be called by code that updates the router stats.
     *  See TunnelDispatcher.updateParticipatingStats().
     *
     *  @since 0.9.23
     */
    synchronized int getAndResetRecentMessagesCount() {
        int rv = _messagesProcessed - _oldMessagesProcessed;
        _oldMessagesProcessed = _messagesProcessed;
        return rv;
    }

    /**
     *  Take note of a message being pumped through this tunnel.
     *  "processed" is for incoming and "sent" is for outgoing (could be dropped in between)
     */
  /****
    public void incrementSentMessages() { _messagesSent++; }

    public int getSentMessagesCount() { return _messagesSent; }

    public int getRecentSentMessagesCount() {
        int rv = _messagesSent - _oldMessagesSent;
        _oldMessagesSent = _messagesSent;
        return rv;
    }
  ****/
    
    /** */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        if (_receiveTunnel != null) {
            buf.append("recv on ");
            buf.append(_receiveTunnel.getTunnelId());
        }
        if (_sendTo != null) {
            buf.append(" send to ").append(_sendTo.toBase64(), 0, 4).append(":");
            if (_sendTunnel != null)
                buf.append(_sendTunnel.getTunnelId());
        }
        buf.append(" layer key: ").append(_layerKey);
        buf.append(" IV key: ").append(_ivKey);
        buf.append(" exp. ").append(DataHelper.formatTime(_expiration));
        int messagesProcessed = getProcessedMessagesCount();
        if (messagesProcessed > 0)
            buf.append(" used ").append(messagesProcessed).append("KB");
        return buf.toString();
    }
}
