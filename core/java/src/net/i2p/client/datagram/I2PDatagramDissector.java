package net.i2p.client.datagram;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;

import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.util.Log;

/**
 * Class for dissecting I2P repliable datagrams, checking the authenticity of
 * the sender.  Note that objects of this class are NOT THREAD SAFE!
 *
 * @author human
 */
public final class I2PDatagramDissector {

    private static Log _log = new Log(I2PDatagramDissector.class);

    private static int DGRAM_BUFSIZE = 32768;

    private DSAEngine dsaEng = DSAEngine.getInstance();
    private SHA256Generator hashGen = SHA256Generator.getInstance();

    private byte[] rxHashBytes = null;

    private Signature rxSign = new Signature();

    private Destination rxDest = new Destination();

    private byte[] rxPayload = new byte[DGRAM_BUFSIZE];

    private int rxPayloadLen = 0;

    /**
     * Crate a new I2P repliable datagram dissector.
     */
    public I2PDatagramDissector() {}

    /**
     * Load an I2P repliable datagram into the dissector.
     *
     * @param dgram I2P repliable datagram to be loader
     *
     * @throws DataFormatException If there's an error in the datagram format
     */
    public void loadI2PDatagram(byte[] dgram) throws DataFormatException {
        ByteArrayInputStream dgStream = new ByteArrayInputStream(dgram);
        byte[] rxTrimmedPayload;

        try {
            rxDest.readBytes(dgStream);

            rxSign.readBytes(dgStream);

            rxPayloadLen = dgStream.read(rxPayload);

            // FIXME: hashGen.calculateHash(source, offset, len) would rock...
            rxTrimmedPayload = new byte[rxPayloadLen];
            System.arraycopy(rxPayload, 0, rxTrimmedPayload, 0, rxPayloadLen);
            
            rxHashBytes =hashGen.calculateHash(rxTrimmedPayload).toByteArray();
        } catch (IOException e) {
            _log.error("Caught IOException - INCONSISTENT STATE!", e);
        }

        //_log.debug("Datagram payload size: " + rxPayloadLen + "; content:\n"
        //           + HexDump.dump(rxPayload, 0, rxPayloadLen));
    }

    /**
     * Get the payload carried by an I2P repliable datagram (previously loaded
     * with the loadI2PDatagram() method), verifying the datagram signature.
     *
     * @return A byte array containing the datagram payload
     *
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public byte[] getPayload() throws I2PInvalidDatagramException {
        if (!dsaEng.verifySignature(rxSign, rxHashBytes,
                                    rxDest.getSigningPublicKey())) {
            throw new I2PInvalidDatagramException("Incorrect I2P repliable datagram signature");
        }

        byte[] retPayload = new byte[rxPayloadLen];
        System.arraycopy(rxPayload, 0, retPayload, 0, rxPayloadLen);

        return retPayload;
    }

    /**
     * Get the sender of an I2P repliable datagram (previously loaded with the
     * loadI2PDatagram() method), verifying the datagram signature.
     *
     * @return The Destination of the I2P repliable datagram sender
     *
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public Destination getSender() throws I2PInvalidDatagramException {
        if (!dsaEng.verifySignature(rxSign, rxHashBytes,
                                    rxDest.getSigningPublicKey())) {
            throw new I2PInvalidDatagramException("Incorrect I2P repliable datagram signature");
        }

        Destination retDest = new Destination();
        try {
            retDest.fromByteArray(rxDest.toByteArray());
        } catch (DataFormatException e) {
            _log.error("Caught DataFormatException", e);
            return null;
        }

        return retDest;
    }

    /**
     * Extract the payload carried by an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), without verifying the
     * datagram signature.
     *
     * @return A byte array containing the datagram payload
     */
    public byte[] extractPayload() {
        byte[] retPayload = new byte[rxPayloadLen];
        System.arraycopy(rxPayload, 0, retPayload, 0, rxPayloadLen);

        return retPayload;
    }

    /**
     * Extract the sender of an I2P repliable datagram (previously loaded with
     * the loadI2PDatagram() method), without verifying the datagram signature.
     *
     * @return The Destination of the I2P repliable datagram sender
     */
    public Destination extractSender() {
        Destination retDest = new Destination();
        try {
            retDest.fromByteArray(rxDest.toByteArray());
        } catch (DataFormatException e) {
            _log.error("Caught DataFormatException", e);
            return null;
        }
        
        return retDest;
    }
}
