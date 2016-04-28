package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * SAM RAW session class.
 *
 * @author human
 */
class SAMRawSession extends SAMMessageSession {

    public static final int RAW_SIZE_MAX = 32*1024;

    // FIXME make final after fixing SAMv3DatagramSession override
    protected SAMRawReceiver recv;

    /**
     * Create a new SAM RAW session.
     *
     * @param dest Base64-encoded destination and private keys (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMRawSession(String dest, Properties props,
                         SAMRawReceiver recv) throws IOException, DataFormatException, I2PSessionException {
        super(dest, props);
        this.recv = recv;
    }

    /**
     * Create a new SAM RAW session.
     *
     * Caller MUST call start().
     *
     * @param destStream Input stream containing the destination and private keys (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    public SAMRawSession(InputStream destStream, Properties props,
                         SAMRawReceiver recv) throws IOException, DataFormatException, I2PSessionException {
        super(destStream, props);
        this.recv = recv;
    }

    /**
     * Create a new SAM RAW session on an existing I2P session.
     *
     * @param props unused for now
     * @since 0.9.25
     */
    protected SAMRawSession(I2PSession sess, Properties props, int listenProtocol, int listenPort,
                            SAMRawReceiver recv) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(sess, listenProtocol, listenPort);
        this.recv = recv;
    }

    /**
     * Send bytes through a SAM RAW session.
     *
     * @param data Bytes to be sent
     * @param proto if 0, will use PROTO_DATAGRAM_RAW (18)
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException {
        if (data.length > RAW_SIZE_MAX)
            throw new DataFormatException("Data size limit exceeded (" + data.length + ")");
        if (proto == I2PSession.PROTO_UNSPECIFIED)
            proto = I2PSession.PROTO_DATAGRAM_RAW;
        return sendBytesThroughMessageSession(dest, data, proto, fromPort, toPort);
    }

    /**
     * Send bytes through a SAM RAW session.
     *
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                 throws DataFormatException, I2PSessionException {
        if (data.length > RAW_SIZE_MAX)
            throw new DataFormatException("Data size limit exceeded (" + data.length + ")");
        if (proto == I2PSession.PROTO_UNSPECIFIED)
            proto = I2PSession.PROTO_DATAGRAM_RAW;
        return sendBytesThroughMessageSession(dest, data, proto, fromPort, toPort,
                                              sendLeaseSet, sendTags,tagThreshold, expiration);
    }

    protected void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
        try {
            recv.receiveRawBytes(msg, proto, fromPort, toPort);
        } catch (IOException e) {
            _log.error("Error forwarding message to receiver", e);
            close();
        }
    }

    protected void shutDown() {
        recv.stopRawReceiving();
    }
}
