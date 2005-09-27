package net.i2p.router.transport;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

class FIFOBandwidthRefiller implements Runnable {
    private Log _log;
    private I2PAppContext _context;
    private FIFOBandwidthLimiter _limiter;
    /** how many KBps do we want to allow? */
    private int _inboundKBytesPerSecond;
    /** how many KBps do we want to allow? */
    private int _outboundKBytesPerSecond;
    /** how many KBps do we want to allow during burst? */
    private int _inboundBurstKBytesPerSecond;
    /** how many KBps do we want to allow during burst? */
    private int _outboundBurstKBytesPerSecond;
    /** when did we last replenish the queue? */
    private long _lastRefillTime;
    /** when did we last check the config for updates? */
    private long _lastCheckConfigTime;
    /** how frequently do we check the config for updates? */
    private long _configCheckPeriodMs = 60*1000;
 
    public static final String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BURST_BANDWIDTH = "i2np.bandwidth.inboundBurstKBytesPerSecond";
    public static final String PROP_OUTBOUND_BURST_BANDWIDTH = "i2np.bandwidth.outboundBurstKBytesPerSecond";
    public static final String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    //public static final String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequencyMs";

    // no longer allow unlimited bandwidth - the user must specify a value, and if they do not, it is 16KBps
    public static final int DEFAULT_INBOUND_BANDWIDTH = 16;
    public static final int DEFAULT_OUTBOUND_BANDWIDTH = 16;
    public static final int DEFAULT_INBOUND_BURST_BANDWIDTH = 16;
    public static final int DEFAULT_OUTBOUND_BURST_BANDWIDTH = 16;

    public static final int DEFAULT_BURST_SECONDS = 60;
    
    /** For now, until there is some tuning and safe throttling, we set the floor at 3KBps inbound */
    public static final int MIN_INBOUND_BANDWIDTH = 3;
    /** For now, until there is some tuning and safe throttling, we set the floor at 3KBps outbound */
    public static final int MIN_OUTBOUND_BANDWIDTH = 3;
    /** For now, until there is some tuning and safe throttling, we set the floor at a 3KBps during burst */
    public static final int MIN_INBOUND_BANDWIDTH_PEAK = 3;
    /** For now, until there is some tuning and safe throttling, we set the floor at a 3KBps during burst */
    public static final int MIN_OUTBOUND_BANDWIDTH_PEAK = 3;
    
    /** 
     * how often we replenish the queues.  
     * the bandwidth limiter is configured to expect an update 10 times per second 
     */
    private static final long REPLENISH_FREQUENCY = 100;
    
