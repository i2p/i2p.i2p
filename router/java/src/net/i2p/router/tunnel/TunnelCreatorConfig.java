package net.i2p.router.tunnel;

import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.text.SimpleDateFormat;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.TunnelInfo;

/**
 * Coordinate the info that the tunnel creator keeps track of, including what 
 * peers are in the tunnel and what their configuration is
 *
 */
public class TunnelCreatorConfig implements TunnelInfo {
    /** only necessary for client tunnels */
    private Hash _destination;
    /** gateway first */
    private HopConfig _config[];
    /** gateway first */
    private Hash _peers[];
    private long _expiration;
    private boolean _isInbound;
    private long _messagesProcessed;
    
    public TunnelCreatorConfig(int length, boolean isInbound) {
        this(length, isInbound, null);
    }
    public TunnelCreatorConfig(int length, boolean isInbound, Hash destination) {
        if (length <= 0)
            throw new IllegalArgumentException("0 length?  0 hop tunnels are 1 length!");
        _config = new HopConfig[length];
        _peers = new Hash[length];
        for (int i = 0; i < length; i++) {
            _config[i] = new HopConfig();
        }
        _isInbound = isInbound;
        _destination = destination;
        _messagesProcessed = 0;
    }
    
    /** how many hops are there in the tunnel? */
    public int getLength() { return _config.length; }
    
    public Properties getOptions() { return null; }
    
    /** 
     * retrieve the config for the given hop.  the gateway is
     * hop 0.
     */
    public HopConfig getConfig(int hop) { return _config[hop]; }
    /**
     * retrieve the tunnelId that the given hop receives messages on.  
     * the gateway is hop 0.
     *
     */
    public TunnelId getReceiveTunnelId(int hop) { return _config[hop].getReceiveTunnel(); }
    /**
     * retrieve the tunnelId that the given hop sends messages on.  
     * the gateway is hop 0.
     *
     */
    public TunnelId getSendTunnelId(int hop) { return _config[hop].getSendTunnel(); }
    
    /** retrieve the peer at the given hop.  the gateway is hop 0 */
    public Hash getPeer(int hop) { return _peers[hop]; }
    public void setPeer(int hop, Hash peer) { _peers[hop] = peer; }
    
    /** is this an inbound tunnel? */
    public boolean isInbound() { return _isInbound; }

    /** if this is a client tunnel, what destination is it for? */
    public Hash getDestination() { return _destination; }
    
    public long getExpiration() { return _expiration; }
    public void setExpiration(long when) { _expiration = when; }
    
    public void testSuccessful(int ms) {}
    
    /** take note of a message being pumped through this tunnel */
    public void incrementProcessedMessages() { _messagesProcessed++; }
    public long getProcessedMessagesCount() { return _messagesProcessed; }
    
    public String toString() {
        // H0:1235-->H1:2345-->H2:2345
        StringBuffer buf = new StringBuffer(128);
        if (_isInbound)
            buf.append("inbound: ");
        else
            buf.append("outbound: ");
        for (int i = 0; i < _peers.length; i++) {
            buf.append(_peers[i].toBase64().substring(0,4));
            buf.append(':');
            if (_config[i].getReceiveTunnel() != null)
                buf.append(_config[i].getReceiveTunnel());
            else
                buf.append('x');
            buf.append('.');
            if (_config[i].getSendTunnel() != null)
                buf.append(_config[i].getSendTunnel());
            else
                buf.append('x');
            if (i + 1 < _peers.length)
                buf.append("...");
        }
        
        buf.append(" expiring on ").append(getExpirationString());
        if (_destination != null)
            buf.append(" for ").append(Base64.encode(_destination.getData(), 0, 3));
        return buf.toString();
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("HH:mm:ss", Locale.UK);

    private String getExpirationString() {
        return format(_expiration);
    }
    static String format(long date) {
        Date d = new Date(date);
        synchronized (_fmt) {
            return _fmt.format(d);
        }
    }
}
