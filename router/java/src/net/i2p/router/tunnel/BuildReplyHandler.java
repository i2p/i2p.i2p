package net.i2p.router.tunnel;

import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.util.Log;

/**
 * Decrypt the layers of a tunnel build reply message, determining whether the individual 
 * hops agreed to participate in the tunnel, or if not, why not.
 *
 */
public class BuildReplyHandler {
    public BuildReplyHandler() {}

    /**
     * Decrypt the tunnel build reply records.  This overwrites the contents of the reply
     *
     * @return status for the records (in record order), or null if the replies were not valid.  Fake records
     *         always have 0 as their value
     */
    public int[] decrypt(I2PAppContext ctx, TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, List recordOrder) {
        int rv[] = new int[TunnelBuildReplyMessage.RECORD_COUNT];
        for (int i = 0; i < rv.length; i++) {
            int hop = ((Integer)recordOrder.get(i)).intValue();
            int ok = decryptRecord(ctx, reply, cfg, i, hop);
            if (ok == -1) return null;
            rv[i] = ok;
        }
        return rv;
    }

    /**
     * Decrypt the record (removing the layers of reply encyption) and read out the status
     *
     * @return -1 on decrypt failure
     */
    private int decryptRecord(I2PAppContext ctx, TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, int recordNum, int hop) {
        Log log = ctx.logManager().getLog(getClass());
        if (hop >= cfg.getLength()) {
            if (log.shouldLog(Log.DEBUG))
                log.debug("Record " + recordNum + "/" + hop + " is fake, so consider it valid...");
            return 0;
        }
        ByteArray rec = reply.getRecord(recordNum);
        int off = rec.getOffset();
        for (int j = cfg.getLength() - 1; j >= hop; j--) {
            HopConfig hopConfig = cfg.getConfig(j);
            SessionKey replyKey = hopConfig.getReplyKey();
            byte replyIV[] = hopConfig.getReplyIV().getData();
            int replyIVOff = hopConfig.getReplyIV().getOffset();
            if (log.shouldLog(Log.DEBUG))
                log.debug("Decrypting record " + recordNum + "/" + hop + " with replyKey " + replyKey.toBase64() + "/" + Base64.encode(replyIV, replyIVOff, 16));
            ctx.aes().decrypt(rec.getData(), off, rec.getData(), off, replyKey, replyIV, replyIVOff, TunnelBuildReplyMessage.RECORD_SIZE);
        }
        // ok, all of the layered encryption is stripped, so lets verify it 
        // (formatted per BuildResponseRecord.create)
        Hash h = ctx.sha().calculateHash(rec.getData(), off + Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH);
        if (!DataHelper.eq(h.getData(), 0, rec.getData(), off, Hash.HASH_LENGTH)) {
            if (log.shouldLog(Log.DEBUG))
                log.debug("Failed verification on " + recordNum + "/" + hop + ": " + h.toBase64() + " calculated, " +
                          Base64.encode(rec.getData(), off, Hash.HASH_LENGTH) + " expected\n" +
                          "Record: " + Base64.encode(rec.getData()));
            return -1;
        } else {
            int rv = (int)DataHelper.fromLong(rec.getData(), off + TunnelBuildReplyMessage.RECORD_SIZE - 1, 1);
            if (log.shouldLog(Log.DEBUG))
                log.debug("Verified: " + rv + " for record " + recordNum + "/" + hop);
            return rv;
        }
    }
}
