package net.i2p.router.transport;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class FIFOBandwidthLimiter {
    private Log _log;
    private I2PAppContext _context;
    private List _pendingInboundRequests;
    private List _pendingOutboundRequests;
    /** how many bytes we can consume for inbound transmission immediately */
    private volatile int _availableInbound;
    /** how many bytes we can consume for outbound transmission immediately */
    private volatile int _availableOutbound;
    /** how many bytes we can queue up for bursting */
    private volatile int _unavailableInboundBurst;
    /** how many bytes we can queue up for bursting */
    private volatile int _unavailableOutboundBurst;
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
    private volatile long _totalAllocatedInboundBytes;
    /** lifetime counter of bytes sent */
    private volatile long _totalAllocatedOutboundBytes;
    /** lifetime counter of tokens available for use but exceeded our maxInboundBurst size */
    private volatile long _totalWastedInboundBytes;
    /** lifetime counter of tokens available for use but exceeded our maxOutboundBurst size */
    private volatile long _totalWastedOutboundBytes;
    private FIFOBandwidthRefiller _refiller;
    
    private long _lastTotalSent;
    private long _lastTotalReceived;
    private long _lastStatsUpdated;
    private long _lastRateUpdated;
    private float _sendBps;
    private float _recvBps;
    
    private static int __id = 0;
    
    static long now() { 
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
        _context.statManager().createRateStat("bw.sendRate", "Low level bandwidth send rate, averaged every minute", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bw.recvRate", "Low level bandwidth receive rate, averaged every minute", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _pendingInboundRequests = new ArrayList(16);
        _pendingOutboundRequests = new ArrayList(16);
        _lastTotalSent = _totalAllocatedOutboundBytes;
        _lastTotalReceived = _totalAllocatedInboundBytes;
        _sendBps = 0;
        _recvBps = 0;
        _lastStatsUpdated = now();
        _lastRateUpdated = _lastStatsUpdated;
        _refiller = new FIFOBandwidthRefiller(_context, this);
        I2PThread t = new I2PThread(_refiller);
        t.setName("BWRefiller" + (++__id));
        t.setDaemon(true);
        t.setPriority(I2PThread.NORM_PRIORITY-1);
        t.start();
    }

    //public long getAvailableInboundBytes() { return _availableInboundBytes; }
    //public long getAvailableOutboundBytes() { return _availableOutboundBytes; }
    public long getTotalAllocatedInboundBytes() { return _totalAllocatedInboundBytes; }
    public long getTotalAllocatedOutboundBytes() { return _totalAllocatedOutboundBytes; }
    public long getTotalWastedInboundBytes() { return _totalWastedInboundBytes; }
    public long getTotalWastedOutboundBytes() { return _totalWastedOutboundBytes; }
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
    
    public int getOutboundKBytesPerSecond() { return _refiller.getOutboundKBytesPerSecond(); } 
    public int getInboundKBytesPerSecond() { return _refiller.getInboundKBytesPerSecond(); } 
    
    public void reinitialize() {
        _pendingInboundRequests.clear();
        _pendingOutboundRequests.clear();
        _availableInbound = 0;
        _availableOutbound = 0;
        _maxInbound = 0;
        _maxOutbound = 0;
        _maxInboundBurst = 0;
        _maxOutboundBurst = 0;
        _unavailableInboundBurst = 0;
        _unavailableOutboundBurst = 0;
        _inboundUnlimited = false;
        _outboundUnlimited = false;
        _refiller.reinitialize();
    }
    
    public Request createRequest() { return new SimpleRequest(); }

    /**
     * Request some bytes, blocking until they become available
     *
     */
    public Request requestInbound(int bytesIn, String purpose) {
        if (_inboundUnlimited) {
            _totalAllocatedInboundBytes += bytesIn;
            return _noop;
        }
        
        SimpleRequest req = new SimpleRequest(bytesIn, 0, purpose);
        requestInbound(req, bytesIn, purpose);
        return req;
    }
    public void requestInbound(Request req, int bytesIn, String purpose) {
        req.init(bytesIn, 0, purpose);
        if (false) { ((SimpleRequest)req).allocateAll(); return; }
        int pending = 0;
        synchronized (_pendingInboundRequests) {
            pending = _pendingInboundRequests.size();
            _pendingInboundRequests.add(req);
        }
        satisfyInboundRequests(((SimpleRequest)req).satisfiedBuffer);
        ((SimpleRequest)req).satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingInboundRequests", pending, pending);
    }
    /**
     * Request some bytes, blocking until they become available
     *
     */
    public Request requestOutbound(int bytesOut, String purpose) {
        if (_outboundUnlimited) {
            _totalAllocatedOutboundBytes += bytesOut;
            return _noop;
        }

        SimpleRequest req = new SimpleRequest(0, bytesOut, purpose);
        requestOutbound(req, bytesOut, purpose);
        return req;
    }
    public void requestOutbound(Request req, int bytesOut, String purpose) {
        req.init(0, bytesOut, purpose);
        if (false) { ((SimpleRequest)req).allocateAll(); return; }
        int pending = 0;
        synchronized (_pendingOutboundRequests) {
            pending = _pendingOutboundRequests.size();
            _pendingOutboundRequests.add(req);
        }
        satisfyOutboundRequests(((SimpleRequest)req).satisfiedBuffer);
        ((SimpleRequest)req).satisfiedBuffer.clear();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingOutboundRequests", pending, pending);
    }
    
    void setInboundBurstKBps(int kbytesPerSecond) {
        _maxInbound = kbytesPerSecond * 1024;
    }
    void setOutboundBurstKBps(int kbytesPerSecond) {
        _maxOutbound = kbytesPerSecond * 1024;
    }
    int getInboundBurstBytes() { return _maxInboundBurst; }
    int getOutboundBurstBytes() { return _maxOutboundBurst; }
    void setInboundBurstBytes(int bytes) { _maxInboundBurst = bytes; }
    void setOutboundBurstBytes(int bytes) { _maxOutboundBurst = bytes; }
    
    StringBuffer getStatus() {
        StringBuffer rv = new StringBuffer(64);
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
    final void refillBandwidthQueues(List buf, long bytesInbound, long bytesOutbound, long maxBurstIn, long maxBurstOut) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Refilling the queues with " + bytesInbound + "/" + bytesOutbound + ": " + getStatus().toString());
        _availableInbound += bytesInbound;
        _availableOutbound += bytesOutbound;
        
        if (_availableInbound > _maxInbound) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("available inbound (" + _availableInbound + ") exceeds our inbound burst (" + _maxInbound + "), so no supplement");
            _unavailableInboundBurst += _availableInbound - _maxInbound;
            _availableInbound = _maxInbound;
            if (_unavailableInboundBurst > _maxInboundBurst) {
                _totalWastedInboundBytes += _unavailableInboundBurst - _maxInboundBurst;
                _unavailableInboundBurst = _maxInboundBurst;
            }
        } else {
            // try to pull in up to 1/10th of the burst rate, since we refill every 100ms
            int want = (int)maxBurstIn;
            if (want > (_maxInbound - _availableInbound))
                want = _maxInbound - _availableInbound;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("want to pull " + want + " from the inbound burst (" + _unavailableInboundBurst + ") to supplement " + _availableInbound + " (max: " + _maxInbound + ")");
            
            if (want > 0) {
                if (want <= _unavailableInboundBurst) {
                    _availableInbound += want;
                    _unavailableInboundBurst -= want;
                } else {
                    _availableInbound += _unavailableInboundBurst;
                    _unavailableInboundBurst = 0;
                }
            }
        }
        
        if (_availableOutbound > _maxOutbound) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("available outbound (" + _availableOutbound + ") exceeds our outbound burst (" + _maxOutbound + "), so no supplement");
            _unavailableOutboundBurst += _availableOutbound - _maxOutbound;
            _availableOutbound = _maxOutbound;
            if (_unavailableOutboundBurst > _maxOutboundBurst) {
                _totalWastedOutboundBytes += _unavailableOutboundBurst - _maxOutboundBurst;
                _unavailableOutboundBurst = _maxOutboundBurst;
            }
        } else {
            // try to pull in up to 1/10th of the burst rate, since we refill every 100ms
            int want = (int)maxBurstOut;
            if (want > (_maxOutbound - _availableOutbound))
                want = _maxOutbound - _availableOutbound;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("want to pull " + want + " from the outbound burst (" + _unavailableOutboundBurst + ") to supplement " + _availableOutbound + " (max: " + _maxOutbound + ")");
            
            if (want > 0) {
                if (want <= _unavailableOutboundBurst) {
                    _availableOutbound += want;
                    _unavailableOutboundBurst -= want;
                } else {
                    _availableOutbound += _unavailableOutboundBurst;
                    _unavailableOutboundBurst = 0;
                }
            }
        }
        
        satisfyRequests(buf);
        updateStats();
    }
    
    private void updateStats() {
        long now = now();
        long time = now - _lastStatsUpdated;
        if (time >= 1000) {
            long totS = _totalAllocatedOutboundBytes;
            long totR = _totalAllocatedInboundBytes;
            long sent = totS - _lastTotalSent;
            long recv = totR - _lastTotalReceived;
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
            if (_log.shouldLog(Log.WARN)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("BW: time = " + time + " sent: " + sent + " recv: " + recv);
                _context.statManager().getStatLog().addData("bw", "bw.sendBps1s", (long)_sendBps, sent);
                _context.statManager().getStatLog().addData("bw", "bw.recvBps1s", (long)_recvBps, recv);
            }
        }
        if (60*1000 + _lastRateUpdated <= now) {
            _lastRateUpdated = now;
            _context.statManager().addRateData("bw.sendRate", (long)_sendBps, 0);
            _context.statManager().addRateData("bw.recvRate", (long)_recvBps, 0);
        }
    }
    
    /**
     * Go through the queue, satisfying as many requests as possible (notifying
     * each one satisfied that the request has been granted).  
     */
    private final void satisfyRequests(List buffer) {
        buffer.clear();
        satisfyInboundRequests(buffer);
        buffer.clear();
        satisfyOutboundRequests(buffer);
    }
    
    private final void satisfyInboundRequests(List satisfied) {
        synchronized (_pendingInboundRequests) {
            if (_inboundUnlimited) {
                locked_satisfyInboundUnlimited(satisfied);
            } else {
                if (_availableInbound > 0) {
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
    private final void locked_satisfyInboundUnlimited(List satisfied) {
        while (_pendingInboundRequests.size() > 0) {
            SimpleRequest req = (SimpleRequest)_pendingInboundRequests.remove(0);
            int allocated = req.getPendingInboundRequested();
            _totalAllocatedInboundBytes += allocated;
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
    private final void locked_satisfyInboundAvailable(List satisfied) {
        for (int i = 0; i < _pendingInboundRequests.size(); i++) {
            if (_availableInbound <= 0) break;
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
            if (req.getAllocationsSinceWait() > 0) {
                // we have already allocated some values to this request, but
                // they haven't taken advantage of it yet (most likely they're
                // IO bound)
                continue;
            }
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingInboundRequested();
            int allocated = 0;
            if (_availableInbound > requested) 
                allocated = requested;
            else
                allocated = _availableInbound;
            _availableInbound -= allocated;
            _totalAllocatedInboundBytes += allocated;
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
    
    private final void satisfyOutboundRequests(List satisfied) {
        synchronized (_pendingOutboundRequests) {
            if (_outboundUnlimited) {
                locked_satisfyOutboundUnlimited(satisfied);
            } else {
                if (_availableOutbound > 0) {
                    locked_satisfyOutboundAvailable(satisfied);
                } else {
                    // no bandwidth available
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Still denying the " + _pendingOutboundRequests.size() 
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
    private final void locked_satisfyOutboundUnlimited(List satisfied) {
        while (_pendingOutboundRequests.size() > 0) {
            SimpleRequest req = (SimpleRequest)_pendingOutboundRequests.remove(0);
            int allocated = req.getPendingOutboundRequested();
            _totalAllocatedOutboundBytes += allocated;
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
    private final void locked_satisfyOutboundAvailable(List satisfied) {
        for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
            if (_availableOutbound <= 0) break;
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
                continue;
            }
            // ok, they are really waiting for us to give them stuff
            int requested = req.getPendingOutboundRequested();
            int allocated = 0;
            if (_availableOutbound > requested) 
                allocated = requested;
            else
                allocated = _availableOutbound;
            _availableOutbound -= allocated;
            _totalAllocatedOutboundBytes += allocated;
            req.allocateBytes(0, allocated);
            satisfied.add(req);
            if (req.getPendingOutboundRequested() > 0) {
                if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Allocating " + allocated + " bytes outbound as a partial grant to " 
                                + req.getRequestName() + " (wanted " 
                                + req.getTotalOutboundRequested() + " bytes, waited " 
                                + waited
                                + "ms) pending " + _pendingOutboundRequests.size()
                                + ", longest waited " + locked_getLongestOutboundWait() + " out");
            } else {
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
    
    public void renderStatusHTML(Writer out) throws IOException {
        long now = now();
        StringBuffer buf = new StringBuffer(4096);
        buf.append("<br /><i id=\"bwlim\">Limiter status: ").append(getStatus().toString()).append("</i><br />\n");
        buf.append("<b>Pending bandwidth requests:</b><ul>");
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
        buf.append("</ol></li></ul>\n");
        out.write(buf.toString());
        out.flush();
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
        List satisfiedBuffer;
        
        public SimpleRequest() {
            satisfiedBuffer = new ArrayList(1);
            init(0, 0, null);
	}
        public SimpleRequest(int in, int out, String target) {
            satisfiedBuffer = new ArrayList(1);
            init(in, out, target);
	}
        public void init(int in, int out, String target) {
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
        
        public void waitForNextAllocation() {
            _allocationsSinceWait = 0;
            if ( (_outAllocated >= _outTotal) && 
                 (_inAllocated >= _inTotal) ) 
                return;
            try {
                synchronized (SimpleRequest.this) {
                    SimpleRequest.this.wait();
                }
            } catch (InterruptedException ie) {}
        }
        int getAllocationsSinceWait() { return _allocationsSinceWait; }
        void allocateAll() {
            _inAllocated = _inTotal;
            _outAllocated = _outTotal;
            _outAllocated = _outTotal;
            _allocationsSinceWait++;
            notifyAllocation();
	}
        void allocateBytes(int in, int out) {
            _inAllocated += in;
            _outAllocated += out;
            _allocationsSinceWait++;
        }
        void notifyAllocation() {
            synchronized (SimpleRequest.this) {
                SimpleRequest.this.notifyAll();
            }
        }
    }

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
    }

    private static final NoopRequest _noop = new NoopRequest();
    private static class NoopRequest implements Request {
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
    }
}
