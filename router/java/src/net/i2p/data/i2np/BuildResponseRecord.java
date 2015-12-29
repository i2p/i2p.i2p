package net.i2p.data.i2np;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
//import net.i2p.util.Log;

/**
 * Class that creates an encrypted tunnel build message record.
 *
 * The reply record is the same size as the request record (528 bytes).
 *
 * When decrypted:
 *
 *<pre>
 * Bytes 0-31 contain the hash of bytes 32-527
 * Bytes 32-526 contain random data.
 * Byte 527 contains the reply.
 *</pre>
 */
public class BuildResponseRecord {

    /**
     * Create a new encrypted response
     *
     * @param status the response 0-255
     * @param replyIV 16 bytes
     * @param responseMessageId unused except for debugging
     * @return a 528-byte response record
     */
    public static EncryptedBuildRecord create(I2PAppContext ctx, int status, SessionKey replyKey,
                                              byte replyIV[], long responseMessageId) {
        //Log log = ctx.logManager().getLog(BuildResponseRecord.class);
        byte rv[] = new byte[TunnelBuildReplyMessage.RECORD_SIZE];
        ctx.random().nextBytes(rv, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE - Hash.HASH_LENGTH - 1);
        DataHelper.toLong(rv, TunnelBuildMessage.RECORD_SIZE-1, 1, status);
        // rv = AES(SHA256(padding+status) + padding + status, replyKey, replyIV)
        ctx.sha().calculateHash(rv, Hash.HASH_LENGTH, rv.length - Hash.HASH_LENGTH, rv, 0);
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug(responseMessageId + ": before encrypt: " + Base64.encode(rv, 0, 128) + " with " + replyKey.toBase64() + "/" + Base64.encode(replyIV));
        ctx.aes().encrypt(rv, 0, rv, 0, replyKey, replyIV, rv.length);
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug(responseMessageId + ": after encrypt: " + Base64.encode(rv, 0, 128));
        return new EncryptedBuildRecord(rv);
    }
}
