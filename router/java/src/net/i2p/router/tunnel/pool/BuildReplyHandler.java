package net.i2p.router.tunnel.pool;

import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.ChaCha20;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortEncryptedBuildRecord;
import net.i2p.data.i2np.ShortTunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Decrypt the layers of a tunnel build reply message, determining whether the individual 
 * hops agreed to participate in the tunnel, or if not, why not.
 *
 * @since 0.9.51 moved to tunnel.pool package
 */
class BuildReplyHandler {

    private final I2PAppContext ctx;
    private final Log log;

    // cached results
    private static final Result RESULT_NG = new Result(-1, null);
    private static final Result RESULT_OK = new Result(0, null);
    private static final Result RESULT_BW = new Result(TunnelHistory.TUNNEL_REJECT_BANDWIDTH, null);

    /**
     *  @since 0.9.8 (methods were static before)
     */
    public BuildReplyHandler(I2PAppContext context) {
        ctx = context;
        log = ctx.logManager().getLog(BuildReplyHandler.class);
    }

    /**
     * This contains the result of decrypting the build request
     * or reply record, including the decrypted properties field,
     * if present.
     *
     * For requests, the code is 0 if the decrypt was successful
     * or -1 if the record was not found or the decrypt failed.
     *
     * For replies, the code is from the reply record, 0-255.
     * The code is usually 0 for ACCEPT or 30 for REJECT_BANDWIDTH.
     * The code is -1 if the record was not found or the decrypt failed.
     *
     * The Properties is usually null, but if non-null, will contain
     * bandwidth request/reply options as specified in proposal 168,
     * or other options to be defined later.
     *
     * If the code is -1, or it's a non-ECIES build record,
     * or the properties field in the record was empty, 
     * the returned properties here will be null.
     *
     * @since 0.9.66
     */
    public static class Result {
        public final int code;
        public final Properties props;
        /**
         * @param c 0-255 or -1
         * @param p may be null
         */
        public Result(int c, Properties p) {
            code = c; props = p;
        }
    }

    /**
     * Decrypt the tunnel build reply records.  This overwrites the contents of the reply.
     * Thread safe (no state).
     *
     * Note that this layer-decrypts the build records in-place.
     * Do not call this more than once for a given message.
     *
     * @return status for the records (in record order), or null if the replies were not valid.  Fake records
     *         always have 0 as their value. If the array is non-null, all entries in the array are non-null.
     */
    public Result[] decrypt(TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, List<Integer> recordOrder) {
        if (reply.getRecordCount() != recordOrder.size()) {
            // somebody messed with us
            log.error("Corrupted build reply, expected " + recordOrder.size() + " records, got " + reply.getRecordCount());
            return null;
        }
        Result rv[] = new Result[reply.getRecordCount()];
        for (int i = 0; i < rv.length; i++) {
            int hop = recordOrder.get(i).intValue();
            if (BuildMessageGenerator.isBlank(cfg, hop)) {
                // self or unused...
                if (log.shouldLog(Log.DEBUG))
                    log.debug(reply.getUniqueId() + ": skipping record " + i + "/" + hop + " for: " + cfg);
                if (cfg.isInbound() && hop + 1 == cfg.getLength()) { // IBEP
                    byte[] h1 = new byte[Hash.HASH_LENGTH];
                    byte[] data = reply.getRecord(i).getData();
                    ctx.sha().calculateHash(data, 0, data.length, h1, 0);
                    // get stored hash put here by BuildMessageGenerator
                    Hash h2 = cfg.getBlankHash();
                    if (h2 != null && DataHelper.eq(h1, h2.getData())) {
                        rv[i] = RESULT_OK;
                    } else {
                        if (log.shouldWarn())
                            log.warn("IBEP record corrupt on " + cfg);
                        // Caller doesn't check value for this hop so fail the whole thing
                        return null;
                    }
                } else {
                    rv[i] = RESULT_OK;
                }
            } else {
                Result res = decryptRecord(reply, cfg, i, hop);
                if (res.code == -1) {
                    if (log.shouldLog(Log.WARN))
                        log.warn(reply.getUniqueId() + ": decrypt record " + i + "/" + hop + " fail: " + cfg);
                    return null;
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug(reply.getUniqueId() + ": decrypt record " + i + "/" + hop + " code: " + res.code + " for " + cfg);
                }
                rv[i] = res;
            }
        }
        return rv;
    }

