package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.RouterIdentity;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class BandwidthLimitedInputStream extends FilterInputStream {
    private Log _log;
    private RouterIdentity _peer;
    private String _peerSource;
    private RouterContext _context;
    private boolean _pullFromOutbound;
    private FIFOBandwidthLimiter.Request _currentRequest;
    
    public BandwidthLimitedInputStream(RouterContext context, InputStream source, RouterIdentity peer) {
        this(context, source, peer, false);
    }
    /**
     * @param pullFromOutbound even though this is an input stream, if this is true, use the
     *                         context's outbound bandwidth limiter queue for delays
     */
    public BandwidthLimitedInputStream(RouterContext context, InputStream source, RouterIdentity peer, boolean pullFromOutbound) {
        super(source);
        _context = context;
        _peer = peer;
        if (peer != null)
            _peerSource = peer.getHash().toBase64();
        _pullFromOutbound = pullFromOutbound;
        _log = context.logManager().getLog(BandwidthLimitedInputStream.class);
    }
    
    @Override
    public int read() throws IOException {
        if (_pullFromOutbound)
            _currentRequest = _context.bandwidthLimiter().requestOutbound(1, _peerSource);
        else
            _currentRequest = _context.bandwidthLimiter().requestInbound(1, _peerSource);
        
        // since its only a single byte, we dont need to loop
        // or check how much was allocated
        _currentRequest.waitForNextAllocation();
        synchronized (this) {
            _currentRequest = null;
        }
        return in.read();
    }
    
    @Override
    public int read(byte dest[]) throws IOException {
        return read(dest, 0, dest.length);
    }
    
    @Override
    public int read(byte dest[], int off, int len) throws IOException {
        int read = in.read(dest, off, len);
        if (read == -1) return -1;
        
        if (_pullFromOutbound)
            _currentRequest = _context.bandwidthLimiter().requestOutbound(read, _peerSource);
        else
            _currentRequest = _context.bandwidthLimiter().requestInbound(read, _peerSource);
        
        while ( (_currentRequest.getPendingInboundRequested() > 0) ||
                (_currentRequest.getPendingOutboundRequested() > 0) ) {
            // we still haven't been authorized for everything, keep on waiting
            _currentRequest.waitForNextAllocation();
            if (_currentRequest.getAborted()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Request aborted while trying to read " + len + " (actually read " + read + ")");
                break;
            }
        }
        synchronized (this) {
            _currentRequest = null;
        }
        return read;
    }
    @Override
    public long skip(long numBytes) throws IOException {
        long skip = in.skip(numBytes);
        
        if (_pullFromOutbound)
            _currentRequest = _context.bandwidthLimiter().requestOutbound((int)skip, _peerSource);
        else
            _currentRequest = _context.bandwidthLimiter().requestInbound((int)skip, _peerSource);
        
        while ( (_currentRequest.getPendingInboundRequested() > 0) ||
                (_currentRequest.getPendingOutboundRequested() > 0) ) {
            // we still haven't been authorized for everything, keep on waiting
            _currentRequest.waitForNextAllocation();
            if (_currentRequest.getAborted()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Request aborted while trying to skip " + numBytes);
                break;
            }
        }
        return skip;
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
