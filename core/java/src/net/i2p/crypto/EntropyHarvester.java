package net.i2p.crypto;

/**
 * Allow various components with some entropy to feed that entropy back
 * into some PRNG.  The quality of the entropy provided varies, so anything
 * harvesting should discriminate based on the offered "source" of the 
 * entropy, silently discarding insufficient entropy sources.
 *
 */
public interface EntropyHarvester {
    /** 
     * Feed the entropy pools with data[offset:offset+len] 
     *
     * @param source origin of the entropy, allowing the harvester to 
     *               determine how much to value the data
     * @param offset index into the data array to start
     * @param len how many bytes to use
     */
    void feedEntropy(String source, byte data[], int offset, int len);
    /** 
     * Feed the entropy pools with the bits in the data
     *
     * @param source origin of the entropy, allowing the harvester to 
     *               determine how much to value the data
     * @param bitoffset bit index into the data array to start 
     *                  (using java standard big-endian)
     * @param bits how many bits to use
     */
    void feedEntropy(String source, long data, int bitoffset, int bits);
}
