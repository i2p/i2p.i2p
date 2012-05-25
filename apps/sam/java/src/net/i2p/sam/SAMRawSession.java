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

import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * SAM RAW session class.
 *
 * @author human
 */
public class SAMRawSession extends SAMMessageSession {

    private final static Log _log = new Log(SAMRawSession.class);
    public static final int RAW_SIZE_MAX = 32*1024;

    protected SAMRawReceiver recv = null;
    /**
     * Create a new SAM RAW session.
     *
     * @param dest Base64-encoded destination (private key)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    public SAMRawSession(String dest, Properties props,
                         SAMRawReceiver recv) throws IOException, DataFormatException, I2PSessionException {
        super(dest, props);

        this.recv = recv;
    }

    /**
     * Create a new SAM RAW session.
     *
     * @param destStream Input stream containing the destination keys
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
     * Send bytes through a SAM RAW session.
     *
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException 
     */
    public boolean sendBytes(String dest, byte[] data) throws DataFormatException {
        if (data.length > RAW_SIZE_MAX)
            throw new DataFormatException("Data size limit exceeded (" + data.length + ")");
        return sendBytesThroughMessageSession(dest, data);
    }

    protected void messageReceived(byte[] msg) {
        try {
            recv.receiveRawBytes(msg);
        } catch (IOException e) {
            _log.error("Error forwarding message to receiver", e);
            close();
        }
    }

    protected void shutDown() {
        recv.stopRawReceiving();
    }
}
