package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.RouterIdentity;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Coordinate the bandwidth limiting across all classes of peers.  Currently
 * treats everything as open (aka doesn't limit)
 *
 */
public class TrivialBandwidthLimiter extends BandwidthLimiter {
    private Log _log;
    private volatile long _maxReceiveBytesPerMinute;
    private volatile long _maxSendBytesPerMinute;
    private volatile long _lastResync;
    private volatile long _lastReadConfig;
    private volatile long _totalReceiveBytes;
    private volatile long _totalSendBytes;
    private volatile long _availableSend;
    private volatile long _availableReceive;
    
    private final static String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundBytesPerMinute";
    private final static String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundBytesPerMinute";
    
    private final static long MINUTE = 60*1000;
    private final static long READ_CONFIG_DELAY = MINUTE;
    
    // max # bytes to store in the pool, in case we have lots of traffic we don't want to
    // spike too hard
    private static long MAX_IN_POOL = 10*1024;
    private static long MAX_OUT_POOL = 10*1024;
    
    public TrivialBandwidthLimiter(RouterContext ctx) {
        this(ctx, -1, -1);
    }
    TrivialBandwidthLimiter(RouterContext ctx, long sendPerMinute, long receivePerMinute) {
        super(ctx);
        _log = ctx.logManager().getLog(TrivialBandwidthLimiter.class);
        _maxReceiveBytesPerMinute = receivePerMinute;
        _maxSendBytesPerMinute = sendPerMinute;
        _lastResync = ctx.clock().now();
        _lastReadConfig = _lastResync;
        _totalReceiveBytes = 0;
        _totalSendBytes = 0;
        _availableReceive = receivePerMinute;
        _availableSend = sendPerMinute;
        MAX_IN_POOL = 10*_availableReceive;
        MAX_OUT_POOL = 10*_availableSend;
        
        _context.jobQueue().addJob(new UpdateBWJob());
        updateLimits();
        if (_log.shouldLog(Log.INFO))
            _log.info("Initializing the limiter with maximum inbound [" + MAX_IN_POOL 
                      + "] outbound [" + MAX_OUT_POOL + "]");
    }
    
    public long getTotalSendBytes() { return _totalSendBytes; }
    public long getTotalReceiveBytes() { return _totalReceiveBytes; }
    
    /**
     * Return how many milliseconds to wait before receiving/processing numBytes from the peer
     */
    public long calculateDelayInbound(RouterIdentity peer, int numBytes) {
        if (_maxReceiveBytesPerMinute <= 0) return 0;
        if (_availableReceive - numBytes > 0) {
            // we have bytes available
            return 0;
        } else {
            // we don't have sufficient bytes.
            // the delay = (needed/numPerMinute)
            long val = MINUTE*(numBytes-_availableReceive)/_maxReceiveBytesPerMinute;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DelayInbound: " + val + " for " + numBytes + " (avail=" 
                           + _availableReceive + ", max=" + _maxReceiveBytesPerMinute + ")");
            return val;
        }
    }
    
    /**
     * Return how many milliseconds to wait before sending numBytes to the peer
     */
    public long calculateDelayOutbound(RouterIdentity peer, int numBytes) {
        if (_maxSendBytesPerMinute <= 0) return 0;
        if (_availableSend - numBytes > 0) {
            // we have bytes available
            return 0;
        } else {
            // we don't have sufficient bytes.
            // the delay = (needed/numPerMinute)
            long val = MINUTE*(numBytes-_availableSend)/_maxSendBytesPerMinute;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DelayOutbound: " + val + " for " + numBytes + " (avail=" 
                           + _availableSend + ", max=" + _maxSendBytesPerMinute + ")");
            return val;
        }
    }
    
    /**
     * Note that numBytes have been read from the peer
     */
    public void consumeInbound(RouterIdentity peer, int numBytes) {
        _totalReceiveBytes += numBytes;
        _availableReceive -= numBytes;
    }
    
    /**
     * Note that numBytes have been sent to the peer
     */
    public void consumeOutbound(RouterIdentity peer, int numBytes) {
        _totalSendBytes += numBytes;
        _availableSend -= numBytes;
    }
    
    private void updateLimits() {
        String inBwStr = _context.router().getConfigSetting(PROP_INBOUND_BANDWIDTH);
        String outBwStr = _context.router().getConfigSetting(PROP_OUTBOUND_BANDWIDTH);
        if (true) {
            // DISABLED UNTIL THIS STUFF GETS A REVAMP
            inBwStr = "-60";
            outBwStr = "-60";
        }
        long oldReceive = _maxReceiveBytesPerMinute;
        long oldSend = _maxSendBytesPerMinute;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read limits ["+inBwStr+" in, " + outBwStr + " out] vs current [" + oldReceive + " in, " + oldSend + " out]");
        
        if ( (inBwStr != null) && (inBwStr.trim().length() > 0) ) {
            try {
                long in = Long.parseLong(inBwStr);
                if (in >= 0) {
                    _maxReceiveBytesPerMinute = in;
                    MAX_IN_POOL = 10*_maxReceiveBytesPerMinute;
                }
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid inbound bandwidth limit [" + inBwStr + "], keeping as " + _maxReceiveBytesPerMinute);
            }
        } else {
            _log.warn("Inbound bandwidth limits not specified in the config via " + PROP_INBOUND_BANDWIDTH);
        }
        if ( (outBwStr != null) && (outBwStr.trim().length() > 0) ) {
            try {
                long out = Long.parseLong(outBwStr);
                if (out >= 0) {
                    _maxSendBytesPerMinute = out;
                    MAX_OUT_POOL = 10*_maxSendBytesPerMinute;
                }
            } catch (NumberFormatException nfe) {
                _log.warn("Invalid outbound bandwidth limit [" + outBwStr + "], keeping as " + _maxSendBytesPerMinute);
            }
        } else {
            _log.warn("Outbound bandwidth limits not specified in the config via " + PROP_OUTBOUND_BANDWIDTH);
        }
        
        if ( (oldReceive != _maxReceiveBytesPerMinute) || (oldSend != _maxSendBytesPerMinute) ) {
            _log.info("Max receive bytes per minute: " + _maxReceiveBytesPerMinute + ", max send per minute: " + _maxSendBytesPerMinute);
            _availableReceive = _maxReceiveBytesPerMinute;
            _availableSend = _maxSendBytesPerMinute;
        }
    }
    
    private class UpdateBWJob extends JobImpl {
        public UpdateBWJob() {
            super(TrivialBandwidthLimiter.this._context);
            getTiming().setStartAfter(TrivialBandwidthLimiter.this._context.clock().now() + MINUTE);
        }
        public String getName() { return "Update bandwidth available"; }
        
        public void runJob() {
            long now = TrivialBandwidthLimiter.this._context.clock().now();
            long numMinutes = ((now - _lastResync)/MINUTE) + 1;
            _availableReceive += numMinutes * _maxReceiveBytesPerMinute;
            _availableSend += numMinutes * _maxSendBytesPerMinute;
            _lastResync = now;
            
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Adding " + (numMinutes*_maxReceiveBytesPerMinute) + " bytes to availableReceive");
                _log.debug("Adding " + (numMinutes*_maxSendBytesPerMinute) + " bytes to availableSend");
            }
            
            // if we're huge, trim
            if (_availableReceive > MAX_IN_POOL) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Trimming available receive to " + MAX_IN_POOL);
                _availableReceive = MAX_IN_POOL;
            }
            if (_availableSend > MAX_OUT_POOL) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Trimming available send to " + MAX_OUT_POOL);
                _availableSend = MAX_OUT_POOL;
            }
            
            getTiming().setStartAfter(now + MINUTE);
            UpdateBWJob.this._context.jobQueue().addJob(UpdateBWJob.this);
            
            // now update the bandwidth limits, in case they've changed
            if (now > _lastReadConfig + READ_CONFIG_DELAY) {
                updateLimits();
                _lastReadConfig = now;
            }
        }
    }
}
