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

public class BandwidthLimitedInputStream extends FilterInputStream {
    private RouterIdentity _peer;
    private String _peerSource;
    private RouterContext _context;
    private boolean _pullFromOutbound;
    
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
    }
    
    public int read() throws IOException {
        FIFOBandwidthLimiter.Request req = null;
        if (_pullFromOutbound)
            req = _context.bandwidthLimiter().requestOutbound(1, _peerSource);
        else
            req = _context.bandwidthLimiter().requestInbound(1, _peerSource);
        
        // since its only a single byte, we dont need to loop
        // or check how much was allocated
        req.waitForNextAllocation();
        return in.read();
    }
    
    public int read(byte dest[]) throws IOException {
        return read(dest, 0, dest.length);
    }
    
    public int read(byte dest[], int off, int len) throws IOException {
        int read = in.read(dest, off, len);
        FIFOBandwidthLimiter.Request req = null;
        if (_pullFromOutbound)
            req = _context.bandwidthLimiter().requestOutbound(read, _peerSource);
        else
            req = _context.bandwidthLimiter().requestInbound(read, _peerSource);
        
        while ( (req.getPendingInboundRequested() > 0) ||
                (req.getPendingOutboundRequested() > 0) ) {
            // we still haven't been authorized for everything, keep on waiting
            req.waitForNextAllocation();
        }
        return read;
    }
    public long skip(long numBytes) throws IOException {
        long skip = in.skip(numBytes);
        FIFOBandwidthLimiter.Request req = null;
        if (_pullFromOutbound)
            req = _context.bandwidthLimiter().requestOutbound((int)skip, _peerSource);
        else
            req = _context.bandwidthLimiter().requestInbound((int)skip, _peerSource);
        
        while ( (req.getPendingInboundRequested() > 0) ||
                (req.getPendingOutboundRequested() > 0) ) {
            // we still haven't been authorized for everything, keep on waiting
            req.waitForNextAllocation();
        }
        return skip;
    }
}
