package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Receive the build message at a certain hop, decrypt its encrypted record,
 * read the enclosed tunnel request, decide how to reply, write the reply,
 * encrypt the reply record, and return a TunnelBuildMessage to forward on to
 * the next hop
 */
class BuildMessageProcessor {
    /**
     * Decrypt the record targetting us, encrypting all of the other records with the included 
     * reply key and IV.  The original, encrypted record targetting us is removed from the request
     * message (so that the reply can be placed in that position after going through the decrypted
     * request record).
     *
     * @return the current hop's decrypted record
     */
    public BuildRequestRecord decrypt(I2PAppContext ctx, TunnelBuildMessage msg, Hash ourHash, PrivateKey privKey) {
        Log log = ctx.logManager().getLog(getClass());
        BuildRequestRecord rv = null;
        int ourHop = -1;
        for (int i = 0; i < TunnelBuildMessage.RECORD_COUNT; i++) {
            ByteArray rec = msg.getRecord(i);
            int off = rec.getOffset();
            int len = BuildRequestRecord.PEER_SIZE;
            if (DataHelper.eq(ourHash.getData(), 0, rec.getData(), off, len)) {
                BuildRequestRecord req = new BuildRequestRecord();
                boolean ok = req.decryptRecord(ctx, privKey, ourHash, rec);
                if (ok)
                    rv = req;
                else
                    return null; // our hop is invalid?  b0rkage
                ourHop = i;
            }
        }
        if (rv == null) {
            // none of the records matched, b0rk
            return null;
        }
        SessionKey replyKey = rv.readReplyKey();
        byte iv[] = rv.readReplyIV();
        int ivOff = 0;
        for (int i = 0; i < TunnelBuildMessage.RECORD_COUNT; i++) {
            if (i != ourHop) {
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Encrypting record " + i + "/? with replyKey " + replyKey.toBase64() + "/" + Base64.encode(iv, ivOff, 16));
                ByteArray data = msg.getRecord(i);
                ctx.aes().encrypt(data.getData(), data.getOffset(), data.getData(), data.getOffset(), replyKey, 
                                  iv, ivOff, data.getValid());
            }
        }
        msg.setRecord(ourHop, null);
        return rv;
    }
}
