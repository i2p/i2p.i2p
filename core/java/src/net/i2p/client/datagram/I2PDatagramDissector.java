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

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 * Class for dissecting I2P repliable datagrams, checking the authenticity of
 * the sender.  Note that objects of this class are NOT THREAD SAFE!
 *
 * @author human
 */
public final class I2PDatagramDissector {

    private static final int DGRAM_BUFSIZE = 32768;
    private static final int MIN_DGRAM_SIZE = 387 + 40;

    private final DSAEngine dsaEng = DSAEngine.getInstance();
    private final SHA256Generator hashGen = SHA256Generator.getInstance();

    private byte[] rxHash;
    private Signature rxSign;
    private Destination rxDest;
    private final byte[] rxPayload = new byte[DGRAM_BUFSIZE];
    private int rxPayloadLen;
    private boolean valid;

    /**
     * Crate a new I2P repliable datagram dissector.
     */
    public I2PDatagramDissector() { // nop
    }

    /**
     * Load an I2P repliable datagram into the dissector.
     * Does NOT verify the signature.
     *
     * Format is:
     * <ol>
     * <li>Destination (387+ bytes)
     * <li>Signature (40+ bytes, type and length as implied by signing key type in the Destination)
     * <li>Payload
     * </ol>
     *
     * For DSA_SHA1 Destinations, the signature is of the SHA-256 Hash of the payload.
     *
     * As of 0.9.14, for non-DSA_SHA1 Destinations, the signature is of the payload itself.
     *
     * @param dgram non-null I2P repliable datagram to be loaded
     *
     * @throws DataFormatException If there's an error in the datagram format
     */
    public void loadI2PDatagram(byte[] dgram) throws DataFormatException {
        // set invalid(very important!)
        this.valid = false;
        if (dgram.length < MIN_DGRAM_SIZE)
            throw new DataFormatException("repliable datagram too small: " + dgram.length);

        ByteArrayInputStream dgStream = new ByteArrayInputStream(dgram);
        
        try {
            // read destination
            rxDest = Destination.create(dgStream);
            SigType type = rxDest.getSigningPublicKey().getType();
            if (type == null)
                throw new DataFormatException("unsupported sig type");
            rxSign = new Signature(type);
            // read signature
            rxSign.readBytes(dgStream);
            
            // read payload
            rxPayloadLen = dgStream.read(rxPayload);
            
            // calculate the hash of the payload
            if (type == SigType.DSA_SHA1) {
                if (rxHash == null)
                    rxHash = new byte[Hash.HASH_LENGTH];
                // non-caching
                hashGen.calculateHash(rxPayload, 0, rxPayloadLen, rxHash, 0);
                //assert this.hashGen.calculateHash(this.extractPayload()).equals(this.rxHash);
            } else {
                rxHash = null;
            }
        } catch (IOException e) {
            // let the application do the logging
            //Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
            //log.error("Error loading datagram", e);
            throw new DataFormatException("Error loading datagram", e);
        //} catch(AssertionError e) {
        //    Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
        //    log.error("Assertion failed!", e);
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
        this.verifySignature();
        
        return this.extractPayload();
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
        this.verifySignature();
        
        return this.extractSender();
    }
    
    /**
     * Extract the hash of the payload of an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), verifying the datagram
     * signature.
     *
     * As of 0.9.14, for signature types other than DSA_SHA1, this returns null.
     *
     * @return The hash of the payload of the I2P repliable datagram
     * @throws I2PInvalidDatagramException if the signature verification fails
     */
    public Hash getHash() throws I2PInvalidDatagramException {
        // make sure it has a valid signature
        this.verifySignature();
        return extractHash();
    }
    
    /**
     * Extract the payload carried by an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), without verifying the
     * datagram signature.
     *
     * @return A byte array containing the datagram payload
     */
    public byte[] extractPayload() {
        byte[] retPayload = new byte[this.rxPayloadLen];
        System.arraycopy(this.rxPayload, 0, retPayload, 0, this.rxPayloadLen);
        
        return retPayload;
    }
    
    /**
     * Extract the sender of an I2P repliable datagram (previously loaded with
     * the loadI2PDatagram() method), without verifying the datagram signature.
     *
     * @return The Destination of the I2P repliable datagram sender
     */
    public Destination extractSender() {
      /****
        if (this.rxDest == null)
            return null;
        Destination retDest = new Destination();
        try {
            retDest.fromByteArray(this.rxDest.toByteArray());
        } catch (DataFormatException e) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2PDatagramDissector.class);
            log.error("Caught DataFormatException", e);
            return null;
        }
        
        return retDest;
      ****/
        // dests are no longer modifiable
        return rxDest;
    }
    
    /**
     * Extract the hash of the payload of an I2P repliable datagram (previously
     * loaded with the loadI2PDatagram() method), without verifying the datagram
     * signature.
     *
     * As of 0.9.14, for signature types other than DSA_SHA1, this returns null.
     *
     * @return The hash of the payload of the I2P repliable datagram
     */
    public Hash extractHash() {
        if (rxHash == null)
            return null;
        // make a copy as we will reuse rxHash
        byte[] hash = new byte[Hash.HASH_LENGTH];
        System.arraycopy(rxHash, 0, hash, 0, Hash.HASH_LENGTH);
        return new Hash(hash);
    }
    
    /**
     * Verify the signature of this datagram (previously loaded with the
     * loadI2PDatagram() method)
     * @throws I2PInvalidDatagramException if the signature is invalid
     */
    public void verifySignature() throws I2PInvalidDatagramException {
        // first check if it already got validated
        if(this.valid)
            return;
        
        if (rxSign == null || rxSign.getData() == null || rxDest == null)
            throw new I2PInvalidDatagramException("Datagram not yet read");

        // now validate
        SigningPublicKey spk = rxDest.getSigningPublicKey();
        SigType type = spk.getType();
        if (type == null)
            throw new I2PInvalidDatagramException("unsupported sig type");
        if (type == SigType.DSA_SHA1) {
            if (!this.dsaEng.verifySignature(rxSign, rxHash, spk))
                throw new I2PInvalidDatagramException("Incorrect I2P repliable datagram signature");
        } else {
            if (!this.dsaEng.verifySignature(rxSign, rxPayload, 0, rxPayloadLen, spk))
                throw new I2PInvalidDatagramException("Incorrect I2P repliable datagram signature");
        }
        
        // set validated
        this.valid = true;
    }
}
