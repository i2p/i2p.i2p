package net.i2p.router.tunnel;

/**
 * Provide a generic interface for IV validation which may be implemented
 * through something as simple as a hashtable or more a complicated 
 * bloom filter.
 *
 */
public interface IVValidator {
    /** 
     * receive the IV for the tunnel message, returning true if it is valid,
     * or false if it has already been used (or is otherwise invalid).  To
     * prevent colluding attackers from successfully tagging the tunnel by
     * switching the IV and the first block of the message, the validator should
     * treat the XOR of the IV and the first block as the unique identifier, 
     * not the IV alone (since the tunnel is encrypted via AES/CBC).  Thanks to
     * dvorak for pointing out that tagging!
     *
     */
    public boolean receiveIV(byte iv[], int ivOffset, byte payload[], int payloadOffset);
}
