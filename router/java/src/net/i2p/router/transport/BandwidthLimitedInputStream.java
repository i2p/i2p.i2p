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
        _peerSource = peer.getHash().toBase64();
        _pullFromOutbound = pullFromOutbound;
    }
    
    public int read() throws IOException {
        if (_pullFromOutbound)
            _context.bandwidthLimiter().requestOutbound(1, _peerSource);
        else
            _context.bandwidthLimiter().requestInbound(1, _peerSource);
        return in.read();
    }
    
    public int read(byte dest[]) throws IOException {
        int read = in.read(dest);
        if (_pullFromOutbound)
            _context.bandwidthLimiter().requestOutbound(read, _peerSource);
        else
            _context.bandwidthLimiter().requestInbound(read, _peerSource);
        return read;
    }
    
    public int read(byte dest[], int off, int len) throws IOException {
        int read = in.read(dest, off, len);
        if (_pullFromOutbound)
            _context.bandwidthLimiter().requestOutbound(read, _peerSource);
        else
            _context.bandwidthLimiter().requestInbound(read, _peerSource);
        return read;
    }
    public long skip(long numBytes) throws IOException {
        long skip = in.skip(numBytes);
        if (_pullFromOutbound)
            _context.bandwidthLimiter().requestOutbound((int)skip, _peerSource);
        else
            _context.bandwidthLimiter().requestInbound((int)skip, _peerSource);
        return skip;
    }
}
