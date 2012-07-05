package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Build garlic messages based on a GarlicConfig
 *
 */
public class GarlicMessageBuilder {

    /**
     *  This was 100 since 0.6.1.10 (50 before that). It's important because:
     * <pre>
     *  - Tags are 32 bytes. So it previously added 3200 bytes to an initial message.
     *  - Too many tags adds a huge overhead to short-duration connections
     *    (like http, datagrams, etc.)
     *  - Large messages have a much higher chance of being dropped due to
     *    one of their 1KB fragments being discarded by a tunnel participant.
     *  - This reduces the effective maximum datagram size because the client
     *    doesn't know when tags will be bundled, so the tag size must be
     *    subtracted from the maximum I2NP size or transport limit.
     * </pre>
     *
     *  Issues with too small a value:
     * <pre>
     *  - When tags are sent, a reply leaseset (~1KB) is always bundled.
     *    Maybe don't need to bundle more than every minute or so
     *    rather than every time?
     *  - Does the number of tags (and the threshold of 20) limit the effective
     *    streaming lib window size? Should the threshold and the number of
     *    sent tags be variable based on the message rate?
     * </pre>
     *
     *  We have to be very careful if we implement an adaptive scheme,
     *  since the key manager is per-router, not per-local-dest.
     *  Or maybe that's a bad idea, and we need to move to a per-dest manager.
     *  This needs further investigation.
     *
     *  So a value somewhat higher than the low threshold
     *  seems appropriate.
     *
     *  Use care when adjusting these values. See ConnectionOptions in streaming,
     *  and TransientSessionKeyManager in crypto, for more information.
     */
    private static final int DEFAULT_TAGS = 40;
    private static final int LOW_THRESHOLD = 30;

    /** @param local non-null; do not use this method for the router's SessionKeyManager */
    public static int estimateAvailableTags(RouterContext ctx, PublicKey key, Hash local) {
        SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(local);
        if (skm == null)
            return 0;
        SessionKey curKey = skm.getCurrentKey(key);
        if (curKey == null)
            return 0;
        return skm.getAvailableTags(key, curKey);
    }
    
