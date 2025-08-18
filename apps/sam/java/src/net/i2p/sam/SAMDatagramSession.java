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

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.datagram.Datagram2;
import net.i2p.client.datagram.Datagram3;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * SAM DATAGRAM session class.
 * Supports DG2/3 as of 0.9.68
 *
 * @author human
 */
class SAMDatagramSession extends SAMMessageSession {

    public static final int DGRAM_SIZE_MAX = 31*1024;

    // FIXME make final after fixing SAMv3DatagramSession override
    protected SAMDatagramReceiver recv;
    private final I2PDatagramMaker dgramMaker;
    private final I2PDatagramDissector dgramDissector;
    private final int version;

    /**
     * Create a new SAM DATAGRAM session.
     * v1/v2 (DG1 only) or v3 (DG 1/2/3)
     *
     * @param dest Base64-encoded destination (private key)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @param v datagram version 1/2/3
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMDatagramSession(String dest, Properties props,
                              SAMDatagramReceiver recv, int v) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(dest, props);
        if (v == 1) {
            dgramMaker = new I2PDatagramMaker(getI2PSession());
            dgramDissector = new I2PDatagramDissector();
        } else if (v == 2 || v == 3) {
            dgramMaker = null;
            dgramDissector = null;
        } else {
            throw new IllegalArgumentException("Bad version: " + v);
        }
        version = v;
    }

    /**
     * Create a new SAM DATAGRAM session.
     * v1/v2 only, DG1 only
     *
     * Caller MUST call start().
     *
     * @param destStream Input stream containing the destination keys
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     * @deprecated unused
     */
    @Deprecated
    public SAMDatagramSession(InputStream destStream, Properties props,
                              SAMDatagramReceiver recv) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(destStream, props);
        this.recv = recv;
        dgramMaker = new I2PDatagramMaker(getI2PSession());
        dgramDissector = new I2PDatagramDissector();
        version = 1;
    }

    /**
     * Create a new SAM DATAGRAM session on an existing I2P session.
     * v3 only, DG 1/2/3
     *
     * @param v datagram version 1/2/3
     * @since 0.9.25
     */
    protected SAMDatagramSession(I2PSession sess, Properties props, int listenPort,
                              SAMDatagramReceiver recv, int v) throws IOException, 
                              DataFormatException, I2PSessionException {
        super(sess, I2PSession.PROTO_DATAGRAM, listenPort);
        this.recv = recv;
        if (v == 1) {
            dgramMaker = new I2PDatagramMaker(getI2PSession());
            dgramDissector = new I2PDatagramDissector();
        } else if (v == 2 || v == 3) {
            dgramMaker = null;
            dgramDissector = null;
        } else {
            throw new IllegalArgumentException("Bad version: " + v);
        }
        version = v;
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     * @param proto ignored, will always use PROTO_DATAGRAM (17), PROTO_DATAGRAM2 (19), or PROTO_DATAGRAM3 (20)
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort) throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        byte[] dgram;
        if (version == 1) {
            synchronized (dgramMaker) {
                dgram = dgramMaker.makeI2PDatagram(data);
            }
            proto = I2PSession.PROTO_DATAGRAM;
        } else if (version == 2) {
            Hash h = new Destination(dest).calculateHash();
            dgram = Datagram2.make(I2PAppContext.getGlobalContext(), getI2PSession(), data, h);
            proto = I2PSession.PROTO_DATAGRAM2;
        } else {
            dgram = Datagram3.make(I2PAppContext.getGlobalContext(), getI2PSession(), data);
            proto = I2PSession.PROTO_DATAGRAM3;
        }
        return sendBytesThroughMessageSession(dest, dgram, proto, fromPort, toPort);
    }

    /**
     * Send bytes through a SAM DATAGRAM session.
     *
     * @param proto ignored, will always use PROTO_DATAGRAM (17), PROTO_DATAGRAM2 (19), or PROTO_DATAGRAM3 (20)
     * @since 0.9.25
     */
    public boolean sendBytes(String dest, byte[] data, int proto,
                             int fromPort, int toPort,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                 throws DataFormatException, I2PSessionException {
        if (data.length > DGRAM_SIZE_MAX)
            throw new DataFormatException("Datagram size exceeded (" + data.length + ")");
        byte[] dgram;
        if (version == 1) {
            synchronized (dgramMaker) {
                dgram = dgramMaker.makeI2PDatagram(data);
            }
            proto = I2PSession.PROTO_DATAGRAM;
        } else if (version == 2) {
            Hash h = new Destination(dest).calculateHash();
            dgram = Datagram2.make(I2PAppContext.getGlobalContext(), getI2PSession(), data, h);
            proto = I2PSession.PROTO_DATAGRAM2;
        } else {
            dgram = Datagram3.make(I2PAppContext.getGlobalContext(), getI2PSession(), data);
            proto = I2PSession.PROTO_DATAGRAM3;
        }
        return sendBytesThroughMessageSession(dest, dgram, proto, fromPort, toPort,
                                              sendLeaseSet, sendTags,tagThreshold, expiration);
    }

    protected void messageReceived(byte[] msg, int proto, int fromPort, int toPort) {
        byte[] payload;
        Destination sender;
        Hash h;
        try {
            if (version == 1 && proto == I2PSession.PROTO_DATAGRAM) {
                synchronized (dgramDissector) {
                    dgramDissector.loadI2PDatagram(msg);
                    sender = dgramDissector.getSender();
                    payload = dgramDissector.extractPayload();
                }
                h = null;
            } else if (version == 2 && proto == I2PSession.PROTO_DATAGRAM2) {
                Datagram2 dg = Datagram2.load(I2PAppContext.getGlobalContext(), getI2PSession(), msg);
                sender = dg.getSender();
                payload = dg.getPayload();
                h = null;
            } else if (version == 3 && proto == I2PSession.PROTO_DATAGRAM3) {
                Datagram3 dg = Datagram3.load(I2PAppContext.getGlobalContext(), getI2PSession(), msg);
                sender = null;
                payload = dg.getPayload();
                h = dg.getSender();
            } else {
                if (_log.shouldDebug())
                    _log.debug("Dropping mismatched protocol, datagram version=" + version + " proto=" + proto);
                return;
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
            if (sender != null) {
                // DG 1/2
                recv.receiveDatagramBytes(sender, payload, proto, fromPort, toPort);
            } else {
                // DG 3
                recv.receiveDatagramBytes(h, payload, proto, fromPort, toPort);
            }
        } catch (IOException e) {
            _log.error("Error forwarding message to receiver", e);
            close();
        }
    }

    protected void shutDown() {
        recv.stopDatagramReceiving();
    }
}
