package net.i2p.httptunnel.filter;

/**
 * A filter letting everything pass as is.
 */
public class NullFilter implements Filter {

    /* (non-Javadoc)
     * @see net.i2p.httptunnel.filter.Filter#filter(byte[])
     */
    public byte[] filter(byte[] toFilter) {
	return toFilter;
    }

    /* (non-Javadoc)
     * @see net.i2p.httptunnel.filter.Filter#finish()
     */
    public byte[] finish() {
	return EMPTY;
    }
}
