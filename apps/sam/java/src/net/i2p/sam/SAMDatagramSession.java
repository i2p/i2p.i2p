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
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * SAM DATAGRAM session class.
 *
 * @author human
 */
class SAMDatagramSession extends SAMMessageSession {

    public static final int DGRAM_SIZE_MAX = 31*1024;

    // FIXME make final after fixing SAMv3DatagramSession override
    protected SAMDatagramReceiver recv;
    private final I2PDatagramMaker dgramMaker;
    private final I2PDatagramDissector dgramDissector = new I2PDatagramDissector();

    /**
     * Create a new SAM DATAGRAM session.
     *
     * @param dest Base64-encoded destination (private key)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMDatagramSession(String dest, Properties props,
                              SAMDatagramReceiver recv) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(dest, props);
        this.recv = recv;
        dgramMaker = new I2PDatagramMaker(getI2PSession());
    }

    /**
     * Create a new SAM DATAGRAM session.
     *
     * Caller MUST call start().
     *
     * @param destStream Input stream containing the destination keys
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    public SAMDatagramSession(InputStream destStream, Properties props,
                              SAMDatagramReceiver recv) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(destStream, props);
        this.recv = recv;
        dgramMaker = new I2PDatagramMaker(getI2PSession());
    }

    /**
     * Create a new SAM DATAGRAM session on an existing I2P session.
     *
     * @param props unused for now
     * @since 0.9.25
     */
    protected SAMDatagramSession(I2PSession sess, Properties props, int listenPort,
                              SAMDatagramReceiver recv) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(sess, I2PSession.PROTO_DATAGRAM, listenPort);
        this.recv = recv;
        dgramMaker = new I2PDatagramMaker(getI2PSession());
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     * @param proto ignored, will always use PROTO_DATAGRAM (17)
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        byte[] dgram ;
        synchronized (dgramMaker) {
            dgram = dgramMaker.makeI2PDatagram(data);
        }
        return sendBytesThroughMessageSession(dest, dgram, I2PSession.PROTO_DATAGRAM, fromPort, toPort);
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                 throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        byte[] dgram ;
        synchronized (dgramMaker) {
            dgram = dgramMaker.makeI2PDatagram(data);
        }
        return sendBytesThroughMessageSession(dest, dgram, I2PSession.PROTO_DATAGRAM, fromPort, toPort,
                                              sendLeaseSet, sendTags,tagThreshold, expiration);
    }

    protected void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
        byte[] payload;
        Destination sender;
        try {
            synchronized (dgramDissector) {
                dgramDissector.loadI2PDatagram(msg);
                sender = dgramDissector.getSender();
                payload = dgramDissector.extractPayload();
            }
        } catch (DataFormatException e) {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Dropping ill-formatted I2P repliable datagram", e);
            }
            return;
        } catch (I2PInvalidDatagramException e) {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Dropping ill-signed I2P repliable datagram", e);
            }
            return;
        }

        try {
            recv.receiveDatagramBytes(sender, payload, proto, fromPort, toPort);
        } catch (IOException e) {
            _log.error("Error forwarding message to receiver", e);
            close();
        }
    }

    protected void shutDown() {
        recv.stopDatagramReceiving();
    }
}