    /**
     * Unused and probably a bad idea.
     *
     * Used below only on a recursive call if the garlic message contains a garlic message.
     * We don't need the SessionKey or SesssionTags returned
     * This uses the router's SKM, which is probably not what you want.
     * This isn't fully implemented, because the key and tags aren't saved - maybe
     * it should force elGamal?
     *
     * @param ctx scope
     * @param config how/what to wrap
     */
    private static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        log.error("buildMessage 2 args, using router SKM", new Exception("who did it"));
        return buildMessage(ctx, config, new SessionKey(), new HashSet(), ctx.sessionKeyManager());
    }

    /**
     * called by OCMJH
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags output parameter that will be filled with the sessionTags used
     * @param skm non-null
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             SessionKeyManager skm) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, DEFAULT_TAGS, false, skm);
    }

    /** unused */
    /***
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set wrappedTags,
                                             int numTagsToDeliver) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, numTagsToDeliver, false);
    }
    ***/

    /**
     * called by above
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags output parameter that will be filled with the sessionTags used
     * @param numTagsToDeliver only if the estimated available tags are below the threshold
     * @param skm non-null
     */
    private static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             int numTagsToDeliver, boolean forceElGamal, SessionKeyManager skm) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, numTagsToDeliver, LOW_THRESHOLD, false, skm);
    }

    /**
     * called by netdb
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags output parameter that will be filled with the sessionTags used
     * @param numTagsToDeliver only if the estimated available tags are below the threshold
     * @param lowTagsThreshold the threshold
     * @param skm non-null
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             int numTagsToDeliver, int lowTagsThreshold, boolean forceElGamal, SessionKeyManager skm) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        PublicKey key = config.getRecipientPublicKey();
        if (key == null) {
            if (config.getRecipient() == null) {
                throw new IllegalArgumentException("Null recipient specified");
            } else if (config.getRecipient().getIdentity() == null) {
                throw new IllegalArgumentException("Null recipient.identity specified");
            } else if (config.getRecipient().getIdentity().getPublicKey() == null) {
                throw new IllegalArgumentException("Null recipient.identity.publicKey specified");
            } else
                key = config.getRecipient().getIdentity().getPublicKey();
        }
        
        if (log.shouldLog(Log.INFO))
            log.info("Encrypted with public key " + key + " to expire on " + new Date(config.getExpiration()));
        
        SessionKey curKey = skm.getCurrentOrNewKey(key);
        SessionTag curTag = null;
        if (!forceElGamal) {
            curTag = skm.consumeNextAvailableTag(key, curKey);
            
            int availTags = skm.getAvailableTags(key, curKey);
            if (log.shouldLog(Log.DEBUG))
                log.debug("Available tags for encryption to " + key + ": " + availTags);

            if (availTags < lowTagsThreshold) { // arbitrary threshold
                for (int i = 0; i < numTagsToDeliver; i++)
                    wrappedTags.add(new SessionTag(true));
                if (log.shouldLog(Log.INFO))
                    log.info("Too few are available (" + availTags + "), so we're including more");
            } else if (skm.getAvailableTimeLeft(key, curKey) < 60*1000) {
                // if we have enough tags, but they expire in under 30 seconds, we want more
                for (int i = 0; i < numTagsToDeliver; i++)
                    wrappedTags.add(new SessionTag(true));
                if (log.shouldLog(Log.INFO))
                    log.info("Tags are almost expired, adding new ones");
            } else {
                // always tack on at least one more - not necessary.
                //wrappedTags.add(new SessionTag(true));
            }
        }

        wrappedKey.setData(curKey.getData());
        
        return buildMessage(ctx, config, wrappedKey, wrappedTags, key, curKey, curTag);
    }
    
    /**
     *  used by TestJob and directly above
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey unused - why??
     * @param wrappedTags output parameter that will be filled with the sessionTags used
     * @param target public key of the location being garlic routed to (may be null if we 
     *               know the encryptKey and encryptTag)
     * @param encryptKey sessionKey used to encrypt the current message
     * @param encryptTag sessionTag used to encrypt the current message
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             PublicKey target, SessionKey encryptKey, SessionTag encryptTag) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        if (config == null)
            throw new IllegalArgumentException("Null config specified");
        
        GarlicMessage msg = new GarlicMessage(ctx);
        
        noteWrap(ctx, msg, config);
        
        byte cloveSet[] = buildCloveSet(ctx, config);
        
        // TODO - 128 is the minimum padded size - should it be more? less? random?
        byte encData[] = ctx.elGamalAESEngine().encrypt(cloveSet, target, encryptKey, wrappedTags, encryptTag, 128);
        msg.setData(encData);
        msg.setMessageExpiration(config.getExpiration());
        
        long timeFromNow = config.getExpiration() - ctx.clock().now();
        if (timeFromNow < 1*1000) {
            if (log.shouldLog(Log.DEBUG))
                log.debug("Building a message expiring in " + timeFromNow + "ms: " + config, new Exception("created by"));
            return null;
        }
        
        if (log.shouldLog(Log.DEBUG))
            log.debug("CloveSet (" + config.getCloveCount() + " cloves) for message " + msg.getUniqueId() + " is " + cloveSet.length
                     + " bytes and encrypted message data is " + encData.length + " bytes");
        
        return msg;
    }
    
    private static void noteWrap(RouterContext ctx, GarlicMessage wrapper, GarlicConfig contained) {
        for (int i = 0; i < contained.getCloveCount(); i++) {
            GarlicConfig config = contained.getClove(i);
            if (config instanceof PayloadGarlicConfig) {
                I2NPMessage msg = ((PayloadGarlicConfig)config).getPayload();
                String bodyType = msg.getClass().getName();
                ctx.messageHistory().wrap(bodyType, msg.getUniqueId(), GarlicMessage.class.getName(), wrapper.getUniqueId());
            }
        }
    }
    
    /**
     * Build an unencrypted set of cloves specified by the config.
     *
     */
    private static byte[] buildCloveSet(RouterContext ctx, GarlicConfig config) {
        ByteArrayOutputStream baos = null;
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        try {
            if (config instanceof PayloadGarlicConfig) {
                byte clove[] = buildClove(ctx, (PayloadGarlicConfig)config);
                baos = new ByteArrayOutputStream(clove.length + 16);
                DataHelper.writeLong(baos, 1, 1);
                baos.write(clove);
            } else {
                byte cloves[][] = new byte[config.getCloveCount()][];
                for (int i = 0; i < config.getCloveCount(); i++) {
                    GarlicConfig c = config.getClove(i);
                    if (c instanceof PayloadGarlicConfig) {
                        //log.debug("Subclove IS a payload garlic clove");
                        cloves[i] = buildClove(ctx, (PayloadGarlicConfig)c);
                    } else {
                        log.debug("Subclove IS NOT a payload garlic clove");
                        // See notes below
                        cloves[i] = buildClove(ctx, c);
                    }
                    if (cloves[i] == null)
                        throw new DataFormatException("Unable to build clove");
                }
                
                int len = 1;
                for (int i = 0; i < cloves.length; i++)
                    len += cloves[i].length;
                baos = new ByteArrayOutputStream(len + 16);
                DataHelper.writeLong(baos, 1, cloves.length);
                for (int i = 0; i < cloves.length; i++)
                    baos.write(cloves[i]);
            }
            if (baos == null)
                new ByteArrayOutputStream(16);
            config.getCertificate().writeBytes(baos);
            DataHelper.writeLong(baos, 4, config.getId());
            DataHelper.writeLong(baos, DataHelper.DATE_LENGTH, config.getExpiration());
        } catch (IOException ioe) {
            log.error("Error building the clove set", ioe);
        } catch (DataFormatException dfe) {
            log.error("Error building the clove set", dfe);
        }
        return baos.toByteArray();
    }
    
    private static byte[] buildClove(RouterContext ctx, PayloadGarlicConfig config) throws DataFormatException, IOException {
        GarlicClove clove = new GarlicClove(ctx);
        clove.setData(config.getPayload());
        return buildCommonClove(ctx, clove, config);
    }
    
    /**
     *  UNUSED
     *
     *  The Garlic Message we are building contains another garlic message,
     *  as specified by a GarlicConfig (NOT a PayloadGarlicConfig).
     *
     *  So this calls back to the top, to buildMessage(ctx, config),
     *  which uses the router's SKM, i.e. the wrong one.
     *  Unfortunately we've lost the reference to the SessionKeyManager way down here,
     *  so we can't call buildMessage(ctx, config, key, tags, skm).
     *
     *  If we do ever end up constructing a garlic message that contains a garlic message,
     *  we'll have to fix this by passing the skm through the last buildMessage,
     *  through buildCloveSet, to here.
     *
     */
    private static byte[] buildClove(RouterContext ctx, GarlicConfig config) throws DataFormatException, IOException {
        GarlicClove clove = new GarlicClove(ctx);
        GarlicMessage msg = buildMessage(ctx, config);
        if (msg == null)
            throw new DataFormatException("Unable to build message from clove config");
        clove.setData(msg);
        return buildCommonClove(ctx, clove, config);
    }
    
    
    private static byte[] buildCommonClove(RouterContext ctx, GarlicClove clove, GarlicConfig config) throws DataFormatException, IOException {
        clove.setCertificate(config.getCertificate());
        clove.setCloveId(config.getId());
        clove.setExpiration(new Date(config.getExpiration()));
        clove.setInstructions(config.getDeliveryInstructions());
        return clove.toByteArray();
        /*
        int size = clove.estimateSize();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
        clove.writeBytes(baos);
        return baos.toByteArray();
         */
    }
}
