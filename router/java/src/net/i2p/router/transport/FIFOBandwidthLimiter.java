package net.i2p.router.transport;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *  Concurrent plan:
 *
 *  It's difficult to get rid of the locks on _pendingInboundRequests
 *  since locked_satisyInboundAvailable() leaves Requests on the head
 *  of the queue.
 *
 *  When we go to Java 6, we can convert from a locked ArrayList to
 *  a LinkedBlockingDeque, where locked_sIA will poll() from the
 *  head of the queue, and if the request is not fully satisfied,
 *  offerFirst() (i.e. push) it back on the head.
 *
 *  Ditto outbound of course.
 *
 *  In the meantime, for Java 5, we have lockless 'shortcut'
 *  methods for the common case where we are under the bandwidth limits.
 *  And the volatile counters are now AtomicIntegers / AtomicLongs.
 *
 */
public class FIFOBandwidthLimiter {
    private final Log _log;
    private final I2PAppContext _context;
    private final List<Request> _pendingInboundRequests;
    private final List<Request> _pendingOutboundRequests;
    /** how many bytes we can consume for inbound transmission immediately */
    private final AtomicInteger _availableInbound = new AtomicInteger();
    /** how many bytes we can consume for outbound transmission immediately */
    private final AtomicInteger _availableOutbound = new AtomicInteger();
    /** how many bytes we can queue up for bursting */
    private final AtomicInteger _unavailableInboundBurst = new AtomicInteger();
    /** how many bytes we can queue up for bursting */
    private final AtomicInteger _unavailableOutboundBurst = new AtomicInteger();
    /** how large _unavailableInbound can get */
    private int _maxInboundBurst;
    /** how large _unavailableInbound can get */
    private int _maxOutboundBurst;
    /** how large _availableInbound can get - aka our inbound rate duringa burst */
    private int _maxInbound;
    /** how large _availableOutbound can get - aka our outbound rate during a burst */
    private int _maxOutbound;
    /** shortcut of whether our outbound rate is unlimited */
    private boolean _outboundUnlimited;
    /** shortcut of whether our inbound rate is unlimited */
    private boolean _inboundUnlimited;
    /** lifetime counter of bytes received */
    private final AtomicLong _totalAllocatedInboundBytes = new AtomicLong();
    /** lifetime counter of bytes sent */
    private final AtomicLong _totalAllocatedOutboundBytes = new AtomicLong();
    /** lifetime counter of tokens available for use but exceeded our maxInboundBurst size */
    private final AtomicLong _totalWastedInboundBytes = new AtomicLong();
    /** lifetime counter of tokens available for use but exceeded our maxOutboundBurst size */
    private final AtomicLong _totalWastedOutboundBytes = new AtomicLong();
    private final FIFOBandwidthRefiller _refiller;
    
    private long _lastTotalSent;
    private long _lastTotalReceived;
    private long _lastStatsUpdated;
    private float _sendBps;
    private float _recvBps;
    private float _sendBps15s;
    private float _recvBps15s;
    
    public /* static */ long now() {
        // dont use the clock().now(), since that may jump
        return System.currentTimeMillis(); 
    }
    
