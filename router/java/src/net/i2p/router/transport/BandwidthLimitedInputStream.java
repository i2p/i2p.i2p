package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.RouterIdentity;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;

public class BandwidthLimitedInputStream extends FilterInputStream {
    private RouterIdentity _peer;
    public BandwidthLimitedInputStream(InputStream source, RouterIdentity peer) {
	super(source);
	_peer = peer;
    }
    
    public int read() throws IOException { 
	BandwidthLimiter.getInstance().delayInbound(_peer, 1);
	return in.read();
    }
    
    public int read(byte dest[]) throws IOException {
	int read = in.read(dest);
	BandwidthLimiter.getInstance().delayInbound(_peer, read);
	return read;
    }
    
    public int read(byte dest[], int off, int len) throws IOException { 
	int read = in.read(dest, off, len);
	BandwidthLimiter.getInstance().delayInbound(_peer, read);
	return read;
    }
    public long skip(long numBytes) throws IOException { 
	long skip = in.skip(numBytes);
	BandwidthLimiter.getInstance().delayInbound(_peer, (int)skip);
	return skip;
    }
}
