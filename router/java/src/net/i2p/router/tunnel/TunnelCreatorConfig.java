package net.i2p.router.tunnel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;

/**
 * Coordinate the info that the tunnel creator keeps track of, including what 
 * peers are in the tunnel and what their configuration is
 *
 */
public class TunnelCreatorConfig implements TunnelInfo {
    protected final RouterContext _context;
    /** only necessary for client tunnels */
    private final Hash _destination;
    /** gateway first */
    private final HopConfig _config[];
    /** gateway first */
    private final Hash _peers[];
    private long _expiration;
    private List<Integer> _order;
    private long _replyMessageId;
    private final boolean _isInbound;
    private long _messagesProcessed;
    private volatile long _verifiedBytesTransferred;
    private boolean _failed;
    private int _failures;
    private boolean _reused;
    
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        _context = ctx;
        if (length <= 0)
            throw new IllegalArgumentException("0 length?  0 hop tunnels are 1 length!");
        _config = new HopConfig[length];
        _peers = new Hash[length];
        for (int i = 0; i < length; i++) {
            _config[i] = new HopConfig();
        }
        _isInbound = isInbound;
        _destination = destination;
    }
    
    /**
     *  How many hops are there in the tunnel?
     *  INCLUDING US.
     *  i.e. one more than the TunnelCreatorConfig length.
     */
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
    
    /**
     *  For convenience
     *  @return getPeer(0)
     *  @since 0.8.9
     */
    public Hash getGateway() {
        return _peers[0];
    }

    /**
     *  For convenience
     *  @return getPeer(getLength() - 1)
     *  @since 0.8.9
     */
    public Hash getEndpoint() {
        return _peers[_peers.length - 1];
    }

    /**
     *  For convenience
     *  @return isInbound() ? getGateway() : getEndpoint()
     *  @since 0.8.9
     */
    public Hash getFarEnd() {
        return _peers[_isInbound ? 0 : _peers.length - 1];
    }

    /** is this an inbound tunnel? */
    public boolean isInbound() { return _isInbound; }

    /** if this is a client tunnel, what destination is it for? */
    public Hash getDestination() { return _destination; }
    
    public long getExpiration() { return _expiration; }
    public void setExpiration(long when) { _expiration = when; }
    
    /** component ordering in the new style request */
    public List<Integer> getReplyOrder() { return _order; }
    public void setReplyOrder(List<Integer> order) { _order = order; }
    /** new style reply message id */
    public long getReplyMessageId() { return _replyMessageId; }
    public void setReplyMessageId(long id) { _replyMessageId = id; }
    
    /** take note of a message being pumped through this tunnel */
    public void incrementProcessedMessages() { _messagesProcessed++; }
    public long getProcessedMessagesCount() { return _messagesProcessed; }

    public void incrementVerifiedBytesTransferred(int bytes) { 
        _verifiedBytesTransferred += bytes; 
        _peakThroughputCurrentTotal += bytes;
        long now = System.currentTimeMillis();
        long timeSince = now - _peakThroughputLastCoallesce;
        if (timeSince >= 60*1000) {
            long tot = _peakThroughputCurrentTotal;
            double normalized = (double)tot * 60d*1000d / (double)timeSince;
            _peakThroughputLastCoallesce = now;
            _peakThroughputCurrentTotal = 0;
            if (_context != null)
                for (int i = 0; i < _peers.length; i++)
                    _context.profileManager().tunnelDataPushed1m(_peers[i], (int)normalized);
        }
    }
    public long getVerifiedBytesTransferred() { return _verifiedBytesTransferred; }

    private static final int THROUGHPUT_COUNT = 3;
    /** 
     * fastest 1 minute throughput, in bytes per minute, ordered with fastest
     * first.
     */
    private final double _peakThroughput[] = new double[THROUGHPUT_COUNT];
    private volatile long _peakThroughputCurrentTotal;
    private volatile long _peakThroughputLastCoallesce = System.currentTimeMillis();
    public double getPeakThroughputKBps() { 
        double rv = 0;
        for (int i = 0; i < THROUGHPUT_COUNT; i++)
            rv += _peakThroughput[i];
        rv /= (60d*1024d*(double)THROUGHPUT_COUNT);
        return rv;
    }
    public void setPeakThroughputKBps(double kBps) {
        _peakThroughput[0] = kBps*60*1024;
        //for (int i = 0; i < THROUGHPUT_COUNT; i++)
        //    _peakThroughput[i] = kBps*60;
    }
    
    
    // Make configurable? - but can't easily get to pool options from here
    private static final int MAX_CONSECUTIVE_TEST_FAILURES = 3;
    
    /**
     * The tunnel failed a test, so (maybe) stop using it
     */
    public boolean tunnelFailed() {
        _failures++;
        if (_failures > MAX_CONSECUTIVE_TEST_FAILURES) {
            _failed = true;
            return false;
        } else {
            return true;
        }
    }
    public boolean getTunnelFailed() { return _failed; }
    public int getTunnelFailures() { return _failures; }
    
    public void testSuccessful(int ms) {
        if (!_failed)
            _failures = 0;
    }
    
    /**
     *  Did we reuse this tunnel?
     *  @since 0.8.11
     */
    public boolean wasReused() { return _reused; }

    /**
     *  Note that we reused this tunnel
     *  @since 0.8.11
     */
    public void setReused() { _reused = true; }

    @Override
    public String toString() {
        // H0:1235-->H1:2345-->H2:2345
        StringBuilder buf = new StringBuilder(128);
        if (_isInbound)
            buf.append("IB");
        else
            buf.append("OB");
        if (_destination == null)
            buf.append(" expl");
        else
            buf.append(" client ").append(Base64.encode(_destination.getData(), 0, 3));
        buf.append(": GW ");
        for (int i = 0; i < _peers.length; i++) {
            buf.append(_peers[i].toBase64().substring(0,4));
            buf.append(':');
            if (_config[i].getReceiveTunnel() != null)
                buf.append(_config[i].getReceiveTunnel());
            else
                buf.append("me");
            buf.append('.');
            if (_config[i].getSendTunnel() != null)
                buf.append(_config[i].getSendTunnel());
            else
                buf.append("me");
            if (i + 1 < _peers.length)
                buf.append("-->");
        }
        
        buf.append(" exp. ").append(getExpirationString());
        if (_replyMessageId > 0)
            buf.append(" replyMsgID ").append(_replyMessageId);
        if (_messagesProcessed > 0)
            buf.append(" with ").append(_messagesProcessed).append("/").append(_verifiedBytesTransferred).append(" msgs/bytes");
    
        if (_failures > 0)
            buf.append(" with ").append(_failures).append(" failures");
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
