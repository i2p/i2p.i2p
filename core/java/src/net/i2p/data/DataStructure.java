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
 * 
 * @author jrandom
 */
public interface DataStructure /* extends Serializable */ {
    /**
     * Load up the current object with data from the given stream.  Data loaded 
     * this way must match the I2P data structure specification.
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
     */
    public void fromBase64(String data) throws DataFormatException;

    public byte[] toByteArray();

    public void fromByteArray(byte data[]) throws DataFormatException;

    /**
     * Calculate the SHA256 value of this object (useful for a few scenarios)
     *
     * @return SHA256 hash, or null if there were problems (data format or io errors)
     */
    public Hash calculateHash();
}