package net.i2p.router.transport;

import java.io.IOException;
import java.io.OutputStream;

import java.util.List;
import java.util.ArrayList;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class FIFOBandwidthLimiter {
    private Log _log;
    private I2PAppContext _context;
    private List _pendingInboundRequests;
    private List _pendingOutboundRequests;
    private volatile int _availableInboundBytes;
    private volatile int _availableOutboundBytes;
    private boolean _outboundUnlimited;
    private boolean _inboundUnlimited;
    private volatile long _totalAllocatedInboundBytes;
    private volatile long _totalAllocatedOutboundBytes;
    private int _maxInboundBytes;
    private int _maxOutboundBytes;
    private FIFOBandwidthRefiller _refiller;
    
    private static int __id = 0;
    
    public FIFOBandwidthLimiter(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthLimiter.class);
        _context.statManager().createRateStat("bwLimiter.pendingOutboundRequests", "How many outbound requests are ahead of the current one (ignoring ones with 0)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.pendingInboundRequests", "How many inbound requests are ahead of the current one (ignoring ones with 0)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.outboundDelayedTime", "How long it takes to honor an outbound request (ignoring ones with that go instantly)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _context.statManager().createRateStat("bwLimiter.inboundDelayedTime", "How long it takes to honor an inbound request (ignoring ones with that go instantly)?", "BandwidthLimiter", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        _pendingInboundRequests = new ArrayList(16);
        _pendingOutboundRequests = new ArrayList(16);
        _refiller = new FIFOBandwidthRefiller(_context, this);
        I2PThread t = new I2PThread(_refiller);
        t.setName("BWRefiller" + (++__id));
        t.setDaemon(true);
        t.setPriority(I2PThread.NORM_PRIORITY-1);
        t.start();
    }

    public long getAvailableInboundBytes() { return _availableInboundBytes; }
    public long getAvailableOutboundBytes() { return _availableOutboundBytes; }
    public long getTotalAllocatedInboundBytes() { return _totalAllocatedInboundBytes; }
    public long getTotalAllocatedOutboundBytes() { return _totalAllocatedOutboundBytes; }
    public long getMaxInboundBytes() { return _maxInboundBytes; }
    public void setMaxInboundBytes(int numBytes) { _maxInboundBytes = numBytes; }
    public long getMaxOutboundBytes() { return _maxOutboundBytes; }
    public void setMaxOutboundBytes(int numBytes) { _maxOutboundBytes = numBytes; }
    public boolean getInboundUnlimited() { return _inboundUnlimited; }
    public void setInboundUnlimited(boolean isUnlimited) { _inboundUnlimited = isUnlimited; }
    public boolean getOutboundUnlimited() { return _outboundUnlimited; }
    public void setOutboundUnlimited(boolean isUnlimited) { _outboundUnlimited = isUnlimited; }
    
    public void reinitialize() {
        _pendingInboundRequests.clear();
        _pendingOutboundRequests.clear();
        _availableInboundBytes = 0;
        _availableOutboundBytes = 0;
        _inboundUnlimited = false;
        _outboundUnlimited = false;
        _refiller.reinitialize();
    }
    
    /**
     * Request some bytes, blocking until they become available
     *
     */
    public Request requestInbound(int bytesIn, String purpose) {
        SimpleRequest req = new SimpleRequest(bytesIn, 0, purpose);
        int pending = 0;
        synchronized (_pendingInboundRequests) {
            pending = _pendingInboundRequests.size();
            _pendingInboundRequests.add(req);
        }
        satisfyInboundRequests();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingInboundRequests", pending, pending);
        return req;
    }
    /**
     * Request some bytes, blocking until they become available
     *
     */
    public Request requestOutbound(int bytesOut, String purpose) {
        SimpleRequest req = new SimpleRequest(0, bytesOut, purpose);
        int pending = 0;
        synchronized (_pendingOutboundRequests) {
            pending = _pendingOutboundRequests.size();
            _pendingOutboundRequests.add(req);
        }
        satisfyOutboundRequests();
        if (pending > 0)
            _context.statManager().addRateData("bwLimiter.pendingOutboundRequests", pending, pending);
        return req;
    }
    
    /**
     * More bytes are available - add them to the queue and satisfy any requests
     * we can
     */
    final void refillBandwidthQueues(long bytesInbound, long bytesOutbound) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Refilling the queues with " + bytesInbound + "/" + bytesOutbound);
        _availableInboundBytes += bytesInbound;
        _availableOutboundBytes += bytesOutbound;
        if (_availableInboundBytes > _maxInboundBytes)
            _availableInboundBytes = _maxInboundBytes;
        if (_availableOutboundBytes > _maxOutboundBytes)
            _availableOutboundBytes = _maxOutboundBytes;
        satisfyRequests();
    }
    
    /**
     * Go through the queue, satisfying as many requests as possible (notifying
     * each one satisfied that the request has been granted).  
     */
    private final void satisfyRequests() {
        satisfyInboundRequests();
        satisfyOutboundRequests();
    }
    
    private final void satisfyInboundRequests() {
        List satisfied = null;
        synchronized (_pendingInboundRequests) {
            while (_pendingInboundRequests.size() > 0) {
                SimpleRequest req = (SimpleRequest)_pendingInboundRequests.get(0);
                if (_inboundUnlimited) {
                    int allocated = req.getPendingInboundRequested();
                    _totalAllocatedInboundBytes += allocated;
                    req.allocateBytes(allocated, 0);
                    if (satisfied == null)
                        satisfied = new ArrayList(2);
                    satisfied.add(req);
                    long waited = _context.clock().now() - req.getRequestTime();
                    if (_log.shouldLog(Log.INFO))
                         _log.info("Granting inbound request " + req.getRequestName() + " fully for " 
                                    + req.getTotalInboundRequested() + " bytes (waited " 
                                    + waited
                                    + "ms) pending " + _pendingInboundRequests.size());
                    // if we're unlimited, we always grant it fully, so there's no need to keep it around
                    _pendingInboundRequests.remove(0);
                    if (waited > 10)
                        _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited, waited);
                } else if (_availableInboundBytes > 0) {
                    int requested = req.getPendingInboundRequested();
                    int allocated = 0;
                    if (_availableInboundBytes > requested) 
                        allocated = requested;
                    else
                        allocated = _availableInboundBytes;
                    _availableInboundBytes -= allocated;
                    _totalAllocatedInboundBytes += allocated;
                    req.allocateBytes(allocated, 0);
                    if (satisfied == null)
                        satisfied = new ArrayList(2);
                    satisfied.add(req);
                    long waited = _context.clock().now() - req.getRequestTime();
                    if (req.getPendingInboundRequested() > 0) {
                        if (_log.shouldLog(Log.INFO))
                             _log.info("Allocating " + allocated + " bytes inbound as a partial grant to " 
                                        + req.getRequestName() + " (wanted " 
                                        + req.getTotalInboundRequested() + " bytes, waited " 
                                        + waited
                                        + "ms) pending " + _pendingInboundRequests.size());
                    } else {
                        if (_log.shouldLog(Log.INFO))
                             _log.info("Allocating " + allocated + " bytes inbound to finish the partial grant to " 
                                        + req.getRequestName() + " (total " 
                                        + req.getTotalInboundRequested() + " bytes, waited " 
                                        + waited
                                        + "ms) pending " + _pendingInboundRequests.size());
                        _pendingInboundRequests.remove(0);
                        if (waited > 10)
                            _context.statManager().addRateData("bwLimiter.inboundDelayedTime", waited, waited);
                    }
                } else {
                    // no bandwidth available
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Still denying the first inbound request (" + req.getRequestName() 
                                    + " for " 
                                    + req.getTotalInboundRequested() + " bytes (available "
                                    + _availableInboundBytes + "/" + _availableOutboundBytes + " in/out) (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms so far) pending " + (_pendingInboundRequests.size()));
                    break;
                }
            }
        }
        
        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest req = (SimpleRequest)satisfied.get(i);
                req.notifyAllocation();
            }
        }
    }
    
    private final void satisfyOutboundRequests() {
        List satisfied = null;
        synchronized (_pendingOutboundRequests) {
            while (_pendingOutboundRequests.size() > 0) {
                SimpleRequest req = (SimpleRequest)_pendingOutboundRequests.get(0);
                if (_outboundUnlimited) {
                    int allocated = req.getPendingOutboundRequested();
                    _totalAllocatedOutboundBytes += allocated;
                    req.allocateBytes(0, allocated);
                    if (satisfied == null)
                        satisfied = new ArrayList(2);
                    satisfied.add(req);
                    long waited = _context.clock().now() - req.getRequestTime();
                    if (_log.shouldLog(Log.INFO))
                         _log.info("Granting outbound request " + req.getRequestName() + " fully for " 
                                    + req.getTotalOutboundRequested() + " bytes (waited " 
                                    + waited
                                    + "ms) pending " + _pendingOutboundRequests.size());
                    // if we're unlimited, we always grant it fully, so there's no need to keep it around
                    _pendingOutboundRequests.remove(0);
                    if (waited > 10)
                        _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited, waited);
                } else if (_availableOutboundBytes > 0) {
                    int requested = req.getPendingOutboundRequested();
                    int allocated = 0;
                    if (_availableOutboundBytes > requested) 
                        allocated = requested;
                    else
                        allocated = _availableOutboundBytes;
                    _availableOutboundBytes -= allocated;
                    _totalAllocatedOutboundBytes += allocated;
                    req.allocateBytes(0, allocated);
                    if (satisfied == null)
                        satisfied = new ArrayList(2);
                    satisfied.add(req);
                    long waited = _context.clock().now() - req.getRequestTime();
                    if (req.getPendingOutboundRequested() > 0) {
                        if (_log.shouldLog(Log.INFO))
                             _log.info("Allocating " + allocated + " bytes outbound as a partial grant to " 
                                        + req.getRequestName() + " (wanted " 
                                        + req.getTotalOutboundRequested() + " bytes, waited " 
                                        + waited
                                        + "ms) pending " + _pendingOutboundRequests.size());
                    } else {
                        if (_log.shouldLog(Log.INFO))
                             _log.info("Allocating " + allocated + " bytes outbound to finish the partial grant to " 
                                        + req.getRequestName() + " (total " 
                                        + req.getTotalOutboundRequested() + " bytes, waited " 
                                        + waited
                                        + "ms) pending " + _pendingOutboundRequests.size());
                        _pendingOutboundRequests.remove(0);
                        if (waited > 10)
                            _context.statManager().addRateData("bwLimiter.outboundDelayedTime", waited, waited);
                    }
                } else {
                    // no bandwidth available
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Still denying the first outbound request (" + req.getRequestName() 
                                    + " for " 
                                    + req.getTotalOutboundRequested() + " bytes (available "
                                    + _availableInboundBytes + "/" + _availableOutboundBytes + " in/out) (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms so far) pending " + (_pendingOutboundRequests.size()));
                    break;
                }
            }
        }
        
        if (satisfied != null) {
            for (int i = 0; i < satisfied.size(); i++) {
                SimpleRequest req = (SimpleRequest)satisfied.get(i);
                req.notifyAllocation();
            }
        }
    }
    
    public void renderStatusHTML(OutputStream out) throws IOException {
        long now = _context.clock().now();
        StringBuffer buf = new StringBuffer(4096);
        buf.append("<br /><b>Pending bandwidth requests (with ");
        buf.append(_availableInboundBytes).append('/');
        buf.append(_availableOutboundBytes).append(" bytes inbound/outbound available):</b><ul>");
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
        out.write(buf.toString().getBytes());
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
        
        public SimpleRequest(int in, int out, String target) {
            _inTotal = in;
            _outTotal = out;
            _inAllocated = 0;
            _outAllocated = 0;
            _target = target;
            _requestId = ++__requestId;
            _requestTime = _context.clock().now();
        }
        public Object getAvailabilityMonitor() { return SimpleRequest.this; }
        public String getRequestName() { return "Req" + _requestId + " to " + _target; }
        public long getRequestTime() { return _requestTime; }
        public int getTotalOutboundRequested() { return _outTotal; }
        public int getPendingOutboundRequested() { return _outTotal - _outAllocated; }
        public int getTotalInboundRequested() { return _inTotal; }
        public int getPendingInboundRequested() { return _inTotal - _inAllocated; }
        public void waitForNextAllocation() {
            if ( (_outAllocated >= _outTotal) && 
                 (_inAllocated >= _inTotal) ) 
                return;
            try {
                synchronized (SimpleRequest.this) {
                    SimpleRequest.this.wait();
                }
            } catch (InterruptedException ie) {}
        }
        void allocateBytes(int in, int out) {
            _inAllocated += in;
            _outAllocated += out;
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
    }
}
