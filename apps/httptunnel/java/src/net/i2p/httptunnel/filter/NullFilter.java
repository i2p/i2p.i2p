package net.i2p.httptunnel.filter;

/**
 * A filter letting everything pass as is.
 */
public class NullFilter implements Filter {

    public byte[] filter(byte[] toFilter) {
	return toFilter;
    }

    public byte[] finish() {
	return EMPTY;
    }
}
