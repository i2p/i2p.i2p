package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.util.DecayingBloomFilter;
import net.i2p.util.DecayingHashSet;
import net.i2p.util.Log;

/**
 * Receive the build message at a certain hop, decrypt its encrypted record,
 * read the enclosed tunnel request, decide how to reply, write the reply,
 * encrypt the reply record, and return a TunnelBuildMessage to forward on to
 * the next hop
 */
public class BuildMessageProcessor {
    private final DecayingBloomFilter _filter;
    
    public BuildMessageProcessor(I2PAppContext ctx) {
        _filter = new DecayingHashSet(ctx, 60*1000, 32, "TunnelBMP");
        // all createRateStat in TunnelDispatcher
    }
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
        long beforeActualDecrypt = 0;
        long afterActualDecrypt = 0;
        long totalEq = 0;
        long totalDup = 0;
        long beforeLoop = System.currentTimeMillis();
        for (int i = 0; i < msg.getRecordCount(); i++) {
            ByteArray rec = msg.getRecord(i);
            int off = rec.getOffset();
            int len = BuildRequestRecord.PEER_SIZE;
            long beforeEq = System.currentTimeMillis();
            boolean eq = DataHelper.eq(ourHash.getData(), 0, rec.getData(), off, len);
            totalEq += System.currentTimeMillis()-beforeEq;
            if (eq) {
                long beforeIsDup = System.currentTimeMillis();
                boolean isDup = _filter.add(rec.getData(), off + len, 32);
                totalDup += System.currentTimeMillis()-beforeIsDup;
                if (isDup) {
                    if (log.shouldLog(Log.WARN))
                        log.debug(msg.getUniqueId() + ": A record matching our hash was found, but it seems to be a duplicate");
                    ctx.statManager().addRateData("tunnel.buildRequestDup", 1, 0);
                    return null;
                }
                BuildRequestRecord req = new BuildRequestRecord();
                beforeActualDecrypt = System.currentTimeMillis();
                boolean ok = req.decryptRecord(ctx, privKey, ourHash, rec);
                afterActualDecrypt = System.currentTimeMillis();
                if (ok) {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug(msg.getUniqueId() + ": A record matching our hash was found and decrypted");
                    rv = req;
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug(msg.getUniqueId() + ": A record matching our hash was found, but could not be decrypted");
                    return null; // our hop is invalid?  b0rkage
                }
                ourHop = i;
            }
        }
        if (rv == null) {
            // none of the records matched, b0rk
            if (log.shouldLog(Log.DEBUG))
                log.debug(msg.getUniqueId() + ": No records matching our hash was found");
            return null;
        }
        
        long beforeEncrypt = System.currentTimeMillis();
        SessionKey replyKey = rv.readReplyKey();
        byte iv[] = rv.readReplyIV();
        int ivOff = 0;
        for (int i = 0; i < msg.getRecordCount(); i++) {
            if (i != ourHop) {
                ByteArray data = msg.getRecord(i);
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Encrypting record " + i + "/?/" + data.getOffset() + "/" + data.getValid() + " with replyKey " + replyKey.toBase64() + "/" + Base64.encode(iv, ivOff, 16));
                ctx.aes().encrypt(data.getData(), data.getOffset(), data.getData(), data.getOffset(), replyKey, 
                                  iv, ivOff, data.getValid());
            }
        }
        long afterEncrypt = System.currentTimeMillis();
        msg.setRecord(ourHop, null);
        if (afterEncrypt-beforeLoop > 1000) {
            if (log.shouldLog(Log.WARN))
                log.warn("Slow decryption, total=" + (afterEncrypt-beforeLoop) 
                         + " looping=" + (beforeEncrypt-beforeLoop)
                         + " decrypt=" + (afterActualDecrypt-beforeActualDecrypt)
                         + " eq=" + totalEq
                         + " dup=" + totalDup
                         + " encrypt=" + (afterEncrypt-beforeEncrypt));
        }
        return rv;
    }
}
