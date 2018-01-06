package net.i2p.router.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.transport.FIFOBandwidthLimiter.Request;
import net.i2p.util.Log;

/**
 *  Thread that runs several times a second to "give" bandwidth to
 *  FIFOBandwidthLimiter.
 *  Instantiated by FIFOBandwidthLimiter.
 *
 *  As of 0.8.12, this also contains a counter for outbound participating bandwidth.
 *  This was a good place for it since we needed a thread for it.
 *
 *  Public only for the properties and defaults.
 */
public class FIFOBandwidthRefiller implements Runnable {
    private final Log _log;
    private final I2PAppContext _context;
    private final FIFOBandwidthLimiter _limiter;
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
    private volatile boolean _isRunning;
 
    public static final String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BURST_BANDWIDTH = "i2np.bandwidth.inboundBurstKBytesPerSecond";
    public static final String PROP_OUTBOUND_BURST_BANDWIDTH = "i2np.bandwidth.outboundBurstKBytesPerSecond";
    public static final String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    //public static final String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequencyMs";

    // no longer allow unlimited bandwidth - the user must specify a value, else use defaults below (KBps)
    public static final int DEFAULT_INBOUND_BANDWIDTH = 300;
    /**
     *  Caution, do not make DEFAULT_OUTBOUND_BANDWIDTH * DEFAULT_SHARE_PCT &gt; 32
     *  without thinking about the implications (default connection limits, for example)
     *  of moving the default bandwidth class from L to M, or maybe
     *  adjusting bandwidth class boundaries.
     */
    public static final int DEFAULT_OUTBOUND_BANDWIDTH = 60;
    public static final int DEFAULT_INBOUND_BURST_BANDWIDTH = 300;
    public static final int DEFAULT_OUTBOUND_BURST_BANDWIDTH = 60;

    public static final int DEFAULT_BURST_SECONDS = 60;
    
    /** For now, until there is some tuning and safe throttling, we set the floor at this inbound (KBps) */
    public static final int MIN_INBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this outbound (KBps) */
    public static final int MIN_OUTBOUND_BANDWIDTH = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this during burst (KBps) */
    public static final int MIN_INBOUND_BANDWIDTH_PEAK = 5;
    /** For now, until there is some tuning and safe throttling, we set the floor at this during burst (KBps) */
    public static final int MIN_OUTBOUND_BANDWIDTH_PEAK = 5;
    /**
     *  Max for reasonable Bloom filter false positive rate.
     *  Do not increase without adding a new Bloom filter size!
     *  See util/DecayingBloomFilter and tunnel/BloomFilterIVValidator.
     */
    public static final int MAX_OUTBOUND_BANDWIDTH = 16384;
    
    /** 
     * how often we replenish the queues.  
     * the bandwidth limiter will get an update this often (ms)
     */
    private static final long REPLENISH_FREQUENCY = 40;
    
