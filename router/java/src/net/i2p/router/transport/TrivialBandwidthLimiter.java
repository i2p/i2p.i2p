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
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.I2PThread;

/**
 * Coordinate the bandwidth limiting across all classes of peers.  Currently
 * treats everything as open (aka doesn't limit)
 *
 */
public class TrivialBandwidthLimiter extends BandwidthLimiter {
    private Log _log;
    /** how many bytes can we read from the network without blocking? */
    private volatile long _inboundAvailable;
    /** how many bytes can we write to the network without blocking? */
    private volatile long _outboundAvailable;
    /** how large will we let the inboundAvailable queue grow? */
    private volatile long _inboundBurstBytes;
    /** how large will we let the outboundAvailable queue grow? */
    private volatile long _outboundBurstBytes;
    /** how many bytes have we ever read from the network? */
    private volatile long _totalInboundBytes;
    /** how many bytes have we ever written to the network? */
    private volatile long _totalOutboundBytes;
    /** how many KBps do we want to allow? */
    private long _inboundKBytesPerSecond;
    /** how many KBps do we want to allow? */
    private long _outboundKBytesPerSecond;
    /** how frequently do we want to replenish the available queues? */
    private long _replenishFrequency;
    private long _minNonZeroDelay;
    /** 
     * when did we last replenish the available queues (since it wont 
     * likely exactly match the replenish frequency)? 
     */
    private volatile long _lastResync;
    /** when did we last update the limits? */
    private long _lastUpdateLimits;
    
    /** 
     * notify this object whenever we need bandwidth and we'll refresh the pool
     * (though not necessarily with sufficient or even any bytes)
     *
     */
    private Object _updateBwLock = new Object();
    
    final static String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    final static String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    final static String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    final static String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    final static String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequency";
    final static String PROP_MIN_NON_ZERO_DELAY = "i2np.bandwidth.minimumNonZeroDelay";
    final static long DEFAULT_REPLENISH_FREQUENCY = 1*1000;
    final static long DEFAULT_MIN_NON_ZERO_DELAY = 1*1000;
    
    final static long UPDATE_LIMIT_FREQUENCY = 60*1000;
    
    public TrivialBandwidthLimiter(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(TrivialBandwidthLimiter.class);
        _inboundAvailable = 0;
        _outboundAvailable = 0;
        _inboundBurstBytes = -1;
        _outboundBurstBytes = -1;
        _inboundKBytesPerSecond = -1;
        _outboundKBytesPerSecond = -1;
        _totalInboundBytes = 0;
        _totalOutboundBytes = 0;
        _replenishFrequency = DEFAULT_REPLENISH_FREQUENCY;
        _lastResync = ctx.clock().now();
        
        updateLimits();
        I2PThread bwThread = new I2PThread(new UpdateBWRunner());
        bwThread.setDaemon(true);
        bwThread.setPriority(I2PThread.MIN_PRIORITY);
        bwThread.setName("BW Updater");
        bwThread.start();
    }
    
    public long getTotalSendBytes() { return _totalOutboundBytes; }
    public long getTotalReceiveBytes() { return _totalInboundBytes; }
    
    /**
     * Return how many milliseconds to wait before receiving/processing numBytes from the peer
     */
    public long calculateDelayInbound(RouterIdentity peer, int numBytes) {
        if (_inboundKBytesPerSecond <= 0) return 0;
        if (_inboundAvailable - numBytes > 0) {
            // we have bytes available
            return 0;
        } else {
            // we don't have sufficient bytes.
            // the delay = 1000*(bytes needed/bytes per second)
            double val = 1000.0*(((double)numBytes-(double)_inboundAvailable)/((double)_inboundKBytesPerSecond*1024));
            long rv = (long)Math.ceil(val);
            if ( (rv > 0) && (rv < _minNonZeroDelay) )
                rv = _minNonZeroDelay;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DelayInbound: " + rv + " for " + numBytes + " (avail=" 
                           + _inboundAvailable + ", max=" + _inboundBurstBytes + ", kbps=" + _inboundKBytesPerSecond + ")");
            // we will want to replenish before this requestor comes back for the data
            if (rv < _replenishFrequency)
                synchronized (_updateBwLock) { _updateBwLock.notify(); } 
            return rv;
        }
    }
    
