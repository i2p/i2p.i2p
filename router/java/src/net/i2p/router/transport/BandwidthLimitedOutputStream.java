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
    
    public BandwidthLimitedOutputStream(RouterContext context, OutputStream source, RouterIdentity peer) {
        super(source);
        _context = context;
        _peer = peer;
        _peerTarget = peer.getHash().toBase64();
        _log = context.logManager().getLog(BandwidthLimitedOutputStream.class);
    }
    
    public void write(int val) throws IOException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing a single byte!", new Exception("Single byte from..."));
        _context.bandwidthLimiter().requestOutbound(1, _peerTarget);
        out.write(val);
    }
    public void write(byte src[]) throws IOException {
        write(src, 0, src.length);
    }
    public void write(byte src[], int off, int len) throws IOException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing " + len + " bytes");
        if (src == null) return;
        if (len <= 0) return;
        if (len + off > src.length)
            throw new IllegalArgumentException("wtf are you thinking?  len=" + len 
                                               + ", off=" + off + ", data=" + src.length);
        _context.bandwidthLimiter().requestOutbound(len, _peerTarget);
        out.write(src, off, len);
    }
}
