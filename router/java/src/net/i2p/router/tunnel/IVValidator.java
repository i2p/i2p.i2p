package net.i2p.router.tunnel;

/**
 * Provide a generic interface for IV validation which may be implemented
 * through something as simple as a hashtable or more a complicated 
 * bloom filter.
 *
 */
public interface IVValidator {
    /** 
     * receive the IV for the tunnel, returning true if it is valid,
     * or false if it has already been used (or is otherwise invalid).
     *
     */
    public boolean receiveIV(byte iv[]);
}