    /**
     * Return how many milliseconds to wait before sending numBytes to the peer
     */
    public long calculateDelayOutbound(RouterIdentity peer, int numBytes) {
        if (_outboundKBytesPerSecond <= 0) return 0;
        if (_outboundAvailable - numBytes > 0) {
            // we have bytes available
            return 0;
        } else {
            // we don't have sufficient bytes.
            // lets make sure...
            // the delay = 1000*(bytes needed/bytes per second)
            long avail = _outboundAvailable;
            double val = 1000.0*(((double)numBytes-(double)avail)/((double)_outboundKBytesPerSecond*1024.0));
            long rv = (long)Math.ceil(val);
            if ( (rv > 0) && (rv < _minNonZeroDelay) )
                rv = _minNonZeroDelay;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DelayOutbound: " + rv + " for " + numBytes + " (avail=" 
                           + avail + ", max=" + _outboundBurstBytes + ", kbps=" + _outboundKBytesPerSecond + ")");
            // we will want to replenish before this requestor comes back for the data
            if (rv < _replenishFrequency)
                synchronized (_updateBwLock) { _updateBwLock.notify(); } 
            return rv;
        }
    }
    
    /**
     * Note that numBytes have been read from the peer
     */
    public void consumeInbound(RouterIdentity peer, int numBytes) {
        if (numBytes > 0)
            _totalInboundBytes += numBytes;
        if (_inboundKBytesPerSecond > 0)
            _inboundAvailable -= numBytes;
    }
    
    /**
     * Note that numBytes have been sent to the peer
     */
    public void consumeOutbound(RouterIdentity peer, int numBytes) {
        if (numBytes > 0)
            _totalOutboundBytes += numBytes;
        if (_outboundKBytesPerSecond > 0)
            _outboundAvailable -= numBytes;
    }
    
    private void updateLimits() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updating rates for the bw limiter");