    FIFOBandwidthRefiller(I2PAppContext context, FIFOBandwidthLimiter limiter) {
        _limiter = limiter;
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthRefiller.class);
        reinitialize();
        _isRunning = true;
    }

    /** @since 0.8.8 */
    synchronized void shutdown() {
        _isRunning = false;
    }

    public void run() {
        // bootstrap 'em with nothing
        _lastRefillTime = _limiter.now();
        List<FIFOBandwidthLimiter.Request> buffer = new ArrayList<Request>(2);
        while (_isRunning) {
            long now = _limiter.now();
            if (now >= _lastCheckConfigTime + _configCheckPeriodMs) {
                checkConfig();
                now = _limiter.now();
                _lastCheckConfigTime = now;
            }
            
            updateParticipating(now);
            boolean updated = updateQueues(buffer, now);
            if (updated) {
                _lastRefillTime = now;
            }
            
            try { Thread.sleep(REPLENISH_FREQUENCY); } catch (InterruptedException ie) {}
        }
    }
    
    synchronized void reinitialize() {
        _lastRefillTime = _limiter.now();
        checkConfig();
        _lastCheckConfigTime = _lastRefillTime;
    }
    
    private boolean updateQueues(List<FIFOBandwidthLimiter.Request> buffer, long now) {
        long numMs = (now - _lastRefillTime);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updating bandwidth after " + numMs + " (status: " + _limiter.getStatus().toString()
                       + " rate in=" 
                       + _inboundKBytesPerSecond + ", out=" 
                       + _outboundKBytesPerSecond  +")");
        // clock skew
        if (numMs >= REPLENISH_FREQUENCY * 50 || numMs <= 0)
            numMs = REPLENISH_FREQUENCY;
        if (numMs >= REPLENISH_FREQUENCY) {
            long inboundToAdd = (1024*_inboundKBytesPerSecond * numMs)/1000;
            long outboundToAdd = (1024*_outboundKBytesPerSecond * numMs)/1000;

            if (inboundToAdd < 0) inboundToAdd = 0;
            if (outboundToAdd < 0) outboundToAdd = 0;

         /**** Always limited for now
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
         ****/
            
            long maxBurstIn = ((_inboundBurstKBytesPerSecond-_inboundKBytesPerSecond)*1024*numMs)/1000;
            long maxBurstOut = ((_outboundBurstKBytesPerSecond-_outboundKBytesPerSecond)*1024*numMs)/1000;
            _limiter.refillBandwidthQueues(buffer, inboundToAdd, outboundToAdd, maxBurstIn, maxBurstOut);
            
            //if (_log.shouldLog(Log.DEBUG)) {
            //    _log.debug("Adding " + inboundToAdd + " bytes to inboundAvailable");
            //    _log.debug("Adding " + outboundToAdd + " bytes to outboundAvailable");
            //}
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
        
        // We are always limited for now
        //_limiter.setInboundUnlimited(_inboundKBytesPerSecond <= 0);
        //_limiter.setOutboundUnlimited(_outboundKBytesPerSecond <= 0);
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
                if (out >= MAX_OUTBOUND_BANDWIDTH)
                    _outboundKBytesPerSecond = MAX_OUTBOUND_BANDWIDTH;
                else if ( (out <= 0) || (out >= MIN_OUTBOUND_BANDWIDTH) )
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
        if (_inboundBurstKBytesPerSecond < _inboundKBytesPerSecond)
            _inboundBurstKBytesPerSecond = _inboundKBytesPerSecond;
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
        if (_outboundBurstKBytesPerSecond < _outboundKBytesPerSecond)
            _outboundBurstKBytesPerSecond = _outboundKBytesPerSecond;
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

    /**
     *  Participating counter stuff below here
     *  TOTAL_TIME needs to be high enough to get a burst without dropping
     *  @since 0.8.12
     */
    private static final int TOTAL_TIME = 4000;
    private static final int PERIODS = TOTAL_TIME / (int) REPLENISH_FREQUENCY;
    /** count in current replenish period */
    private final AtomicInteger _currentParticipating = new AtomicInteger();
    private long _lastPartUpdateTime;
    private int _lastTotal;
    /** the actual length of last total period as coalesced (nominally TOTAL_TIME) */
    private long _lastTotalTime;
    private int _lastIndex;
    /** buffer of count per replenish period, last is at _lastIndex, older at higher indexes (wraps) */
    private final int[] _counts = new int[PERIODS];
    /** the actual length of the period (nominally REPLENISH_FREQUENCY) */
    private final long[] _times = new long[PERIODS];
    private final ReentrantReadWriteLock _updateLock = new ReentrantReadWriteLock(false);

    /**
     *  We sent a message.
     *
     *  @param size bytes
     *  @since 0.8.12
     */
    void incrementParticipatingMessageBytes(int size) {
        _currentParticipating.addAndGet(size);
    }

    /**
     *  Out bandwidth. Actual bandwidth, not smoothed, not bucketed.
     *
     *  @return Bps in recent period (a few seconds)
     *  @since 0.8.12
     */
    int getCurrentParticipatingBandwidth() {
        _updateLock.readLock().lock();
        try {
            return locked_getCurrentParticipatingBandwidth();
        } finally {
            _updateLock.readLock().unlock();
        }
    }

    private int locked_getCurrentParticipatingBandwidth() {
        int current = _currentParticipating.get();
        long totalTime = (_limiter.now() - _lastPartUpdateTime) + _lastTotalTime;
        if (totalTime <= 0)
            return 0;
        // 1000 for ms->seconds in denominator
        long bw = 1000l * (current + _lastTotal) / totalTime;
        if (bw > Integer.MAX_VALUE)
            return 0;
        return (int) bw;
    }

    /**
     *  Run once every replenish period
     *
     *  @since 0.8.12
     */
    private void updateParticipating(long now) {
        _updateLock.writeLock().lock();
        try {
            locked_updateParticipating(now);
        } finally {
            _updateLock.writeLock().unlock();
        }
    }

    private void locked_updateParticipating(long now) {
        long elapsed = now - _lastPartUpdateTime;
        if (elapsed <= 0) {
            // glitch in the matrix
            _lastPartUpdateTime = now;
            return;
        }
        _lastPartUpdateTime = now;
        if (--_lastIndex < 0)
            _lastIndex = PERIODS - 1;
        _counts[_lastIndex] = _currentParticipating.getAndSet(0);
        _times[_lastIndex] = elapsed;
        _lastTotal = 0;
        _lastTotalTime = 0;
        // add up total counts and times
        for (int i = 0; i < PERIODS; i++) {
            int idx = (_lastIndex + i) % PERIODS;
             _lastTotal += _counts[idx];
             _lastTotalTime += _times[idx];
             if (_lastTotalTime >= TOTAL_TIME)
                 break;
        }
        if (_lastIndex == 0 && _lastTotalTime > 0) {
            long bw = 1000l * _lastTotal / _lastTotalTime;
            _context.statManager().addRateData("tunnel.participatingBandwidthOut", bw);
            if (_lastTotal > 0 && _log.shouldLog(Log.INFO))
                _log.info(DataHelper.formatSize(_lastTotal) + " bytes out part. tunnels in last " + _lastTotalTime + " ms: " +
                          DataHelper.formatSize(bw) + " Bps");
        }
    }
}
