package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Receive the build message at a certain hop, decrypt its encrypted record,
 * read the enclosed tunnel request, decide how to reply, write the reply,
 * encrypt the reply record, and return a TunnelBuildMessage to forward on to
 * the next hop.
 *
 * There is only one of these.
 * Instantiated by BuildHandler.
 *
 */
public class BuildMessageProcessor {
    private final I2PAppContext ctx;
    private final Log log;
    private final DecayingBloomFilter _filter;
    
    public BuildMessageProcessor(I2PAppContext ctx) {
        this.ctx = ctx;
        log = ctx.logManager().getLog(getClass());
        _filter = selectFilter();
        // all createRateStat in TunnelDispatcher
    }

    /**
     *  For N typical part tunnels and rejecting 50%, that's 12N requests per hour.
     *  This is the equivalent of (12N/600) KBps through the IVValidator filter.
     *
     *  Target false positive rate is 1E-5 or lower
     *
     *  @since 0.9.24
     */
    private DecayingBloomFilter selectFilter() {
        long maxMemory = SystemVersion.getMaxMemory();
        int m;
        if (SystemVersion.isAndroid() || SystemVersion.isARM() || maxMemory < 96*1024*1024L) {
            // 32 KB
            // appx 500 part. tunnels or 6K req/hr
            m = 17;
        } else if (ctx.getProperty(RouterThrottleImpl.PROP_MAX_TUNNELS, RouterThrottleImpl.DEFAULT_MAX_TUNNELS) >
                   RouterThrottleImpl.DEFAULT_MAX_TUNNELS && maxMemory > 256*1024*1024L) {
            // 2 MB
            // appx 20K part. tunnels or 240K req/hr
            m = 23;
        } else if (maxMemory > 256*1024*1024L) {
            // 1 MB
            // appx 10K part. tunnels or 120K req/hr
            m = 22;
        } else if (maxMemory > 128*1024*1024L) {
            // 512 KB
            // appx 5K part. tunnels or 60K req/hr
            m = 21;
        } else {
            // 128 KB
            // appx 2K part. tunnels or 24K req/hr
            m = 19;
        }
        if (log.shouldInfo())
            log.info("Selected Bloom filter m = " + m);
        return new DecayingBloomFilter(ctx, 60*60*1000, 32, "TunnelBMP", m);
    }

    /**
     * Decrypt the record targetting us, encrypting all of the other records with the included 
     * reply key and IV.  The original, encrypted record targetting us is removed from the request
     * message (so that the reply can be placed in that position after going through the decrypted
     * request record).
     *
     * Note that this layer-decrypts the build records in-place.
     * Do not call this more than once for a given message.
     *
     * @return the current hop's decrypted record or null on failure
     */
    public BuildRequestRecord decrypt(TunnelBuildMessage msg, Hash ourHash, PrivateKey privKey) {
        BuildRequestRecord rv = null;
        int ourHop = -1;
        long beforeActualDecrypt = 0;
        long afterActualDecrypt = 0;
        byte[] ourHashData = ourHash.getData();
        long beforeLoop = System.currentTimeMillis();
        for (int i = 0; i < msg.getRecordCount(); i++) {
            EncryptedBuildRecord rec = msg.getRecord(i);
            int len = BuildRequestRecord.PEER_SIZE;
            boolean eq = DataHelper.eq(ourHashData, 0, rec.getData(), 0, len);
            if (eq) {
                beforeActualDecrypt = System.currentTimeMillis();
                try {
                    rv = new BuildRequestRecord(ctx, privKey, rec);
                    afterActualDecrypt = System.currentTimeMillis();

                    // i2pd bug
                    boolean isBad = SessionKey.INVALID_KEY.equals(rv.readReplyKey());
                    if (isBad) {
                        if (log.shouldLog(Log.WARN))
                            log.warn(msg.getUniqueId() + ": Bad reply key: " + rv);
                        ctx.statManager().addRateData("tunnel.buildRequestBadReplyKey", 1);
                        return null;
                    }

                    // The spec says to feed the 32-byte AES-256 reply key into the Bloom filter.
                    // But we were using the first 32 bytes of the encrypted reply.
                    // Fixed in 0.9.24
                    boolean isDup = _filter.add(rv.getData(), BuildRequestRecord.OFF_REPLY_KEY, 32);
                    if (isDup) {
                        if (log.shouldLog(Log.WARN))
                            log.warn(msg.getUniqueId() + ": Dup record: " + rv);
                        ctx.statManager().addRateData("tunnel.buildRequestDup", 1);
                        return null;
                    }

                    if (log.shouldLog(Log.DEBUG))
                        log.debug(msg.getUniqueId() + ": Matching record: " + rv);
                    ourHop = i;
                    // TODO should we keep looking for a second match and fail if found?
                    break;
                } catch (DataFormatException dfe) {
                    if (log.shouldLog(Log.WARN))
                        log.warn(msg.getUniqueId() + ": Matching record decrypt failure", dfe);
                    // on the microscopic chance that there's another router
                    // out there with the same first 16 bytes, go around again
                    continue;
                }
            }
        }
        if (rv == null) {
            // none of the records matched, b0rk
            if (log.shouldLog(Log.WARN))
                log.warn(msg.getUniqueId() + ": No matching record");
            return null;
        }
        
        long beforeEncrypt = System.currentTimeMillis();
        SessionKey replyKey = rv.readReplyKey();
        byte iv[] = rv.readReplyIV();
        for (int i = 0; i < msg.getRecordCount(); i++) {
            if (i != ourHop) {
                EncryptedBuildRecord data = msg.getRecord(i);
                //if (log.shouldLog(Log.DEBUG))
                //    log.debug("Encrypting record " + i + "/? with replyKey " + replyKey.toBase64() + "/" + Base64.encode(iv));
                // encrypt in-place, corrupts SDS
                byte[] bytes = data.getData();
                ctx.aes().encrypt(bytes, 0, bytes, 0, replyKey, iv, 0, EncryptedBuildRecord.LENGTH);
            }
        }
        long afterEncrypt = System.currentTimeMillis();
        msg.setRecord(ourHop, null);
        if (afterEncrypt-beforeLoop > 1000) {
            if (log.shouldLog(Log.WARN))
                log.warn("Slow decryption, total=" + (afterEncrypt-beforeLoop) 
                         + " looping=" + (beforeEncrypt-beforeLoop)
                         + " decrypt=" + (afterActualDecrypt-beforeActualDecrypt)
                         + " encrypt=" + (afterEncrypt-beforeEncrypt));
        }
        return rv;
    }
}
