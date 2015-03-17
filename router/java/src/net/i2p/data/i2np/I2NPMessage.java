package net.i2p.data.i2np;
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

import net.i2p.data.DataStructure;

/**
 * Base interface for all I2NP messages
 *
 * @author jrandom
 */
public interface I2NPMessage extends DataStructure {
    final long MAX_ID_VALUE = (1l<<32l)-1l;
    final int MAX_SIZE = 64*1024; // insane
    
    /**
     * Read the body into the data structures, after the initial type byte, using
     * the current class's format as defined by the I2NP specification
     *
     * Unused - All transports provide encapsulation and so we have byte arrays available.
     *
     * @param in stream to read from
     *           starting at type if type is < 0 (16 byte header)
     *           starting at ID if type is >= 0 (15 byte header)
     * @param type I2NP message type. If less than zero, read the type from data
     * @param buffer scratch buffer to be used when reading and parsing
     * @return size of the message read (including headers)
     * @throws I2NPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     * @deprecated unused
     */
    public int readBytes(InputStream in, int type, byte buffer[]) throws I2NPMessageException, IOException;

    /**
     * Read the body into the data structures, after the initial type byte, using
     * the current class's format as defined by the I2NP specification
     *
     * @param data the data
     * @param type I2NP message type. If less than zero, read the type from data
     * @param offset where to start
     *           starting at type if type is < 0 (16 byte header)
     *           starting at ID if type is >= 0 (15 byte header)
     * @return size of the message read (including headers)
     * @throws I2NPMessageException if there is no valid message
     * @throws IOException if there is a problem reading from the stream
     */
    public int readBytes(byte data[], int type, int offset) throws I2NPMessageException;

    /**
     * Read the body into the data structures, after the initial type byte, using
     * the current class's format as defined by the I2NP specification
     *
     * @param data the data, may or may not include the type
     * @param type I2NP message type. If less than zero, read the type from data
     * @param offset where to start
     *           starting at type if type is < 0 (16 byte header)
     *           starting at ID if type is >= 0 (15 byte header)
     * @param maxLen read no more than this many bytes from data starting at offset, even if it is longer
     *               This includes the type byte only if type < 0
     * @return size of the message read (including headers)
     * @throws I2NPMessageException if there is no valid message
     * @throws IOException if there is a problem reading from the stream
     * @since 0.8.12
     */
    public int readBytes(byte data[], int type, int offset, int maxLen) throws I2NPMessageException;

    /**
     * Read the body into the data structures, after the initial type byte and
     * the uniqueId / expiration, using the current class's format as defined by
     * the I2NP specification
     *
     * @param data data to read from
     * @param offset where to start in the data array
     * @param dataSize how long into the data to read
     * @param type I2NP message type
     * @throws I2NPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException;
    public void readMessage(byte data[], int offset, int dataSize, int type, I2NPMessageHandler handler) throws I2NPMessageException;
    
    /**
     * Return the unique identifier for this type of I2NP message, as defined in
     * the I2NP spec
     */
    public int getType();
    
    /**
     * Replay resistant message ID
     */
    public long getUniqueId(); 
    public void setUniqueId(long id);
    
    /**
     * Date after which the message should be dropped (and the associated uniqueId forgotten)
     *
     */
    public long getMessageExpiration();
    public void setMessageExpiration(long exp);

    
    /** How large the message is, including any checksums, i.e. full 16 byte header */
    public int getMessageSize();

    /** How large the raw message is with the short 5 byte header */
    public int getRawMessageSize();

    /** 
     * write the message to the buffer, returning the number of bytes written.
     * the data is formatted so as to be self contained, with the type, size,
     * expiration, unique id, as well as a checksum bundled along.  
     * Full 16 byte header.
     */
    public int toByteArray(byte buffer[]);

    /**
     * write the message to the buffer, returning the number of bytes written.
     * the data is is not self contained - it does not include the size,
     * unique id, or any checksum, but does include the type and expiration.
     * Short 5 byte header.
     */
    public int toRawByteArray(byte buffer[]);
}