    public FIFOBandwidthRefiller(I2PAppContext context, FIFOBandwidthLimiter limiter) {
        _limiter = limiter;
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthRefiller.class);
        reinitialize();
    }
    public void run() {
        // bootstrap 'em with nothing
        _lastRefillTime = _limiter.now();
        while (true) {
            long now = _limiter.now();
            if (now >= _lastCheckConfigTime + _configCheckPeriodMs) {
                checkConfig();
                now = _limiter.now();
                _lastCheckConfigTime = now;
            }
            
            boolean updated = updateQueues(now);
            if (updated) {
                _lastRefillTime = now;
            }
            
            try { Thread.sleep(REPLENISH_FREQUENCY); } catch (InterruptedException ie) {}
        }
    }
    
    public void reinitialize() {
        _lastRefillTime = _limiter.now();
        checkConfig();
        _lastCheckConfigTime = _lastRefillTime;
    }
    
    private boolean updateQueues(long now) {
        long numMs = (now - _lastRefillTime);
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating bandwidth after " + numMs + " (status: " + _limiter.getStatus().toString()
                       + " rate in=" 
                       + _inboundKBytesPerSecond + ", out=" 
                       + _outboundKBytesPerSecond  +")");
        if (numMs >= REPLENISH_FREQUENCY) {
            long inboundToAdd = (1024*_inboundKBytesPerSecond * numMs)/1000;
            long outboundToAdd = (1024*_outboundKBytesPerSecond * numMs)/1000;

            if (inboundToAdd < 0) inboundToAdd = 0;
            if (outboundToAdd < 0) outboundToAdd = 0;

            if (_inboundKBytesPerSecond <= 0) {
                _limiter.setInboundUnlimited(true);
                inboundToAdd = 0;
            } else {
                _limiter.setInboundUnlimited(false);
            }
            if (_outboundKBytesPerSecond <= 0) {
                _limiter.setOutboundUnlimited(true);
                outboundToAdd = 0;
            } else {
                _limiter.setOutboundUnlimited(false);
            }
            
            long maxBurstIn = ((_inboundBurstKBytesPerSecond-_inboundKBytesPerSecond)*1024*numMs)/1000;
            long maxBurstOut = ((_outboundBurstKBytesPerSecond-_outboundKBytesPerSecond)*1024*numMs)/1000;
            _limiter.refillBandwidthQueues(inboundToAdd, outboundToAdd, maxBurstIn, maxBurstOut);
            
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable");
                _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable");
            }
            return true;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refresh delay too fast (" + numMs + ")");
            return false;
        }
    }
    
    private void checkConfig() {
        updateInboundRate();
        updateOutboundRate();
        updateInboundBurstRate();
        updateOutboundBurstRate();
        updateInboundPeak();
        updateOutboundPeak();
        
        if (_inboundKBytesPerSecond <= 0) {
            _limiter.setInboundUnlimited(true);
        } else {
            _limiter.setInboundUnlimited(false);
        }
        if (_outboundKBytesPerSecond <= 0) {
            _limiter.setOutboundUnlimited(true);
        } else {
            _limiter.setOutboundUnlimited(false);
        }

    }
    
    private void updateInboundRate() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_inboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if ( (in <= 0) || (in > MIN_INBOUND_BANDWIDTH) ) 
                    _inboundKBytesPerSecond = in;
                else
                    _inboundKBytesPerSecond = MIN_INBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating inbound rate to " + _inboundKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth limit [" + inBwStr 
                              + "], keeping as " + _inboundKBytesPerSecond);
            }
        } else {
            if ( (inBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Inbound bandwidth limits not specified in the config via " + PROP_INBOUND_BANDWIDTH);
        }
        
        if (_inboundKBytesPerSecond <= 0)
            _inboundKBytesPerSecond = DEFAULT_INBOUND_BANDWIDTH;
    }
    private void updateOutboundRate() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH);
        
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_outboundKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int out = Integer.parseInt(outBwStr);
                if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
                    _outboundKBytesPerSecond = out;
                else
                    _outboundKBytesPerSecond = MIN_OUTBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating outbound rate to " + _outboundKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth limit [" + outBwStr 
                              + "], keeping as " + _outboundKBytesPerSecond);
            }
        } else {
            if ( (outBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Outbound bandwidth limits not specified in the config via " + PROP_OUTBOUND_BANDWIDTH);
        }
        
        if (_outboundKBytesPerSecond <= 0)
            _outboundKBytesPerSecond = DEFAULT_OUTBOUND_BANDWIDTH;
    }
    
    private void updateInboundBurstRate() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BURST_BANDWIDTH);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_inboundBurstKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if ( (in <= 0) || (in > MIN_INBOUND_BANDWIDTH) ) 
                    _inboundBurstKBytesPerSecond = in;
                else
                    _inboundBurstKBytesPerSecond = MIN_INBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating inbound burst rate to " + _inboundBurstKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth burst limit [" + inBwStr 
                              + "], keeping as " + _inboundBurstKBytesPerSecond);
            }
        } else {
            if ( (inBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Inbound bandwidth burst limits not specified in the config via " + PROP_INBOUND_BURST_BANDWIDTH);
        }
        
        if (_inboundBurstKBytesPerSecond <= 0)
            _inboundBurstKBytesPerSecond = DEFAULT_INBOUND_BURST_BANDWIDTH;
        _limiter.setInboundBurstKBps(_inboundBurstKBytesPerSecond);
    }
    
    private void updateOutboundBurstRate() {
        String outBwStr = _context.getProperty(PROP_OUTBOUND_BURST_BANDWIDTH);
        
        if ( (outBwStr != null) && 
             (outBwStr.trim().length() > 0) && 
             (!(outBwStr.equals(String.valueOf(_outboundBurstKBytesPerSecond)))) ) {
            // bandwidth was specified *and* changed
            try {
                int out = Integer.parseInt(outBwStr);
                if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
                    _outboundBurstKBytesPerSecond = out;
                else
                    _outboundBurstKBytesPerSecond = MIN_OUTBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating outbound burst rate to " + _outboundBurstKBytesPerSecond);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth burst limit [" + outBwStr 
                              + "], keeping as " + _outboundBurstKBytesPerSecond);
            }
        } else {
            if ( (outBwStr == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("Outbound bandwidth burst limits not specified in the config via " + PROP_OUTBOUND_BURST_BANDWIDTH);
        }
        
        if (_outboundBurstKBytesPerSecond <= 0)
            _outboundBurstKBytesPerSecond = DEFAULT_OUTBOUND_BURST_BANDWIDTH;
        _limiter.setOutboundBurstKBps(_outboundBurstKBytesPerSecond);
    }
    
    private void updateInboundPeak() {
        String inBwStr = _context.getProperty(PROP_INBOUND_BANDWIDTH_PEAK);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_limiter.getInboundBurstBytes())))) ) {
            // peak bw was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if (in >= MIN_INBOUND_BANDWIDTH_PEAK) {
                    if (in < _inboundBurstKBytesPerSecond)
                        _limiter.setInboundBurstBytes(_inboundBurstKBytesPerSecond * 1024);
                    else 
                        _limiter.setInboundBurstBytes(in * 1024);
                } else {
                    if (MIN_INBOUND_BANDWIDTH_PEAK < _inboundBurstKBytesPerSecond) 
                        _limiter.setInboundBurstBytes(_inboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setInboundBurstBytes(MIN_INBOUND_BANDWIDTH_PEAK * 1024);
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid inbound bandwidth burst limit [" + inBwStr 
                              + "]");
                _limiter.setInboundBurstBytes(DEFAULT_BURST_SECONDS * _inboundBurstKBytesPerSecond * 1024);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Inbound bandwidth burst limits not specified in the config via " 
                           + PROP_INBOUND_BANDWIDTH_PEAK);
            _limiter.setInboundBurstBytes(DEFAULT_BURST_SECONDS * _inboundBurstKBytesPerSecond * 1024);
        }
    }
    private void updateOutboundPeak() {
        String inBwStr = _context.getProperty(PROP_OUTBOUND_BANDWIDTH_PEAK);
        if ( (inBwStr != null) && 
             (inBwStr.trim().length() > 0) && 
             (!(inBwStr.equals(String.valueOf(_limiter.getOutboundBurstBytes())))) ) {
            // peak bw was specified *and* changed
            try {
                int in = Integer.parseInt(inBwStr);
                if (in >= MIN_OUTBOUND_BANDWIDTH_PEAK) {
                    if (in < _outboundBurstKBytesPerSecond)
                        _limiter.setOutboundBurstBytes(_outboundBurstKBytesPerSecond * 1024);
                    else 
                        _limiter.setOutboundBurstBytes(in * 1024);
                } else {
                    if (MIN_OUTBOUND_BANDWIDTH_PEAK < _outboundBurstKBytesPerSecond) 
                        _limiter.setOutboundBurstBytes(_outboundBurstKBytesPerSecond * 1024);
                    else
                        _limiter.setOutboundBurstBytes(MIN_OUTBOUND_BANDWIDTH_PEAK * 1024);
                }
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid outbound bandwidth burst limit [" + inBwStr 
                              + "]");
                _limiter.setOutboundBurstBytes(DEFAULT_BURST_SECONDS * _outboundBurstKBytesPerSecond * 1024);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Outbound bandwidth burst limits not specified in the config via " 
                           + PROP_OUTBOUND_BANDWIDTH_PEAK);
            _limiter.setOutboundBurstBytes(DEFAULT_BURST_SECONDS * _outboundBurstKBytesPerSecond * 1024);
        }
    }
}