    public FIFOBandwidthLimiter(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthLimiter.class);
        _context.statManager().createRateStat("bwLimiter.pendingOutboundRequests", "How many outbound requests are ahead of the current one (ignoring ones with 0)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.pendingInboundRequests", "How many inbound requests are ahead of the current one (ignoring ones with 0)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.outboundDelayedTime", "How long it takes to honor an outbound request (ignoring ones with that go instantly)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.inboundDelayedTime", "How long it takes to honor an inbound request (ignoring ones with that go instantly)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        if (_log.shouldLog(Log.WARN)) {
            // If you want to see these you better have the logging set at startup!
            _context.statManager().createRateStat("bw.sendBps1s", "How fast we are transmitting for the 1s quantization (period is the number of bytes transmitted)?", "Bandwidth", new long[] { 60*1000l, 10*60*1000l });
            _context.statManager().createRateStat("bw.recvBps1s", "How fast we are receiving for the 1s quantization (period is the number of bytes transmitted)?", "Bandwidth", new long[] { 60*1000l, 10*60*1000l });
            _context.statManager().createRateStat("bw.sendBps15s", "How fast we are transmitting for the 15s quantization (period is the number of bytes transmitted)?", "Bandwidth", new long[] { 60*1000l, 10*60*1000l });
            _context.statManager().createRateStat("bw.recvBps15s", "How fast we are receiving for the 15s quantization (period is the number of bytes transmitted)?", "Bandwidth", new long[] { 60*1000l, 10*60*1000l });
        }
        _pendingInboundRequests = new ArrayList(16);
        _pendingOutboundRequests = new ArrayList(16);
        _lastTotalSent = _totalAllocatedOutboundBytes.get();
        _lastTotalReceived = _totalAllocatedInboundBytes.get();
        _lastStatsUpdated = now();
        _refiller = new FIFOBandwidthRefiller(_context, this);
        I2PThread t = new I2PThread(_refiller, "BWRefiller", true);
        t.setPriority(I2PThread.NORM_PRIORITY-1);
        t.start();
    }

    //public long getAvailableInboundBytes() { return _availableInboundBytes; }
    //public long getAvailableOutboundBytes() { return _availableOutboundBytes; }
    public long getTotalAllocatedInboundBytes() { return _totalAllocatedInboundBytes.get(); }
    public long getTotalAllocatedOutboundBytes() { return _totalAllocatedOutboundBytes.get(); }
    public long getTotalWastedInboundBytes() { return _totalWastedInboundBytes.get(); }
    public long getTotalWastedOutboundBytes() { return _totalWastedOutboundBytes.get(); }
    //public long getMaxInboundBytes() { return _maxInboundBytes; }
    //public void setMaxInboundBytes(int numBytes) { _maxInboundBytes = numBytes; }
    //public long getMaxOutboundBytes() { return _maxOutboundBytes; }
    //public void setMaxOutboundBytes(int numBytes) { _maxOutboundBytes = numBytes; }
    public boolean getInboundUnlimited() { return _inboundUnlimited; }
    public void setInboundUnlimited(boolean isUnlimited) { _inboundUnlimited = isUnlimited; }
    public boolean getOutboundUnlimited() { return _outboundUnlimited; }
    public void setOutboundUnlimited(boolean isUnlimited) { _outboundUnlimited = isUnlimited; }
    public float getSendBps() { return _sendBps; }
    public float getReceiveBps() { return _recvBps; }
    public float getSendBps15s() { return _sendBps15s; }
    public float getReceiveBps15s() { return _recvBps15s; }
    
    /** These are the configured maximums, not the current rate */
    public int getOutboundKBytesPerSecond() { return _refiller.getOutboundKBytesPerSecond(); } 
    public int getInboundKBytesPerSecond() { return _refiller.getInboundKBytesPerSecond(); } 
    public int getOutboundBurstKBytesPerSecond() { return _refiller.getOutboundBurstKBytesPerSecond(); } 
    public int getInboundBurstKBytesPerSecond() { return _refiller.getInboundBurstKBytesPerSecond(); } 
    
    public void reinitialize() {
        _pendingInboundRequests.clear();
        _pendingOutboundRequests.clear();
        _availableInbound.set(0);
        _availableOutbound.set(0);
        _maxInbound = 0;
        _maxOutbound = 0;
        _maxInboundBurst = 0;
        _maxOutboundBurst = 0;
        _unavailableInboundBurst.set(0);
        _unavailableOutboundBurst.set(0);
        _inboundUnlimited = false;
        _outboundUnlimited = false;
        _refiller.reinitialize();
    }
    
    public Request createRequest() { return new SimpleRequest(); }

    /**
     * Request some bytes. Does not block.
     */
    public Request requestInbound(int bytesIn, String purpose) {
        // try to satisfy without grabbing the global lock
        if (shortcutSatisfyInboundRequest(bytesIn))
            return _noop;
        return requestInbound(bytesIn, purpose, null, null);
    }

    public Request requestInbound(int bytesIn, String purpose, CompleteListener lsnr, Object attachment) {
        SimpleRequest req = new SimpleRequest(bytesIn, 0, purpose, lsnr, attachment);
        requestInbound(req, bytesIn, purpose);
        return req;
    }

    /**
     * The transports don't use this any more, so make it private
     * and a SimpleRequest instead of a Request
     * So there's no more casting
     */
    private void requestInbound(SimpleRequest req, int bytesIn, String purpose) {
        // don't init twice - uncomment if we make public again?
        //req.init(bytesIn, 0, purpose);
        int pending;
        synchronized (_pendingInboundRequests) {
            pending = _pendingInboundRequests.size();
            _pendingInboundRequests.add(req);
        }
        satisfyInboundRequests(req.satisfiedBuffer);
        req.satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingInboundRequests", pending, pending);
    }

    /**
     * Request some bytes. Does not block.
     */
    public Request requestOutbound(int bytesOut, String purpose) {
        // try to satisfy without grabbing the global lock
        if (shortcutSatisfyOutboundRequest(bytesOut))
            return _noop;
        return requestOutbound(bytesOut, purpose, null, null);
    }

    public Request requestOutbound(int bytesOut, String purpose, CompleteListener lsnr, Object attachment) {
        SimpleRequest req = new SimpleRequest(0, bytesOut, purpose, lsnr, attachment);
        requestOutbound(req, bytesOut, purpose);
        return req;
    }

    private void requestOutbound(SimpleRequest req, int bytesOut, String purpose) {
        // don't init twice - uncomment if we make public again?
        //req.init(0, bytesOut, purpose);
        int pending;
        synchronized (_pendingOutboundRequests) {
            pending = _pendingOutboundRequests.size();
            _pendingOutboundRequests.add(req);
        }
        satisfyOutboundRequests(req.satisfiedBuffer);
        req.satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingOutboundRequests", pending, pending);
    }
    
    void setInboundBurstKBps(int kbytesPerSecond) {
        _maxInbound = kbytesPerSecond * 1024;
    }
    void setOutboundBurstKBps(int kbytesPerSecond) {
        _maxOutbound = kbytesPerSecond * 1024;
    }
    public int getInboundBurstBytes() { return _maxInboundBurst; }
    public int getOutboundBurstBytes() { return _maxOutboundBurst; }
    void setInboundBurstBytes(int bytes) { _maxInboundBurst = bytes; }
    void setOutboundBurstBytes(int bytes) { _maxOutboundBurst = bytes; }
    
    StringBuilder getStatus() {
        StringBuilder rv = new StringBuilder(128);
        rv.append("Available: ").append(_availableInbound).append('/').append(_availableOutbound).append(' ');
        rv.append("Max: ").append(_maxInbound).append('/').append(_maxOutbound).append(' ');
        rv.append("Burst: ").append(_unavailableInboundBurst).append('/').append(_unavailableOutboundBurst).append(' ');
        rv.append("Burst max: ").append(_maxInboundBurst).append('/').append(_maxOutboundBurst).append(' ');
        return rv;
    }
    
    /**
     * More bytes are available - add them to the queue and satisfy any requests
     * we can
     *
     * @param maxBurstIn allow up to this many bytes in from the burst section for this time period (may be negative)
     * @param maxBurstOut allow up to this many bytes in from the burst section for this time period (may be negative)
     */
    final void refillBandwidthQueues(List<Request> buf, long bytesInbound, long bytesOutbound, long maxBurstIn, long maxBurstOut) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Refilling the queues with " + bytesInbound + "/" + bytesOutbound + ": " + getStatus().toString());

        // Take some care throughout to minimize accesses to the atomics,
        // both for efficiency and to not let strange things happen if
        // it changes out from under us
        // This never had locks before concurrent, anyway

        int avi = _availableInbound.addAndGet((int) bytesInbound);
        if (avi > _maxInbound) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("available inbound (" + avi + ") exceeds our inbound burst (" + _maxInbound + "), so no supplement");
            int uib = _unavailableInboundBurst.addAndGet(avi - _maxInbound);
            _availableInbound.set(_maxInbound);
            if (uib > _maxInboundBurst) {
                _totalWastedInboundBytes.addAndGet(uib - _maxInboundBurst);
                _unavailableInboundBurst.set(_maxInboundBurst);
            }
        } else {
            // try to pull in up to 1/10th of the burst rate, since we refill every 100ms
            int want = (int)maxBurstIn;
            if (want > (_maxInbound - avi))
                want = _maxInbound - avi;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("want to pull " + want + " from the inbound burst (" + _unavailableInboundBurst + ") to supplement " + avi + " (max: " + _maxInbound + ")");
            
            if (want > 0) {
                int uib = _unavailableInboundBurst.get();
                if (want <= uib) {
                    _availableInbound.addAndGet(want);
                    _unavailableInboundBurst.addAndGet(0 - want);
                } else {
                    _availableInbound.addAndGet(uib);
                    _unavailableInboundBurst.set(0);
                }
            }
        }
        
        int avo = _availableOutbound.addAndGet((int) bytesOutbound);
        if (avo > _maxOutbound) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("available outbound (" + avo + ") exceeds our outbound burst (" + _maxOutbound + "), so no supplement");
            int uob = _unavailableOutboundBurst.getAndAdd(avo - _maxOutbound);
            _availableOutbound.set(_maxOutbound);

            if (uob > _maxOutboundBurst) {
                _totalWastedOutboundBytes.getAndAdd(uob - _maxOutboundBurst);
                _unavailableOutboundBurst.set(_maxOutboundBurst);
            }
        } else {
            // try to pull in up to 1/10th of the burst rate, since we refill every 100ms
            int want = (int)maxBurstOut;
            if (want > (_maxOutbound - avo))
                want = _maxOutbound - avo;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("want to pull " + want + " from the outbound burst (" + _unavailableOutboundBurst + ") to supplement " + avo + " (max: " + _maxOutbound + ")");
            
            if (want > 0) {
                int uob = _unavailableOutboundBurst.get();
                if (want <= uob) {
                    _availableOutbound.addAndGet(want);
                    _unavailableOutboundBurst.addAndGet(0 - want);
                } else {
                    _availableOutbound.addAndGet(uob);
                    _unavailableOutboundBurst.set(0);
                }
            }
        }
        
        satisfyRequests(buf);
        updateStats();
    }
    
    private void updateStats() {
        long now = now();
        long time = now - _lastStatsUpdated;
        // If at least one second has passed
        if (time >= 1000) {
            long totS = _totalAllocatedOutboundBytes.get();
            long totR = _totalAllocatedInboundBytes.get();
            long sent = totS - _lastTotalSent; // How much we sent meanwhile
            long recv = totR - _lastTotalReceived; // How much we received meanwhile
            _lastTotalSent = totS;
            _lastTotalReceived = totR;
            _lastStatsUpdated = now;

            if (_sendBps <= 0)
                _sendBps = ((float)sent*1000f)/(float)time;
            else
                _sendBps = (0.9f)*_sendBps + (0.1f)*((float)sent*1000f)/(float)time;
            if (_recvBps <= 0)
                _recvBps = ((float)recv*1000f)/(float)time;
            else
                _recvBps = (0.9f)*_recvBps + (0.1f)*((float)recv*1000)/(float)time;

            // warning, getStatLog() can be null
            //if (_log.shouldLog(Log.WARN)) {
                //if (_log.shouldLog(Log.INFO))
                //    _log.info("BW: time = " + time + " sent: " + _sendBps + " recv: " + _recvBps);
            //    _context.statManager().getStatLog().addData("bw", "bw.sendBps1s", (long)_sendBps, sent);
            //    _context.statManager().getStatLog().addData("bw", "bw.recvBps1s", (long)_recvBps, recv);
            //}

            // Maintain an approximate average with a 15-second halflife
            // Weights (0.955 and 0.045) are tuned so that transition between two values (e.g. 0..10)
            // would reach their midpoint (e.g. 5) in 15s
            //if (_sendBps15s <= 0)
            //    _sendBps15s = (0.045f)*((float)sent*15*1000f)/(float)time;
            //else
                _sendBps15s = (0.955f)*_sendBps15s + (0.045f)*((float)sent*1000f)/(float)time;

            //if (_recvBps15s <= 0)
            //    _recvBps15s = (0.045f)*((float)recv*15*1000f)/(float)time;
            //else
                _recvBps15s = (0.955f)*_recvBps15s + (0.045f)*((float)recv*1000)/(float)time;

            // warning, getStatLog() can be null
            //if (_log.shouldLog(Log.WARN)) {
            //    if (_log.shouldLog(Log.DEBUG))
            //        _log.debug("BW15: time = " + time + " sent: " + _sendBps + " recv: " + _recvBps);
            //    _context.statManager().getStatLog().addData("bw", "bw.sendBps15s", (long)_sendBps15s, sent);
            //    _context.statManager().getStatLog().addData("bw", "bw.recvBps15s", (long)_recvBps15s, recv);
            //}
        }
    }
    
    /**
     * Go through the queue, satisfying as many requests as possible (notifying
     * each one satisfied that the request has been granted).  
     *
     * @param buffer returned with the satisfied outbound requests only
     */
    private final void satisfyRequests(List<Request> buffer) {
        buffer.clear();
        satisfyInboundRequests(buffer);
        buffer.clear();
        satisfyOutboundRequests(buffer);
    }
    
    private final void satisfyInboundRequests(List<Request> satisfied) {
        synchronized (_pendingInboundRequests) {
            if (_inboundUnlimited) {
                locked_satisfyInboundUnlimited(satisfied);
            } else {
                if (_availableInbound.get() > 0) {
                    locked_satisfyInboundAvailable(satisfied);
                } else {
                    // no bandwidth available
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Still denying the " + _pendingInboundRequests.size() 
                                  + " pending inbound requests (status: " + getStatus().toString()
                                  + ", longest waited " + locked_getLongestInboundWait() + " in)");
                }
            }
        }
        
        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest creq = (SimpleRequest)satisfied.get(i);
                creq.notifyAllocation();
            }
        }
    }
    
    /** called from debug logging only */
    private long locked_getLongestInboundWait() {
        long start = -1;
        for (int i = 0; i < _pendingInboundRequests.size(); i++) {
            SimpleRequest req = (SimpleRequest)_pendingInboundRequests.get(i);
            if ( (start < 0) || (start > req.getRequestTime()) )
                start = req.getRequestTime();
        }
        if (start == -1) 
            return 0;
        else
            return now() - start;
    }

    /** called from debug logging only */
    private long locked_getLongestOutboundWait() {
        long start = -1;
        for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
            SimpleRequest req = (SimpleRequest)_pendingOutboundRequests.get(i);
            if (req == null) continue;
            if ( (start < 0) || (start > req.getRequestTime()) )
                start = req.getRequestTime();
        }
        if (start == -1)
            return 0;
        else
            return now() - start;
    }
    
    /**
     * There are no limits, so just give every inbound request whatever they want
     *
     */
    private final void locked_satisfyInboundUnlimited(List<Request> satisfied) {
        while (!_pendingInboundRequests.isEmpty()) {
            SimpleRequest req = (SimpleRequest)_pendingInboundRequests.remove(0);
            int allocated = req.getPendingInboundRequested();
            _totalAllocatedInboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated, 0);
            satisfied.add(req);
            long waited = now() - req.getRequestTime();
            if (_log.shouldLog(Log.DEBUG))
                 _log.debug("Granting inbound request " + req.getRequestName() + " fully for " 
                            + req.getTotalInboundRequested() + " bytes (waited " 
                            + waited
                            + "ms) pending " + _pendingInboundRequests.size());
            if (waited > 10)
                _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited, waited);
        }
    }
    
    /**
     * ok, we have limits, so lets iterate through the requests, allocating as much
     * bandwidth as we can to those who have used what we have given them and are waiting
     * for more (giving priority to the first ones who requested it)
     * 
     * @return list of requests that were completely satisfied
     */
    private final void locked_satisfyInboundAvailable(List<Request> satisfied) {
        for (int i = 0; i < _pendingInboundRequests.size(); i++) {
            if (_availableInbound.get() <= 0) break;
            SimpleRequest req = (SimpleRequest)_pendingInboundRequests.get(i);
            long waited = now() - req.getRequestTime();
            if (req.getAborted()) {
                // connection decided they dont want the data anymore
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Aborting inbound request to " 
                                + req.getRequestName() + " (total " 
                                + req.getTotalInboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size());
                _pendingInboundRequests.remove(i);
                i--;
                continue;
            }
            if ( (req.getAllocationsSinceWait() > 0) && (req.getCompleteListener() == null) ) {
                // we have already allocated some values to this request, but
                // they haven't taken advantage of it yet (most likely they're
                // IO bound)
                // (also, the complete listener is only set for situations where 
                // waitForNextAllocation() is never called)
                continue;
            }
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingInboundRequested();
            int avi = _availableInbound.get();
            int allocated;
            if (avi >= requested) 
                allocated = requested;
            else
                allocated = avi;
            _availableInbound.addAndGet(0 - allocated);
            _totalAllocatedInboundBytes.addAndGet(allocated);
            req.allocateBytes(allocated, 0);
            satisfied.add(req);
            if (req.getPendingInboundRequested() > 0) {
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Allocating " + allocated + " bytes inbound as a partial grant to " 
                                + req.getRequestName() + " (wanted " 
                                + req.getTotalInboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size()
                                + ", longest waited " + locked_getLongestInboundWait() + " in");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Allocating " + allocated + " bytes inbound to finish the partial grant to " 
                                + req.getRequestName() + " (total " 
                                + req.getTotalInboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingInboundRequests.size()
                                + ", longest waited " + locked_getLongestInboundWait() + " out");
                _pendingInboundRequests.remove(i);
                i--;
                if (waited > 10)
                    _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited, waited);
            }
        }
    }
    
    private final void satisfyOutboundRequests(List<Request> satisfied) {
        synchronized (_pendingOutboundRequests) {
            if (_outboundUnlimited) {
                locked_satisfyOutboundUnlimited(satisfied);
            } else {
                if (_availableOutbound.get() > 0) {
                    locked_satisfyOutboundAvailable(satisfied);
                } else {
                    // no bandwidth available
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Still denying the " + _pendingOutboundRequests.size() 
                                  + " pending outbound requests (status: " + getStatus().toString()
                                  + ", longest waited " + locked_getLongestOutboundWait() + " out)");
                }
            }
        }
        
        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest creq = (SimpleRequest)satisfied.get(i);
                creq.notifyAllocation();
            }
        }
    }
    
    /**
     * There are no limits, so just give every outbound request whatever they want
     *
     */
    private final void locked_satisfyOutboundUnlimited(List<Request> satisfied) {
        while (!_pendingOutboundRequests.isEmpty()) {
            SimpleRequest req = (SimpleRequest)_pendingOutboundRequests.remove(0);
            int allocated = req.getPendingOutboundRequested();
            _totalAllocatedOutboundBytes.addAndGet(allocated);
            req.allocateBytes(0, allocated);
            satisfied.add(req);
            long waited = now() - req.getRequestTime();
            if (_log.shouldLog(Log.DEBUG))
                 _log.debug("Granting outbound request " + req.getRequestName() + " fully for " 
                            + req.getTotalOutboundRequested() + " bytes (waited " 
                            + waited
                            + "ms) pending " + _pendingOutboundRequests.size()
                            + ", longest waited " + locked_getLongestOutboundWait() + " out");
            if (waited > 10)
                _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited, waited);
        }
    }
    
    /**
     * ok, we have limits, so lets iterate through the requests, allocating as much
     * bandwidth as we can to those who have used what we have given them and are waiting
     * for more (giving priority to the first ones who requested it)
     * 
     * @return list of requests that were completely satisfied
     */
    private final void locked_satisfyOutboundAvailable(List<Request> satisfied) {
        for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
            if (_availableOutbound.get() <= 0) break;
            SimpleRequest req = (SimpleRequest)_pendingOutboundRequests.get(i);
            long waited = now() - req.getRequestTime();
            if (req.getAborted()) {
                // connection decided they dont want the data anymore
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Aborting outbound request to " 
                                + req.getRequestName() + " (total " 
                                + req.getTotalOutboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size());
                _pendingOutboundRequests.remove(i);
                i--;
                continue;
            }
            if (req.getAllocationsSinceWait() > 0) {
                // we have already allocated some values to this request, but
                // they haven't taken advantage of it yet (most likely they're
                // IO bound)
                if (_log.shouldLog(Log.WARN))
                    _log.warn("multiple allocations since wait... ntcp shouldn't do this: " + req.getRequestName());
                continue;
            }
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingOutboundRequested();
            int avo = _availableOutbound.get();
            int allocated;
            if (avo >= requested) 
                allocated = requested;
            else
                allocated = avo;
            _availableOutbound.addAndGet(0 - allocated);
            _totalAllocatedOutboundBytes.addAndGet(allocated);
            req.allocateBytes(0, allocated);
            satisfied.add(req);
            if (req.getPendingOutboundRequested() > 0) {
                if (req.attachment() != null) {
                    if (_log.shouldLog(Log.INFO))
                         _log.info("Allocating " + allocated + " bytes outbound as a partial grant to " 
                                    + req.getRequestName() + " (wanted " 
                                    + req.getTotalOutboundRequested() + " bytes, waited " 
                                    + waited
                                    + "ms) pending " + _pendingOutboundRequests.size()
                                    + ", longest waited " + locked_getLongestOutboundWait() + " out");
                }
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Allocating " + allocated + " bytes outbound as a partial grant to " 
                                + req.getRequestName() + " (wanted " 
                                + req.getTotalOutboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size()
                                + ", longest waited " + locked_getLongestOutboundWait() + " out");
            } else {
                if (req.attachment() != null) {
                    if (_log.shouldLog(Log.INFO))
                         _log.info("Allocating " + allocated + " bytes outbound to finish the partial grant to " 
                                    + req.getRequestName() + " (total " 
                                    + req.getTotalOutboundRequested() + " bytes, waited " 
                                    + waited
                                    + "ms) pending " + _pendingOutboundRequests.size()
                                    + ", longest waited " + locked_getLongestOutboundWait() + " out)");
                }
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Allocating " + allocated + " bytes outbound to finish the partial grant to " 
                                + req.getRequestName() + " (total " 
                                + req.getTotalOutboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size()
                                + ", longest waited " + locked_getLongestOutboundWait() + " out)");
                _pendingOutboundRequests.remove(i);
                i--;
                if (waited > 10)
                    _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited, waited);
            }
        }
    }
    
    /**
     *  Lockless total satisfaction,
     *  at some minor risk of exceeding the limits
     *  and driving the available counter below zero
     *
     *  @param requested number of bytes
     *  @return satisfaction
     *  @since 0.7.13
     */
    private boolean shortcutSatisfyInboundRequest(int requested) {
        boolean rv = _inboundUnlimited ||
                     (_pendingInboundRequests.isEmpty() &&
                      _availableInbound.get() >= requested);
        if (rv) {
            _availableInbound.addAndGet(0 - requested);
            _totalAllocatedInboundBytes.addAndGet(requested);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("IB shortcut for " + requested + "B? " + rv);
        return rv;
    }
    
    /**
     *  Lockless total satisfaction,
     *  at some minor risk of exceeding the limits
     *  and driving the available counter below zero
     *
     *  @param requested number of bytes
     *  @return satisfaction
     *  @since 0.7.13
     */
    private boolean shortcutSatisfyOutboundRequest(int requested) {
        boolean rv = _outboundUnlimited ||
                     (_pendingOutboundRequests.isEmpty() &&
                      _availableOutbound.get() >= requested);
        if (rv) {
            _availableOutbound.addAndGet(0 - requested);
            _totalAllocatedOutboundBytes.addAndGet(requested);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("OB shortcut for " + requested + "B? " + rv);
        return rv;
    }

    /** @deprecated not worth translating */
    public void renderStatusHTML(Writer out) throws IOException {
/*******
        long now = now();
        StringBuilder buf = new StringBuilder(4096);
        buf.append("<h3><b id=\"bwlim\">Limiter Status:</b></h3>").append(getStatus().toString()).append("\n");
        buf.append("<h3>Pending bandwidth requests:</h3><ul>");
        buf.append("<li>Inbound requests: <ol>");
        synchronized (_pendingInboundRequests) {
            for (int i = 0; i < _pendingInboundRequests.size(); i++) {
                Request req = (Request)_pendingInboundRequests.get(i);
                buf.append("<li>").append(req.getRequestName()).append(" for ");
                buf.append(req.getTotalInboundRequested()).append(" bytes ");
                buf.append("requested (").append(req.getPendingInboundRequested()).append(" pending) as of ");
                buf.append(now-req.getRequestTime());
                buf.append("ms ago</li>\n");
            }
        }
        buf.append("</ol></li><li>Outbound requests: <ol>\n");
        synchronized (_pendingOutboundRequests) {
            for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
                Request req = (Request)_pendingOutboundRequests.get(i);
                buf.append("<li>").append(req.getRequestName()).append(" for ");
                buf.append(req.getTotalOutboundRequested()).append(" bytes ");
                buf.append("requested (").append(req.getPendingOutboundRequested()).append(" pending) as of ");
                buf.append(now-req.getRequestTime());
                buf.append("ms ago</li>\n");
            }
        }
        buf.append("</ol></li></ul><hr>\n");
        out.write(buf.toString());
        out.flush();
******/
    }
    
    private static long __requestId = 0;
    private final class SimpleRequest implements Request {
        private int _inAllocated;
        private int _inTotal;
        private int _outAllocated;
        private int _outTotal;
        private long _requestId;
        private long _requestTime;
        private String _target;
        private int _allocationsSinceWait;
        private boolean _aborted;
        private boolean _waited;
        final List<Request> satisfiedBuffer;
        private CompleteListener _lsnr;
        private Object _attachment;
        
        public SimpleRequest() {
            satisfiedBuffer = new ArrayList(1);
            init(0, 0, null);
	}
        public SimpleRequest(int in, int out, String target) {
            satisfiedBuffer = new ArrayList(1);
            init(in, out, target);
	}
        public SimpleRequest(int in, int out, String target, CompleteListener lsnr, Object attachment) {
            satisfiedBuffer = new ArrayList(1);
            _lsnr = lsnr;
            _attachment = attachment;
            init(in, out, target);
	}
        public void init(int in, int out, String target) {
            _waited = false;
            _inTotal = in;
            _outTotal = out;
            _inAllocated = 0;
            _outAllocated = 0;
            _aborted = false;
            _target = target;
            satisfiedBuffer.clear();
            _requestId = ++__requestId;
            _requestTime = now();
        }
        public Object getAvailabilityMonitor() { return SimpleRequest.this; }
        public String getRequestName() { return "Req" + _requestId + " to " + _target; }
        public long getRequestTime() { return _requestTime; }
        public int getTotalOutboundRequested() { return _outTotal; }
        public int getPendingOutboundRequested() { return _outTotal - _outAllocated; }
        public int getTotalInboundRequested() { return _inTotal; }
        public int getPendingInboundRequested() { return _inTotal - _inAllocated; }
        public boolean getAborted() { return _aborted; }
        public void abort() { _aborted = true; }
        public CompleteListener getCompleteListener() { return _lsnr; }
        public void setCompleteListener(CompleteListener lsnr) {
            boolean complete = false;
            synchronized (SimpleRequest.this) {
                _lsnr = lsnr;
                if (isComplete()) {
                    complete = true;
                }
            }
            if (complete && lsnr != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("complete listener set AND completed: " + lsnr);
                lsnr.complete(SimpleRequest.this);
            }
        }
        
        private boolean isComplete() { return (_outAllocated >= _outTotal) && (_inAllocated >= _inTotal); }
        
        public void waitForNextAllocation() {
            _waited = true;
            _allocationsSinceWait = 0;
            boolean complete = false;
            try {
                synchronized (SimpleRequest.this) {
                    if (isComplete())
                        complete = true;
                    else
                        SimpleRequest.this.wait();
                }
            } catch (InterruptedException ie) {}
            if (complete && _lsnr != null)
                _lsnr.complete(SimpleRequest.this);
        }
        int getAllocationsSinceWait() { return _waited ? _allocationsSinceWait : 0; }
        void allocateAll() {
            _inAllocated = _inTotal;
            _outAllocated = _outTotal;
            if (_lsnr == null)
                _allocationsSinceWait++;
            if (_log.shouldLog(Log.DEBUG)) _log.debug("allocate all");
            notifyAllocation();
	}
        void allocateBytes(int in, int out) {
            _inAllocated += in;
            _outAllocated += out;
            if (_lsnr == null)
                _allocationsSinceWait++;
            if (isComplete()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("allocate " + in +"/"+ out + " completed, listener=" + _lsnr);
            }
            //notifyAllocation(); // handled within the satisfy* methods
        }
        void notifyAllocation() {
            boolean complete = false;
            synchronized (SimpleRequest.this) {
                if (isComplete())
                    complete = true;
                SimpleRequest.this.notifyAll();
            }
            if (complete && _lsnr != null) {
                _lsnr.complete(SimpleRequest.this);
                if (_log.shouldLog(Log.INFO))
                    _log.info("at completion for " + _inTotal + "/" + _outTotal 
                              + ", recvBps=" + _recvBps + "/"+ _recvBps15s + " listener is " + _lsnr);
            }
        }
        public void attach(Object obj) { _attachment = obj; }
        public Object attachment() { return _attachment; }
        @Override
        public String toString() { return getRequestName(); }
    }

    /**
     *  This is somewhat complicated by having both
     *  inbound and outbound in a single request.
     *  Making a request unidirectional would
     *  be a good simplification.
     *  But NTCP would have to be changed as it puts them on one queue.
     */
    public interface Request {
        /** describe this particular request */
        public String getRequestName();
        /** when was the request made? */
        public long getRequestTime();
        /** how many outbound bytes were requested? */
        public int getTotalOutboundRequested();
        /** how many outbound bytes were requested and haven't yet been allocated? */
        public int getPendingOutboundRequested();
        /** how many inbound bytes were requested? */
        public int getTotalInboundRequested();
        /** how many inbound bytes were requested and haven't yet been allocated? */
        public int getPendingInboundRequested();
        /** block until we are allocated some more bytes */
        public void waitForNextAllocation();
        /** we no longer want the data requested (the connection closed) */
        public void abort();
        /** was this request aborted?  */
        public boolean getAborted();
        /** thar be dragons */
        public void init(int in, int out, String target);
        public void setCompleteListener(CompleteListener lsnr);
        public void attach(Object obj);
        public Object attachment();
        public CompleteListener getCompleteListener();
    }
    
    public interface CompleteListener {
        public void complete(Request req);
    }

    private static final NoopRequest _noop = new NoopRequest();
    private static class NoopRequest implements Request {
        private CompleteListener _lsnr;
        private Object _attachment;
        public void abort() {}
        public boolean getAborted() { return false; }
        public int getPendingInboundRequested() { return 0; }
        public int getPendingOutboundRequested() { return 0; }
        public String getRequestName() { return "noop"; }
        public long getRequestTime() { return 0; }
        public int getTotalInboundRequested() { return 0; }
        public int getTotalOutboundRequested() { return 0; } 
        public void waitForNextAllocation() {}
        public void init(int in, int out, String target) {}
        public CompleteListener getCompleteListener() { return _lsnr; }
        public void setCompleteListener(CompleteListener lsnr) {
            _lsnr = lsnr;
            lsnr.complete(NoopRequest.this);
        }
        public void attach(Object obj) { _attachment = obj; }
        public Object attachment() { return _attachment; }
    }
}
