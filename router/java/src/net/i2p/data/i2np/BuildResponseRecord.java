package net.i2p.data.i2np;

import net.i2p.I2PAppContext;
import net.i2p.data.*;

/**
 * Read and write the reply to a tunnel build message record.
 *
 */
public class BuildResponseRecord {
    /**
     * Create a new encrypted response
     */
    public byte[] create(I2PAppContext ctx, int status, SessionKey replyKey, byte replyIV[]) {
        byte rv[] = new byte[TunnelBuildReplyMessage.RECORD_SIZE];
        ctx.random().nextBytes(rv);
        DataHelper.toLong(rv, TunnelBuildMessage.RECORD_SIZE-1, 1, status);
        // rv = AES(SHA256(padding+status) + padding + status, replyKey, replyIV)
        ctx.sha().calculateHash(rv, Hash.HASH_LENGTH, rv.length - Hash.HASH_LENGTH, rv, 0);
        ctx.aes().encrypt(rv, 0, rv, 0, replyKey, replyIV, rv.length);
        return rv;
    }
}
