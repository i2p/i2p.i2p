package net.i2p.sam;

import java.io.Closeable;

import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;

/**
 * Base interface for SAMMessageSession, which is the base for
 * v1/v3 datagram and raw sessions.
 * Also implemented by SAMStreamSession.
 *
 * @since 0.9.25 pulled from SAMMessageSession
 */
interface SAMMessageSess extends Closeable {

    /**
     * Start a SAM message-based session.
     * MUST be called after constructor.
     */
    public void start();

    /**
     * Close a SAM message-based session.
     */
    public void close();

    /**
     * Get the SAM message-based session Destination.
     *
     * @return The SAM message-based session Destination.
     */
    public Destination getDestination();

    /**
     * Send bytes through a SAM message-based session.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException;

    /**
     * Send bytes through a SAM message-based session.
     *
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                  throws DataFormatException, I2PSessionException;

    public int getListenProtocol();

    public int getListenPort();
}
