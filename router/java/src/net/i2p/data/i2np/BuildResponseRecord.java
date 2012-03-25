package net.i2p.data.i2np;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
//import net.i2p.util.Log;

/**
 * Read and write the reply to a tunnel build message record.
 *
 * The reply record is the same size as the request record (528 bytes).
 * Bytes 0-526 contain random data.
 * Byte 527 contains the reply.
 */
public class BuildResponseRecord {

    /**
     * Create a new encrypted response
     *
     * @param status the response
     * @param responseMessageId unused except for debugging
     * @return a 528-byte response record
     */
    public static byte[] create(I2PAppContext ctx, int status, SessionKey replyKey, byte replyIV[], long responseMessageId) {
        //Log log = ctx.logManager().getLog(BuildResponseRecord.class);
        byte rv[] = new byte[TunnelBuildReplyMessage.RECORD_SIZE];
        ctx.random().nextBytes(rv);
        DataHelper.toLong(rv, TunnelBuildMessage.RECORD_SIZE-1, 1, status);
        // rv = AES(SHA256(padding+status) + padding + status, replyKey, replyIV)
        ctx.sha().calculateHash(rv, Hash.HASH_LENGTH, rv.length - Hash.HASH_LENGTH, rv, 0);
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug(responseMessageId + ": before encrypt: " + Base64.encode(rv, 0, 128) + " with " + replyKey.toBase64() + "/" + Base64.encode(replyIV));
        ctx.aes().encrypt(rv, 0, rv, 0, replyKey, replyIV, rv.length);
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug(responseMessageId + ": after encrypt: " + Base64.encode(rv, 0, 128));
        return rv;
    }
}