    /**
     * Decrypt the record (removing the layers of reply encyption) and read out the status
     *
     * Note that this layer-decrypts the build records in-place.
     * Do not call this more than once for a given message.
     * Do not call for blank hops.
     *
     * @return the status 0-255, or -1 on decrypt failure
     */
    private Result decryptRecord(TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, int recordNum, int hop) {
        EncryptedBuildRecord rec = reply.getRecord(recordNum);
        int type = reply.getType();
        if (rec == null) {
            if (log.shouldWarn())
                log.warn("Missing record " + recordNum);
            return RESULT_NG;
        }
        byte[] data = rec.getData();
        int start = cfg.getLength() - 1;
        if (cfg.isInbound())
            start--; // the last hop in an inbound tunnel response doesn't actually encrypt
        int end = hop;
        boolean isEC = cfg.isEC(hop);
        // chacha decrypt after the loop
        if (isEC)
            end++;
        // do we need to adjust this for the endpoint?
        boolean isOTBRM = type == OutboundTunnelBuildReplyMessage.MESSAGE_TYPE;
        boolean isShort = isOTBRM || type == ShortTunnelBuildReplyMessage.MESSAGE_TYPE;
        if (isShort) {
            byte iv[] = new byte[12];
            for (int j = start; j >= end; j--) {
                byte[] replyKey = cfg.getChaChaReplyKey(j).getData();
                if (log.shouldDebug()) {
                    log.debug(reply.getUniqueId() + ": Decrypting ChaCha record " + recordNum + "/" + hop + "/" + j + " with replyKey " 
                              + Base64.encode(replyKey) + " : " + cfg);
                }
                // slot number, little endian
                iv[4] = (byte) recordNum;
                ChaCha20.encrypt(replyKey, iv, data, 0, data, 0, ShortEncryptedBuildRecord.LENGTH);
            }
        } else {
            for (int j = start; j >= end; j--) {
                SessionKey replyKey = cfg.getAESReplyKey(j);
                byte replyIV[] = cfg.getAESReplyIV(j);
                if (log.shouldDebug()) {
                    log.debug(reply.getUniqueId() + ": Decrypting AES record " + recordNum + "/" + hop + "/" + j + " with replyKey " 
                              + replyKey.toBase64() + "/" + Base64.encode(replyIV) + ": " + cfg);
                    //log.debug(reply.getRawUniqueId() + ": before decrypt: " + Base64.encode(data));
                    //log.debug(reply.getRawUniqueId() + ": Full reply rec: sz=" + data.length + " data=" + Base64.encode(data));
                }
                ctx.aes().decrypt(data, 0, data, 0, replyKey, replyIV, 0, data.length);
                //if (log.shouldLog(Log.DEBUG))
                //    log.debug(reply.getRawUniqueId() + ": after decrypt: " + Base64.encode(data));
            }
        }
        // ok, all of the layered encryption is stripped, so lets verify it 
        // (formatted per BuildResponseRecord.create)
        int rv;
        Properties props = null;
        if (isEC) {
            // For last iteration, do ChaCha instead
            SessionKey replyKey = cfg.getChaChaReplyKey(hop);
            byte[] replyIV = cfg.getChaChaReplyAD(hop);
            if (log.shouldDebug())
                log.debug(reply.getUniqueId() + ": Decrypting chacha/poly record " + recordNum + "/" + hop + " with replyKey " 
                          + replyKey.toBase64() + "/" + Base64.encode(replyIV) + ": " + cfg);
            boolean ok;
            if (isShort)
                ok = BuildResponseRecord.decrypt(rec, replyKey, replyIV, recordNum);
            else
                ok = BuildResponseRecord.decrypt(rec, replyKey, replyIV);
            if (!ok) {
                if (log.shouldWarn())
                    log.warn(reply.getUniqueId() + ": chacha reply decrypt fail on " + recordNum + "/" + hop);
                return RESULT_NG;
            }
            // this handles both standard records in a build reply message and short records in a OTBRM
            rv = data[rec.length() - 17] & 0xff;

            // reply properties
            if (rv == 0) {
                // The mapping is at the beginning of the record
                // check to see if non-empty before parsing
                if (data[0] != 0 || data[1] != 0) {
                    props = new Properties();
                    try {
                        DataHelper.fromProperties(data, 0, props);
                    } catch (DataFormatException dfe) {
                        if (log.shouldWarn())
                            log.warn(reply.getUniqueId() + ": error reading properties", dfe);
                        props = null;
                    }
                }
            }
        } else {
            // don't cache the result
            //Hash h = ctx.sha().calculateHash(data, off + Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH);
            byte[] h = SimpleByteCache.acquire(Hash.HASH_LENGTH);
            ctx.sha().calculateHash(data, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH, h, 0);
            boolean ok = DataHelper.eq(h, 0, data, 0, Hash.HASH_LENGTH);
            if (!ok) {
                if (log.shouldWarn())
                    log.warn(reply.getUniqueId() + ": sha256 reply verify fail on " + recordNum + "/" + hop + ": " + Base64.encode(h) + " calculated, " +
                             Base64.encode(data, 0, Hash.HASH_LENGTH) + " expected\n" +
                             "Record: " + Base64.encode(data, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH));
                SimpleByteCache.release(h);
                return RESULT_NG;
            }
            SimpleByteCache.release(h);
            rv = data[TunnelBuildReplyMessage.RECORD_SIZE - 1] & 0xff;
        }
        if (log.shouldLog(Log.DEBUG))
            log.debug(reply.getUniqueId() + ": Verified: " + rv + " for record " + recordNum + "/" + hop);
        if (props == null) {
            // return cached
            if (rv == 0)
                return RESULT_OK;
            if (rv == TunnelHistory.TUNNEL_REJECT_BANDWIDTH)
                return RESULT_BW;
        }
        return new Result(rv, props);
    }
}
