package net.i2p.router.transport;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

public class FIFOBandwidthRefiller implements Runnable {
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

    // no longer allow unlimited bandwidth - the user must specify a value, else use defaults below (KBps)
    public static final int DEFAULT_INBOUND_BANDWIDTH = 96;
    /**
     *  Caution, do not make DEFAULT_OUTBOUND_BANDWIDTH * DEFAULT_SHARE_PCT > 32
     *  without thinking about the implications (default connection limits, for example)
     *  of moving the default bandwidth class from L to M, or maybe
     *  adjusting bandwidth class boundaries.
     */
    public static final int DEFAULT_OUTBOUND_BANDWIDTH = 40;
    public static final int DEFAULT_INBOUND_BURST_BANDWIDTH = 80;
    public static final int DEFAULT_OUTBOUND_BURST_BANDWIDTH = 40;

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
        List buffer = new ArrayList(2);
        while (true) {
            long now = _limiter.now();
            if (now >= _lastCheckConfigTime + _configCheckPeriodMs) {
                checkConfig();
                now = _limiter.now();
                _lastCheckConfigTime = now;
            }
            
            boolean updated = updateQueues(buffer, now);
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
    
    private boolean updateQueues(List buffer, long now) {
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
            _limiter.refillBandwidthQueues(buffer, inboundToAdd, outboundToAdd, maxBurstIn, maxBurstOut);
            
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable");
                _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable");
            }
            return true;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refresh delay too fast (" + numMs + ")");
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
        int in = _context.getProperty(PROP_INBOUND_BANDWIDTH, DEFAULT_INBOUND_BANDWIDTH);
        if (in != _inboundKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (in <= 0) || (in > MIN_INBOUND_BANDWIDTH) ) 
                    _inboundKBytesPerSecond = in;
                else
                    _inboundKBytesPerSecond = MIN_INBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating inbound rate to " + _inboundKBytesPerSecond);
        }
        
        if (_inboundKBytesPerSecond <= 0)
            _inboundKBytesPerSecond = DEFAULT_INBOUND_BANDWIDTH;
    }
    private void updateOutboundRate() {
        int out = _context.getProperty(PROP_OUTBOUND_BANDWIDTH, DEFAULT_OUTBOUND_BANDWIDTH);
        if (out != _outboundKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
                    _outboundKBytesPerSecond = out;
                else
                    _outboundKBytesPerSecond = MIN_OUTBOUND_BANDWIDTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating outbound rate to " + _outboundKBytesPerSecond);
        }
        
        if (_outboundKBytesPerSecond <= 0)
            _outboundKBytesPerSecond = DEFAULT_OUTBOUND_BANDWIDTH;
    }
    
    private void updateInboundBurstRate() {
        int in = _context.getProperty(PROP_INBOUND_BURST_BANDWIDTH, DEFAULT_INBOUND_BURST_BANDWIDTH);
        if (in != _inboundBurstKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (in <= 0) || (in >= _inboundKBytesPerSecond) ) 
                    _inboundBurstKBytesPerSecond = in;
                else
                    _inboundBurstKBytesPerSecond = _inboundKBytesPerSecond;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating inbound burst rate to " + _inboundBurstKBytesPerSecond);
        }
        
        if (_inboundBurstKBytesPerSecond <= 0)
            _inboundBurstKBytesPerSecond = DEFAULT_INBOUND_BURST_BANDWIDTH;
        _limiter.setInboundBurstKBps(_inboundBurstKBytesPerSecond);
    }
    
    private void updateOutboundBurstRate() {
        int out = _context.getProperty(PROP_OUTBOUND_BURST_BANDWIDTH, DEFAULT_OUTBOUND_BURST_BANDWIDTH);
        if (out != _outboundBurstKBytesPerSecond) {
            // bandwidth was specified *and* changed
                if ( (out <= 0) || (out >= _outboundKBytesPerSecond) )
                    _outboundBurstKBytesPerSecond = out;
                else
                    _outboundBurstKBytesPerSecond = _outboundKBytesPerSecond;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating outbound burst rate to " + _outboundBurstKBytesPerSecond);
        }
        
        if (_outboundBurstKBytesPerSecond <= 0)
            _outboundBurstKBytesPerSecond = DEFAULT_OUTBOUND_BURST_BANDWIDTH;
        _limiter.setOutboundBurstKBps(_outboundBurstKBytesPerSecond);
    }
    
    private void updateInboundPeak() {
        int in = _context.getProperty(PROP_INBOUND_BANDWIDTH_PEAK,
                                      DEFAULT_BURST_SECONDS * _inboundBurstKBytesPerSecond);
        if (in != _limiter.getInboundBurstBytes()) {
            // peak bw was specified *and* changed
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
        }
    }
    private void updateOutboundPeak() {
        int in = _context.getProperty(PROP_OUTBOUND_BANDWIDTH_PEAK,
                                      DEFAULT_BURST_SECONDS * _outboundBurstKBytesPerSecond);
        if (in != _limiter.getOutboundBurstBytes()) {
            // peak bw was specified *and* changed
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
        }
    }
    
    int getOutboundKBytesPerSecond() { return _outboundKBytesPerSecond; } 
    int getInboundKBytesPerSecond() { return _inboundKBytesPerSecond; } 
    int getOutboundBurstKBytesPerSecond() { return _outboundBurstKBytesPerSecond; } 
    int getInboundBurstKBytesPerSecond() { return _inboundBurstKBytesPerSecond; } 
}
