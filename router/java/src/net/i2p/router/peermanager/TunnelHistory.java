package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Tunnel related history information
 *
 */
public class TunnelHistory {
    private RouterContext _context;
    private Log _log;
    private volatile long _lifetimeAgreedTo;
    private volatile long _lifetimeRejected;
    private volatile long _lastAgreedTo;
    private volatile long _lastRejected;
    private volatile long _lifetimeFailed;
    private volatile long _lastFailed;
    private RateStat _rejectRate;
    private RateStat _failRate;
    private String _statGroup;
    
    public TunnelHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(TunnelHistory.class);
        _statGroup = statGroup;
        _lifetimeAgreedTo = 0;
        _lifetimeFailed = 0;
        _lifetimeRejected = 0;
        _lastAgreedTo = 0;
        _lastFailed = 0;
        _lastRejected = 0;
        createRates(statGroup);
    }
    
    private void createRates(String statGroup) {
        _rejectRate = new RateStat("tunnelHistory.rejectRate", "How often does this peer reject a tunnel request?", statGroup, new long[] { 60*1000l, 10*60*1000l, 30*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _failRate = new RateStat("tunnelHistory.failRate", "How often do tunnels this peer accepts fail?", statGroup, new long[] { 60*1000l, 10*60*1000l, 30*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _rejectRate.setStatLog(_context.statManager().getStatLog());
        _failRate.setStatLog(_context.statManager().getStatLog());
    }
    
    /** total tunnels the peer has agreed to participate in */
    public long getLifetimeAgreedTo() { return _lifetimeAgreedTo; }
    /** total tunnels the peer has refused to participate in */
    public long getLifetimeRejected() { return _lifetimeRejected; }
    /** total tunnels the peer has agreed to participate in that were later marked as failed prematurely */
    public long getLifetimeFailed() { return _lifetimeFailed; }
    /** when the peer last agreed to participate in a tunnel */
    public long getLastAgreedTo() { return _lastAgreedTo; }
    /** when the peer last refused to participate in a tunnel */
    public long getLastRejected() { return _lastRejected; }
    /** when the last tunnel the peer participated in failed */
    public long getLastFailed() { return _lastFailed; }
    
    public void incrementAgreedTo() {
        _lifetimeAgreedTo++;
        _lastAgreedTo = _context.clock().now();
    }
    public void incrementRejected() {
        _lifetimeRejected++;
        _rejectRate.addData(1, 1);
        _lastRejected = _context.clock().now();
    }
    public void incrementFailed() {
        _lifetimeFailed++;
        _failRate.addData(1, 1);
        _lastFailed = _context.clock().now();
    }
    
    public void setLifetimeAgreedTo(long num) { _lifetimeAgreedTo = num; }
    public void setLifetimeRejected(long num) { _lifetimeRejected = num; }
    public void setLifetimeFailed(long num) { _lifetimeFailed = num; }
    public void setLastAgreedTo(long when) { _lastAgreedTo = when; }
    public void setLastRejected(long when) { _lastRejected = when; }
    public void setLastFailed(long when) { _lastFailed = when; }
    
    public RateStat getRejectionRate() { return _rejectRate; }
    public RateStat getFailedRate() { return _failRate; }
    
    public void coallesceStats() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Coallescing stats");
        _rejectRate.coallesceStats();
        _failRate.coallesceStats();
    }
    
    private final static String NL = System.getProperty("line.separator");
    
    public void store(OutputStream out) throws IOException {
        StringBuffer buf = new StringBuffer(512);
        buf.append(NL);
        buf.append("#################").append(NL);
        buf.append("# Tunnel history").append(NL);
        buf.append("###").append(NL);
        add(buf, "lastAgreedTo", _lastAgreedTo, "When did the peer last agree to participate in a tunnel?  (milliseconds since the epoch)");
        add(buf, "lastFailed", _lastFailed, "When was the last time a tunnel that the peer agreed to participate failed?  (milliseconds since the epoch)");
        add(buf, "lastRejected", _lastRejected, "When was the last time the peer refused to participate in a tunnel?  (milliseconds since the epoch)");
        add(buf, "lifetimeAgreedTo", _lifetimeAgreedTo, "How many tunnels has the peer ever agreed to participate in?");
        add(buf, "lifetimeFailed", _lifetimeFailed, "How many tunnels has the peer ever agreed to participate in that failed prematurely?");
        add(buf, "lifetimeRejected", _lifetimeRejected, "How many tunnels has the peer ever refused to participate in?");
        out.write(buf.toString().getBytes());
        _rejectRate.store(out, "tunnelHistory.rejectRate");
        _rejectRate.store(out, "tunnelHistory.failRate");
    }
    
    private void add(StringBuffer buf, String name, long val, String description) {
        buf.append("# ").append(name.toUpperCase()).append(NL).append("# ").append(description).append(NL);
        buf.append("tunnels.").append(name).append('=').append(val).append(NL).append(NL);
    }
    
    public void load(Properties props) {
        _lastAgreedTo = getLong(props, "tunnels.lastAgreedTo");
        _lastFailed = getLong(props, "tunnels.lastFailed");
        _lastRejected = getLong(props, "tunnels.lastRejected");
        _lifetimeAgreedTo = getLong(props, "tunnels.lifetimeAgreedTo");
        _lifetimeFailed = getLong(props, "tunnels.lifetimeFailed");
        _lifetimeRejected = getLong(props, "tunnels.lifetimeRejected");
        try {
            _rejectRate.load(props, "tunnelHistory.rejectRate", true);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loading tunnelHistory.rejectRate");
            _rejectRate.load(props, "tunnelHistory.failRate", true);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loading tunnelHistory.failRate");
        } catch (IllegalArgumentException iae) {
            _log.warn("TunnelHistory rates are corrupt, resetting", iae);
            createRates(_statGroup);
        }
    }
    
    private final static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }
}
