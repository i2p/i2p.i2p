package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Decrypt a step in the tunnel, verifying the message in the process.
 *
 */
public class TunnelMessageProcessor {
    private static final int IV_SIZE = GatewayMessage.IV_SIZE;
    private static final int HOPS = GatewayMessage.HOPS;
    
    /**
     * Unwrap the tunnel message, overwriting it with the decrypted version.
     *
     * @param data full message received, written to while decrypted
     * @param send decrypted tunnel message, ready to send
     * @param layerKey session key to be used at the current layer
     * @return true if the message was valid, false if it was not.
     */
    public boolean unwrapMessage(I2PAppContext ctx, byte data[], SessionKey layerKey) {
        Log log = getLog(ctx);
        
        int payloadLength = data.length 
                            - IV_SIZE // IV
                            - HOPS * Hash.HASH_LENGTH // checksum blocks 
                            - Hash.HASH_LENGTH; // verification of the checksum blocks
        
        Hash recvPayloadHash = ctx.sha().calculateHash(data, IV_SIZE, payloadLength);
        if (log.shouldLog(Log.DEBUG))
            log.debug("H(recvPayload) = " + recvPayloadHash.toBase64());
        
        decryptMessage(ctx, data, layerKey);
        
        Hash payloadHash = ctx.sha().calculateHash(data, IV_SIZE, payloadLength);
        if (log.shouldLog(Log.DEBUG))
            log.debug("H(payload) = " + payloadHash.toBase64());
        
        boolean ok = verifyMessage(ctx, data, payloadHash);
        
        if (ok) {
            return true;
        } else {
            // no hashes were found that match the seen hash
            if (log.shouldLog(Log.DEBUG))
                log.debug("No hashes match");
            return false;
        }
    }
    
    private void decryptMessage(I2PAppContext ctx, byte data[], SessionKey layerKey) {
        Log log = getLog(ctx);
        if (log.shouldLog(Log.DEBUG))
            log.debug("IV[recv] = " + Base64.encode(data, 0, IV_SIZE));
        
        int numBlocks = (data.length - IV_SIZE) / IV_SIZE;
        // for debugging, so we can compare eIV
        int numPayloadBlocks = (data.length - IV_SIZE - 2 * IV_SIZE * (GatewayMessage.HOPS + 1)) / IV_SIZE;
        
        // prev == previous encrypted block (or IV for the first block)
        byte prev[] = new byte[IV_SIZE];
        // cur == current encrypted block (so we can overwrite the data in place)
        byte cur[] = new byte[IV_SIZE];
        System.arraycopy(data, 0, prev, 0, IV_SIZE);
        
        //decrypt the whole row
        for (int i = 0; i < numBlocks; i++) {
            int off = (i + 1) * IV_SIZE;
        
            if (i == numPayloadBlocks) {
                // should match the eIV
                if (log.shouldLog(Log.DEBUG))
                    log.debug("block[" + i + "].prev=" + Base64.encode(prev));
            }
            
            System.arraycopy(data, off, cur, 0, IV_SIZE);
            ctx.aes().decryptBlock(data, off, layerKey, data, off);
            DataHelper.xor(prev, 0, data, off, data, off, IV_SIZE);
            byte xf[] = prev;
            prev = cur;
            cur = xf;
        }
        
        // update the IV for the next layer
        ctx.aes().decryptBlock(data, 0, layerKey, data, 0);
        Hash h = ctx.sha().calculateHash(data, 0, IV_SIZE);
        System.arraycopy(h.getData(), 0, data, 0, IV_SIZE);
        
        if (log.shouldLog(Log.DEBUG)) {
            log.debug("IV[send] = " + Base64.encode(data, 0, IV_SIZE));
            log.debug("key = " + layerKey.toBase64());
        }
    }
    
    private boolean verifyMessage(I2PAppContext ctx, byte data[], Hash payloadHash) {
        Log log = getLog(ctx);
        int matchFound = -1;
        
        int off = data.length - (GatewayMessage.HOPS + 1) * Hash.HASH_LENGTH;
        for (int i = 0; i < GatewayMessage.HOPS; i++) {
            if (DataHelper.eq(payloadHash.getData(), 0, data, off, Hash.HASH_LENGTH)) {
                matchFound = i;
                break;
            }
            
            off += Hash.HASH_LENGTH;
        }
        
        if (log.shouldLog(Log.DEBUG)) {
            off = data.length - (GatewayMessage.HOPS + 1) * Hash.HASH_LENGTH;
            for (int i = 0; i < HOPS; i++)
                log.debug("checksum[" + i + "] = " + Base64.encode(data, off + i*Hash.HASH_LENGTH, Hash.HASH_LENGTH)
                          + (i == matchFound ? " * MATCH" : ""));
            
            log.debug("verification = " + Base64.encode(data, data.length - Hash.HASH_LENGTH, Hash.HASH_LENGTH));
        }
        
        return matchFound != -1;
    }

    /**
     * Determine whether the checksum block has been modified by comparing the final
     * verification hash to the hash of the block.
     * 
     * @return true if the checksum is valid, false if it has been modified
     */
    public boolean verifyChecksum(I2PAppContext ctx, byte message[]) {
        int checksumSize = GatewayMessage.HOPS * Hash.HASH_LENGTH;
        int offset = message.length - (checksumSize + Hash.HASH_LENGTH);
        Hash checksumHash = ctx.sha().calculateHash(message, offset, checksumSize);
        getLog(ctx).debug("Measured checksum: " + checksumHash.toBase64());
        byte expected[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(message, message.length-Hash.HASH_LENGTH, expected, 0, Hash.HASH_LENGTH);
        getLog(ctx).debug("Expected checksum: " + Base64.encode(expected));
        
        return DataHelper.eq(checksumHash.getData(), 0, message, message.length-Hash.HASH_LENGTH, Hash.HASH_LENGTH);
    }
    
    private static final Log getLog(I2PAppContext ctx) { 
        return ctx.logManager().getLog(TunnelMessageProcessor.class);
    }
}
