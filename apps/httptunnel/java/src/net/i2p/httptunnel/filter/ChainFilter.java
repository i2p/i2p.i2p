package net.i2p.httptunnel.filter;

import net.i2p.util.Log;

import java.io.*;
import java.util.*;

/**
 * Chain multiple filters. Decorator pattern...
 */
public class ChainFilter implements Filter {

    private static final Log _log = new Log(ChainFilter.class);
    
    public Collection filters;
    
    public ChainFilter(Collection filters) {
	this.filters=filters;
    }

    public byte[] filter(byte[] toFilter) {
	byte[] buf = toFilter;
	for (Iterator it = filters.iterator(); it.hasNext();) {
	    Filter f = (Filter) it.next();
	    buf = f.filter(buf);
	}
	return buf;
    }

    public byte[] finish() {
	// this is a bit complicated. Think about it...
	try {
	    byte[] buf = EMPTY;
	    for (Iterator it = filters.iterator(); it.hasNext();) {
		Filter f = (Filter) it.next();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		if (buf.length != 0) {
		    baos.write(f.filter(buf));
		}
		baos.write(f.finish());
	    buf = baos.toByteArray();
	    }
	    return buf;
	} catch (IOException ex) {
	    _log.error("Error chaining filters", ex);
	    return EMPTY;
	}
    }
	
}
