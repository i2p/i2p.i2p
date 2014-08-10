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

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.crypto.SigType;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Class for creating I2P repliable datagrams.  Note that objects of this class
 * are NOT THREAD SAFE!
 *
 * @author human
 */
public final class I2PDatagramMaker {

    private static final int DGRAM_BUFSIZE = 32768;

    private final SHA256Generator hashGen = SHA256Generator.getInstance();
    private final DSAEngine dsaEng = DSAEngine.getInstance();

    private SigningPrivateKey sxPrivKey;
    private byte[] sxDestBytes;

    private final ByteArrayOutputStream sxDGram = new ByteArrayOutputStream(DGRAM_BUFSIZE);

    /**
     * Construct a new I2PDatagramMaker that will be able to create I2P
     * repliable datagrams going to be sent through the specified I2PSession.
     *
     * @param session I2PSession used to send I2PDatagrams through
     */
    public I2PDatagramMaker(I2PSession session) {
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
     * Format is:
     * <ol>
     * <li>Destination (387+ bytes)
     * <li>Signature (40+ bytes, type and length as implied by signing key type in the Destination)
     * <li>Payload
     * </ol>
     *
     * Maximum datagram size is 32768, so maximum payload size is 32341, or less for
     * non-DSA_SHA1 destinations. Practical maximum is a few KB less due to
     * ElGamal/AES overhead. 10 KB or less is recommended for best results.
     *
     * For DSA_SHA1 Destinations, the signature is of the SHA-256 Hash of the payload.
     *
     * As of 0.9.14, for non-DSA_SHA1 Destinations, the signature is of the payload itself.
     *
     * @param payload non-null Bytes to be contained in the I2P datagram.
     * @return null on error
     * @throws IllegalArgumentException if payload is too big
     * @throws IllegalStateException if Destination signature type unsupported
     */
    public byte[] makeI2PDatagram(byte[] payload) {
        sxDGram.reset();
        
        try {
            sxDGram.write(sxDestBytes);
            SigType type = sxPrivKey.getType();
            if (type == null)
                throw new IllegalStateException("Unsupported sig type");
            
            Signature sig;
            if (type == SigType.DSA_SHA1) {
                byte[] hash = SimpleByteCache.acquire(Hash.HASH_LENGTH);
                // non-caching
                hashGen.calculateHash(payload, 0, payload.length, hash, 0);
                sig = dsaEng.sign(hash, sxPrivKey);
                SimpleByteCache.release(hash);
            } else {
                sig = dsaEng.sign(payload, sxPrivKey);
            }
            sig.writeBytes(sxDGram);
            sxDGram.write(payload);
            if (sxDGram.size() > DGRAM_BUFSIZE)
                throw new IllegalArgumentException("Too big");
            return sxDGram.toByteArray();
        } catch (IOException e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramMaker.class);
            log.error("Caught IOException", e);
            return null;
        } catch (DataFormatException e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramMaker.class);
            log.error("Caught DataFormatException", e);
            return null;
        }
    }
}