        _lastUpdateLimits = _context.clock().now();
        updateInboundRate();
        updateOutboundRate();
        updateInboundPeak();
        updateOutboundPeak();
        updateReplenishFrequency();
        updateMinNonZeroDelay();
    }
    
    private void updateInboundRate() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_inboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                long in = Long.parseLong(inBwStr);
                if (in >= 0) {
                    _inboundKBytesPerSecond = in;
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth limit [" + inBwStr 
                              + "], keeping as " + _inboundKBytesPerSecond);
            }
        } else {
            if ( (inBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Inbound bandwidth limits not specified in the config via " + PROP_INBOUND_BANDWIDTH);
        }
    }
    private void updateOutboundRate() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH);
        
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_outboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                long out = Long.parseLong(outBwStr);
                _outboundKBytesPerSecond = out;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth limit [" + outBwStr 
                              + "], keeping as " + _outboundKBytesPerSecond);
            }
        } else {
            if ( (outBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Outbound bandwidth limits not specified in the config via " + PROP_OUTBOUND_BANDWIDTH);
        }
    }
    
    private void updateInboundPeak() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH_PEAK);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_inboundBurstBytes)))) ) {
            // peak bw was specified *and* changed
            try {
                long in = Long.parseLong(inBwStr);
                _inboundBurstBytes = in * 1024;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth burst limit [" + inBwStr 
                              + "], keeping as " + _inboundBurstBytes);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Inbound bandwidth burst limits not specified in the config via " + PROP_INBOUND_BANDWIDTH_PEAK);
        }
    }
    private void updateOutboundPeak() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH_PEAK);
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_outboundBurstBytes)))) ) {
            // peak bw was specified *and* changed
            try {
                long out = Long.parseLong(outBwStr);
                if (out >= 0) {
                    _outboundBurstBytes = out * 1024;
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth burst limit [" + outBwStr 
                              + "], keeping as " + _outboundBurstBytes);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Outbound bandwidth burst limits not specified in the config via " + PROP_OUTBOUND_BANDWIDTH_PEAK);
        }
    }
    
    private void updateReplenishFrequency() {
        String freqMs = _context.getProperty(PROP_REPLENISH_FREQUENCY);
        if ( (freqMs != null) && 
             (freqMs.trim().length() > 0) && 
             (!(freqMs.equals(String.valueOf(_replenishFrequency)))) ) {
            // frequency was specified *and* changed
            try {
                long ms = Long.parseLong(freqMs);
                if (ms >= 0) {
                    _replenishFrequency = ms;
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid replenish frequency [" + freqMs
                              + "], keeping as " + _replenishFrequency);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Replenish frequency not specified in the config via " + PROP_REPLENISH_FREQUENCY);
            _replenishFrequency = DEFAULT_REPLENISH_FREQUENCY;
        }
    }
    
    private void updateMinNonZeroDelay() {
        String delayMs = _context.getProperty(PROP_MIN_NON_ZERO_DELAY);
        if ( (delayMs != null) && 
             (delayMs.trim().length() > 0) && 
             (!(delayMs.equals(String.valueOf(_minNonZeroDelay)))) ) {
            // delay was specified *and* changed
            try {
                long ms = Long.parseLong(delayMs);
                if (ms >= 0) {
                    _minNonZeroDelay = ms;
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid minimum nonzero delay [" + delayMs
                              + "], keeping as " + _minNonZeroDelay);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Minimum nonzero delay not specified in the config via " + PROP_MIN_NON_ZERO_DELAY);
            _minNonZeroDelay = DEFAULT_MIN_NON_ZERO_DELAY;
        }
    }
    
    public void reinitialize() {
        _inboundAvailable = 0;
        _inboundBurstBytes = 0;
        _inboundKBytesPerSecond = -1;
        _lastResync = _context.clock().now();
        _lastUpdateLimits = -1;
        _minNonZeroDelay = DEFAULT_MIN_NON_ZERO_DELAY;
        _outboundAvailable = 0;
        _outboundBurstBytes = 0;
        _outboundKBytesPerSecond = -1;
        _replenishFrequency = DEFAULT_REPLENISH_FREQUENCY;
        _totalInboundBytes = 0;
        _totalOutboundBytes = 0;
        updateLimits();
        updateBW();
    }
    
    private void updateBW() {
        long now = _context.clock().now();
        long numMs = (now - _lastResync);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updating bandwidth after " + numMs + " (available in=" + _inboundAvailable + ", out=" + _outboundAvailable + ", rate in=" + _inboundKBytesPerSecond + ", out=" + _outboundKBytesPerSecond  +")");
        if (numMs > 1000) {
            long inboundToAdd = 1024*_inboundKBytesPerSecond * (numMs/1000);
            long outboundToAdd = 1024*_outboundKBytesPerSecond * (numMs/1000);

            if (inboundToAdd < 0) inboundToAdd = 0;
            if (outboundToAdd < 0) outboundToAdd = 0;

            _inboundAvailable += inboundToAdd;
            _outboundAvailable += outboundToAdd;

            if (_inboundAvailable > _inboundBurstBytes)
                _inboundAvailable = _inboundBurstBytes;
            if (_outboundAvailable > _outboundBurstBytes)
                _outboundAvailable = _outboundBurstBytes;

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable (current: " + _inboundAvailable + ")");
                _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable (current: " + _outboundAvailable + ")");
            }

            if (inboundToAdd > 0) synchronized (_inboundWaitLock) { _inboundWaitLock.notify(); }
            if (outboundToAdd > 0) synchronized (_outboundWaitLock) { _outboundWaitLock.notify(); }
        }
        _lastResync = now;
    }
    
    private class UpdateBWRunner implements Runnable {
        public void run() {
            while (true) {
                try {
                    synchronized (_updateBwLock) {
                        _updateBwLock.wait(_replenishFrequency);
                    }
                } catch (InterruptedException ie) {}
                try {
                    updateBW();
                    if (_context.clock().now() > _lastUpdateLimits + UPDATE_LIMIT_FREQUENCY)
                        updateLimits();
                } catch (Exception e) {
                    _log.log(Log.CRIT, "Error updating bandwidth!", e);
                }
            }
        }
    }
}
