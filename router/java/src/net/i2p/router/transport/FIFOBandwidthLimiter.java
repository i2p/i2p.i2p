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
    private volatile long _availableInboundBytes;
    private volatile long _availableOutboundBytes;
    private boolean _outboundUnlimited;
    private boolean _inboundUnlimited;
    private volatile long _totalAllocatedInboundBytes;
    private volatile long _totalAllocatedOutboundBytes;
    private long _maxInboundBytes;
    private long _maxOutboundBytes;
    private FIFOBandwidthRefiller _refiller;
    
    private static int __id = 0;
    
    public FIFOBandwidthLimiter(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(FIFOBandwidthLimiter.class);
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
    public void setMaxInboundBytes(long numBytes) { _maxInboundBytes = numBytes; }
    public long getMaxOutboundBytes() { return _maxOutboundBytes; }
    public void setMaxOutboundBytes(long numBytes) { _maxOutboundBytes = numBytes; }
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
    public void requestInbound(int bytesIn, String purpose) {
        addInboundRequest(new SimpleRequest(bytesIn, 0, purpose));
    }
    /**
     * Request some bytes, blocking until they become available
     *
     */
    public void requestOutbound(int bytesOut, String purpose) {
        addOutboundRequest(new SimpleRequest(0, bytesOut, purpose));
    }
    
    /**
     * Add the request to the queue, blocking the requesting thread until
     * bandwidth is available (and all requests for bandwidth ahead of it have
     * been granted).  Once sufficient bandwidth is available, this call will
     * return and request.grantRequest() will have been called.
     * 
     */
    private final void addInboundRequest(BandwidthRequest request) {
        synchronized (_pendingInboundRequests) {
            if ( (_pendingInboundRequests.size() <= 0) &&
                 ( (request.getRequestedInboundBytes() <= _availableInboundBytes) || (_inboundUnlimited) ) ) {
                 // the queue is empty and there are sufficient bytes, grant 'em
                 if (!_inboundUnlimited)
                    _availableInboundBytes -= request.getRequestedInboundBytes();
                 _totalAllocatedInboundBytes += request.getRequestedInboundBytes();
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Granting inbound request " + request.getRequestName() + " immediately for " 
                                + request.getRequestedInboundBytes());
                 request.grantRequest();
                 return;
            } else {
                _pendingInboundRequests.add(request);
            }
        }
        synchronized (request.getAvailabilityMonitor()) {
            while (!request.alreadyGranted()) {
                try {
                    request.getAvailabilityMonitor().wait();
                } catch (InterruptedException ie) {}
            }
        }
    }
    
    /**
     * Add the request to the queue, blocking the requesting thread until
     * bandwidth is available (and all requests for bandwidth ahead of it have
     * been granted).  Once sufficient bandwidth is available, this call will
     * return and request.grantRequest() will have been called.
     * 
     */
    private final void addOutboundRequest(BandwidthRequest request) {
        synchronized (_pendingOutboundRequests) {
            if ( (_pendingOutboundRequests.size() <= 0) &&
                 ( (request.getRequestedOutboundBytes() <= _availableOutboundBytes) || (_outboundUnlimited) ) ) {
                 // the queue is empty and there are sufficient bytes, grant 'em
                 if (!_outboundUnlimited)
                    _availableOutboundBytes -= request.getRequestedOutboundBytes();
                 _totalAllocatedOutboundBytes += request.getRequestedOutboundBytes();
                 if (_log.shouldLog(Log.INFO))
                     _log.info("Granting outbound request " + request.getRequestName() + " immediately for " 
                                + request.getRequestedOutboundBytes());
                 request.grantRequest();
                 return;
            } else {
                _pendingOutboundRequests.add(request);
            }
        }
        synchronized (request.getAvailabilityMonitor()) {
            while (!request.alreadyGranted()) {
                try {
                    request.getAvailabilityMonitor().wait();
                } catch (InterruptedException ie) {}
            }
        }
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
        synchronized (_pendingInboundRequests) {
            while (_pendingInboundRequests.size() > 0) {
                BandwidthRequest req = (BandwidthRequest)_pendingInboundRequests.get(0);
                if ( (req.getRequestedInboundBytes() <= _availableInboundBytes) || (_inboundUnlimited) ) {
                     _pendingInboundRequests.remove(0);
                     if (!_inboundUnlimited)
                        _availableInboundBytes -= req.getRequestedInboundBytes();
                     _totalAllocatedInboundBytes += req.getRequestedInboundBytes();
                     if (_log.shouldLog(Log.INFO))
                         _log.info("Granting inbound request " + req.getRequestName() + " for " 
                                    + req.getRequestedInboundBytes() + " bytes (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms) pending " + _pendingInboundRequests.size());
                     // i hate nested synchronization
                     synchronized (req.getAvailabilityMonitor()) {
                        req.grantRequest();
                        req.getAvailabilityMonitor().notifyAll();
                     }
                } else {
                    // there isn't sufficient bandwidth for the first request, 
                    // so since we're a FIFO limiter, everyone waits.  If we were a 
                    // best fit or ASAP limiter, we'd continue on iterating to see
                    // if anyone would be satisfied with the current availability
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Still denying the first inbound request (" + req.getRequestName() 
                                    + " for " 
                                    + req.getRequestedInboundBytes() + " bytes (available "
                                    + _availableInboundBytes + "/" + _availableOutboundBytes + " in/out) (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms so far) pending " + (_pendingInboundRequests.size()));
                    return;
                }
            }
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Nothing pending");
        }
    }
    
    private final void satisfyOutboundRequests() {
        synchronized (_pendingOutboundRequests) {
            while (_pendingOutboundRequests.size() > 0) {
                BandwidthRequest req = (BandwidthRequest)_pendingOutboundRequests.get(0);
                if ( (req.getRequestedOutboundBytes() <= _availableOutboundBytes) || (_outboundUnlimited) ) {
                     _pendingOutboundRequests.remove(0);
                     if (!_outboundUnlimited)
                        _availableOutboundBytes -= req.getRequestedOutboundBytes();
                     _totalAllocatedOutboundBytes += req.getRequestedOutboundBytes();
                     if (_log.shouldLog(Log.INFO))
                         _log.info("Granting outbound request " + req.getRequestName() + " for " 
                                    + req.getRequestedOutboundBytes() + " bytes (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms) pending " + (_pendingOutboundRequests.size()-1));
                     // i hate nested synchronization
                     synchronized (req.getAvailabilityMonitor()) {
                        req.grantRequest();
                        req.getAvailabilityMonitor().notifyAll();
                     }
                } else {
                    // there isn't sufficient bandwidth for the first request, 
                    // so since we're a FIFO limiter, everyone waits.  If we were a 
                    // best fit or ASAP limiter, we'd continue on iterating to see
                    // if anyone would be satisfied with the current availability
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Still denying the first outbound request (" + req.getRequestName() 
                                    + " for " 
                                    + req.getRequestedOutboundBytes() + " bytes (available "
                                    + _availableInboundBytes + "/" + _availableOutboundBytes + " in/out) (waited " 
                                    + (_context.clock().now() - req.getRequestTime()) 
                                    + "ms so far) pending " + (_pendingOutboundRequests.size()));
                    return;
                }
            }
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Nothing pending");
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
                BandwidthRequest req = (BandwidthRequest)_pendingInboundRequests.get(i);
                buf.append("<li>").append(req.getRequestName()).append(" for ");
                buf.append(req.getRequestedInboundBytes()).append(" bytes ");
                buf.append("requested ").append(now-req.getRequestTime());
                buf.append("ms ago</li>\n");
            }
        }
        buf.append("</ol></li><li>Outbound requests: <ol>\n");
        synchronized (_pendingOutboundRequests) {
            for (int i = 0; i < _pendingOutboundRequests.size(); i++) {
                BandwidthRequest req = (BandwidthRequest)_pendingOutboundRequests.get(i);
                buf.append("<li>").append(req.getRequestName()).append(" for ");
                buf.append(req.getRequestedOutboundBytes()).append(" bytes ");
                buf.append("requested ").append(now-req.getRequestTime());
                buf.append("ms ago</li>\n");
            }
        }
        buf.append("</ol></li></ul>\n");
        out.write(buf.toString().getBytes());
    }
    
    private static long __requestId = 0;
    private final class SimpleRequest implements BandwidthRequest {
        private boolean _alreadyGranted;
        private int _in;
        private int _out;
        private long _requestId;
        private long _requestTime;
        private String _target;
        
        public SimpleRequest(int in, int out, String target) {
            _in = in;
            _out = out;
            _target = target;
            _alreadyGranted = false;
            _requestId = ++__requestId;
            _requestTime = _context.clock().now();
        }
        public boolean alreadyGranted() { return _alreadyGranted; }
        public Object getAvailabilityMonitor() { return SimpleRequest.this; }
        public String getRequestName() { return "Req" + _requestId + " to " + _target; }
        public int getRequestedInboundBytes() { return _in; }
        public int getRequestedOutboundBytes() { return _out; }
        public void grantRequest() { _alreadyGranted = true; }
        public long getRequestTime() { return _requestTime; }
    }
    
    /**
     * Defines a request for bandwidth allocation
     */
    private interface BandwidthRequest {
        /** 
         * how can we summarize this request (in case we want to display a list 
         * of 'whats pending')
         */
        public String getRequestName();
        /** 
         * How many bytes are we going to send away from the router
         */
        public int getRequestedOutboundBytes();
        /** 
         * How many bytes are we going to read from the network
         */
        public int getRequestedInboundBytes();
        /**
         * Lock unique to this request that will be wait() & notified upon
         * during the queueing
         */
        public Object getAvailabilityMonitor();
        /** 
         * When was the bandwidth requested? 
         */
        public long getRequestTime();
        /** 
         * must return true only if grantRequest has been called, else 
         * false 
         */
        public boolean alreadyGranted();
        /** 
         * flag this request to tell it that it has been or is about to be 
         * allocated sufficient bytes.  This should NOT be used as the notification
         * itself
         */
        public void grantRequest();
    }
}
