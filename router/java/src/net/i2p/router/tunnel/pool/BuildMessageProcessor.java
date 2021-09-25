package net.i2p.router.tunnel.pool;

import java.io.File;

import net.i2p.crypto.ChaCha20;
import net.i2p.crypto.EncType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.ShortEncryptedBuildRecord;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.router.RouterContext;
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
 * @since 0.9.51 moved to tunnel.pool package
 */
class BuildMessageProcessor {
    private final RouterContext ctx;
    private final Log log;
    private final DecayingBloomFilter _filter;
    
    public BuildMessageProcessor(RouterContext ctx) {
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
        // Too early, keys not registered with key manager yet
        //boolean isEC = ctx.keyManager().getPrivateKey().getType() == EncType.ECIES_X25519;
        // ... rather than duplicating all the logic in LoadRouterInfoJob,
        // just look for a short router.keys.dat file.
        // If it doesn't exist, it will be created later as EC.
        // If we're rekeing to EC, it's ok to have a longer duration this time.
        File f = new File(ctx.getConfigDir(), "router.keys.dat");
        boolean isEC = f.length() < 663;
        int duration;
        if (isEC) {
            // EC build records have a minute timestamp, so the Bloom filter can turn over faster
            duration = 8*60*1000;
            // shorter duration filter does not need to be as big
            m = Math.max(m - 3, 17);
        } else {
            // ElG build records have an hour timestamp
            duration = 60*60*1000;
        }
        if (log.shouldInfo())
            log.info("Selected Bloom filter m = " + m);
        return new DecayingBloomFilter(ctx, duration, 32, "TunnelBMP", m);
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
        byte[] ourHashData = ourHash.getData();
        boolean isShort = msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE;
        for (int i = 0; i < msg.getRecordCount(); i++) {
            EncryptedBuildRecord rec = msg.getRecord(i);
            boolean eq = DataHelper.eq(ourHashData, 0, rec.getData(), 0, BuildRequestRecord.PEER_SIZE);
            if (eq) {
                try {
                    rv = new BuildRequestRecord(ctx, privKey, rec);

                    if (isShort) {
                        SessionKey sk = rv.getChaChaReplyKey();
                        boolean isDup = _filter.add(sk.getData(), 0, 32);
                        if (isDup) {
                            if (log.shouldWarn())
                                log.warn(msg.getUniqueId() + ": Dup record: " + rv);
                            ctx.statManager().addRateData("tunnel.buildRequestDup", 1);
                            return null;
                        }
                    } else {
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
                        boolean isEC = ctx.keyManager().getPrivateKey().getType() == EncType.ECIES_X25519;
                        int off = isEC ? BuildRequestRecord.OFF_REPLY_KEY_EC : BuildRequestRecord.OFF_REPLY_KEY;
                        boolean isDup = _filter.add(rv.getData(), off, 32);
                        if (isDup) {
                            if (log.shouldLog(Log.WARN))
                                log.warn(msg.getUniqueId() + ": Dup record: " + rv);
                            ctx.statManager().addRateData("tunnel.buildRequestDup", 1);
                            return null;
                        }
                    }

                    if (log.shouldLog(Log.DEBUG))
                        log.debug(msg.getUniqueId() + ": Matching record: " + rv);
                    ourHop = i;
                    // TODO should we keep looking for a second match and fail if found?
                    break;
                } catch (DataFormatException dfe) {
                    // For ECIES routers, this is relatively common due to old routers that don't
                    // check enc type sending us ElG requests
                    if (log.shouldLog(Log.WARN))
                        log.warn(msg.getUniqueId() + ": Matching record decrypt failure " + privKey.getType(), dfe);
                    // on the microscopic chance that there's another router
                    // out there with the same first 16 bytes, go around again
                    continue;
                }
            }
        }
        if (rv == null) {
            // none of the records matched, b0rk
            if (log.shouldLog(Log.WARN))
                log.warn(msg.getUniqueId() + ": No record decrypted");
            return null;
        }
        
        if (isShort) {
            byte[] replyKey = rv.getChaChaReplyKey().getData();
            byte iv[] = new byte[12];
            for (int i = 0; i < msg.getRecordCount(); i++) {
                if (i != ourHop) {
                    EncryptedBuildRecord data = msg.getRecord(i);
                    //if (log.shouldLog(Log.DEBUG))
                    //    log.debug("Encrypting record " + i + "/? with replyKey " + replyKey.toBase64() + "/" + Base64.encode(iv));
                    // encrypt in-place, corrupts SDS
                    byte[] bytes = data.getData();
                    // slot number, little endian
                    iv[4] = (byte) i;
                    ChaCha20.encrypt(replyKey, iv, bytes, 0, bytes, 0, ShortEncryptedBuildRecord.LENGTH);
                }
            }
        } else {
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
        }
        msg.setRecord(ourHop, null);
        return rv;
    }
}
