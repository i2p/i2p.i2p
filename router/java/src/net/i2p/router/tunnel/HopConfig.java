package net.i2p.router.tunnel;

import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Defines the general configuration for a hop in a tunnel.
 *
 */
public class HopConfig {
    private byte _receiveTunnelId[];
    private Hash _receiveFrom;
    private byte _sendTunnelId[];
    private Hash _sendTo;
    private SessionKey _layerKey;
    private SessionKey _ivKey;
    private long _expiration;
    private Map _options;
    
    public HopConfig() {
        _receiveTunnelId = null;
        _receiveFrom = null;
        _sendTunnelId = null;
        _sendTo = null;
        _layerKey = null;
        _ivKey = null;
        _expiration = -1;
        _options = null;
    }
    
    /** what tunnel ID are we receiving on? */
    public byte[] getReceiveTunnelId() { return _receiveTunnelId; }
    public void setReceiveTunnelId(byte id[]) { _receiveTunnelId = id; }
    
    /** what is the previous peer in the tunnel (if any)? */
    public Hash getReceiveFrom() { return _receiveFrom; }
    public void setReceiveFrom(Hash from) { _receiveFrom = from; }
    
    /** what is the next tunnel ID we are sending to? */
    public byte[] getSendTunnelId() { return _sendTunnelId; }
    public void setSendTunnelId(byte id[]) { _sendTunnelId = id; }
    
    /** what is the next peer in the tunnel (if any)? */
    public Hash getSendTo() { return _sendTo; }
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
    
    /** 
     * what are the configuration options for this tunnel (if any)?  keys to
     * this map should be strings and values should be Objects of an 
     * option-specific type (e.g. "maxMessages" would be an Integer, "shouldPad"
     * would be a Boolean, etc).
     *
     */
    public Map getOptions() { return _options; }
    public void setOptions(Map options) { _options = options; }
}
