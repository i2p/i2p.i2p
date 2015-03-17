package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.data.RouterIdentity;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class BandwidthLimitedOutputStream extends FilterOutputStream {
    private RouterIdentity _peer;
    private String _peerTarget;
    private RouterContext _context;
    private Log _log;
    private FIFOBandwidthLimiter.Request _currentRequest;
    
    public BandwidthLimitedOutputStream(RouterContext context, OutputStream source, RouterIdentity peer) {
        super(source);
        _context = context;
        _peer = peer;
        if (peer != null)
            _peerTarget = peer.getHash().toBase64();
        else
            _peerTarget = "unknown";
        _log = context.logManager().getLog(BandwidthLimitedOutputStream.class);
        _currentRequest = null;
    }
    
    public FIFOBandwidthLimiter.Request getCurrentRequest() { return _currentRequest; }
    
    @Override
    public void write(int val) throws IOException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing a single byte!", new Exception("Single byte from..."));
        long before = _context.clock().now();
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(1, _peerTarget);
        // only a single byte, no need to loop
        req.waitForNextAllocation();
        long waited = _context.clock().now() - before;
        if ( (waited > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Waiting to write a byte took too long [" + waited + "ms");
        out.write(val);
    }
    @Override
    public void write(byte src[]) throws IOException {
        write(src, 0, src.length);
    }
    @Override
    public void write(byte src[], int off, int len) throws IOException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing " + len + " bytes");
        if (src == null) return;
        if (len <= 0) return;
        if (len + off > src.length)
            throw new IllegalArgumentException("wtf are you thinking?  len=" + len 
                                               + ", off=" + off + ", data=" + src.length);
        _currentRequest = _context.bandwidthLimiter().requestOutbound(len, _peerTarget);
        
        int written = 0;
        while (written < len) {
            int allocated = len - _currentRequest.getPendingOutboundRequested();
            int toWrite = allocated - written;
            if (toWrite > 0) {
                try {
                    out.write(src, off + written, toWrite);
                } catch (IOException ioe) {
                    _currentRequest.abort();
                    _currentRequest = null;
                    throw ioe;
                }
                written += toWrite;
            }
            _currentRequest.waitForNextAllocation();
        }
        synchronized (this) {
            _currentRequest = null;
        }
    }
    
    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (_currentRequest != null)
                _currentRequest.abort();
        }
        super.close();
    }
}
