package net.i2p.client.datagram;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.client.I2PSession;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataFormatException;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.Log;

/**
 * Class for creating I2P repliable datagrams.  Note that objects of this class
 * are NOT THREAD SAFE!
 *
 * @author human
 */
public final class I2PDatagramMaker {

    private static Log _log = new Log(I2PDatagramMaker.class);

    private static final int DGRAM_BUFSIZE = 32768;

    private final SHA256Generator hashGen = SHA256Generator.getInstance();
    private final DSAEngine dsaEng = DSAEngine.getInstance();

    private SigningPrivateKey sxPrivKey = null;
    private byte[] sxDestBytes = null;

    private final ByteArrayOutputStream sxDGram = new ByteArrayOutputStream(DGRAM_BUFSIZE);

    /**
     * Construct a new I2PDatagramMaker that will be able to create I2P
     * repliable datagrams going to be sent through the specified I2PSession.
     *
     * @param session I2PSession used to send I2PDatagrams through
     */
    public I2PDatagramMaker(I2PSession session) {
        this();
        this.setI2PDatagramMaker(session);
    }
    /**
     * Construct a new I2PDatagramMaker that is null.
     * Use setI2PDatagramMaker to set the parameters.
     */
    public I2PDatagramMaker() {
        // nop
    }

    public void setI2PDatagramMaker(I2PSession session) {
        sxPrivKey = session.getPrivateKey();
        sxDestBytes = session.getMyDestination().toByteArray();
    }
    /**
     * Make a repliable I2P datagram containing the specified payload.
     *
     * @param payload Bytes to be contained in the I2P datagram.
     */
    public byte[] makeI2PDatagram(byte[] payload) {
        sxDGram.reset();
        
        try {
            sxDGram.write(sxDestBytes);
        
            dsaEng.sign(hashGen.calculateHash(payload).toByteArray(),
                        sxPrivKey).writeBytes(sxDGram);

            sxDGram.write(payload);

            return sxDGram.toByteArray();
        } catch (IOException e) {
            _log.error("Caught IOException", e);
            return null;
        } catch (DataFormatException e) {
            _log.error("Caught DataFormatException", e);
            return null;
        }
    }
}
