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
    private RouterContext _context;
    public BandwidthLimitedInputStream(RouterContext context, InputStream source, RouterIdentity peer) {
        super(source);
        _context = context;
        _peer = peer;
    }
    
    public int read() throws IOException {
        _context.bandwidthLimiter().delayInbound(_peer, 1);
        return in.read();
    }
    
    public int read(byte dest[]) throws IOException {
        int read = in.read(dest);
        _context.bandwidthLimiter().delayInbound(_peer, read);
        return read;
    }
    
    public int read(byte dest[], int off, int len) throws IOException {
        int read = in.read(dest, off, len);
        _context.bandwidthLimiter().delayInbound(_peer, read);
        return read;
    }
    public long skip(long numBytes) throws IOException {
        long skip = in.skip(numBytes);
        _context.bandwidthLimiter().delayInbound(_peer, (int)skip);
        return skip;
    }
}
