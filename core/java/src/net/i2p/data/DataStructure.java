package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the class as a standard object with particular bit representation,
 * exposing methods to read and write that representation.
 *<p>
 * Do not reuse objects.
 * Many of modifying methods contain checks to prevent
 * altering a DataStructure after it is initialized. This protects the netdb,
 * messages that contain DataStructures,
 * caches, and the object itself from simple causes of corruption, by
 * throwing IllegalStateExceptions.
 * These checks are not necessarily thread-safe, and are not guaranteed
 * to catch all possible means of corruption.
 * Beware of other avenues of corruption, such as directly modifying data
 * stored in byte[] objects.
 *</p>
 * 
 * @author jrandom
 */
public interface DataStructure /* extends Serializable */ {

    /**
     * Load up the current object with data from the given stream.  Data loaded 
     * this way must match the I2P data structure specification.
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     *
     * @param in stream to read from
     * @throws DataFormatException if the data is improperly formatted
     * @throws IOException if there was a problem reading the stream
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException;

    /**
     * Write out the data structure to the stream, using the format defined in the
     * I2P data structure specification.
     *
     * @param out stream to write to
     * @throws DataFormatException if the data was incomplete or not yet ready to be written
     * @throws IOException if there was a problem writing to the stream
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException;

    /** 
     * render the structure into modified base 64 notation
     * @return null on error
     */
    public String toBase64();

    /**
     * Load the structure from the base 64 encoded data provided
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     * Warning - many classes will throw IllegalArgumentException if data is the wrong size.
     *
     */
    public void fromBase64(String data) throws DataFormatException;

    /**
     *  @return may be null if data is not set
     */
    public byte[] toByteArray();

    /**
     * Load the structure from the data provided
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     * Warning - many classes will throw IllegalArgumentException if data is the wrong size.
     *
     */
    public void fromByteArray(byte data[]) throws DataFormatException;

    /**
     * Calculate the SHA256 value of this object (useful for a few scenarios)
     *
     * @return SHA256 hash, or null if there were problems (data format or io errors)
     */
    public Hash calculateHash();
}
