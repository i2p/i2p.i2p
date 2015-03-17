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

    /** @param local non-null; do not use this method for the router's SessionKeyManager */
    public static boolean needsTags(RouterContext ctx, PublicKey key, Hash local) {
        SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(local);
        if (skm == null)
            return true;
        SessionKey curKey = skm.getCurrentKey(key);
        if (curKey == null)
            return true;
        return skm.shouldSendTags(key, curKey);
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
     * @throws IllegalArgumentException on error
     */
    private static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        log.error("buildMessage 2 args, using router SKM", new Exception("who did it"));
        return buildMessage(ctx, config, new SessionKey(), new HashSet(), ctx.sessionKeyManager());
    }

    /**
     * Now unused, since we have to generate a reply token first in OCMOSJ but we don't know if tags are required yet.
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags Output parameter that will be filled with the sessionTags used.
                          If non-empty on return you must call skm.tagsDelivered() when sent
                          and then call skm.tagsAcked() or skm.failTags() later.
     * @param skm non-null
     * @throws IllegalArgumentException on error
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             SessionKeyManager skm) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, skm.getTagsToSend(), skm);
    }

    /** unused */
    /***
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set wrappedTags,
                                             int numTagsToDeliver) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, numTagsToDeliver, false);
    }
    ***/

    /**
     * called by OCMJH
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags Output parameter that will be filled with the sessionTags used.
                          If non-empty on return you must call skm.tagsDelivered() when sent
                          and then call skm.tagsAcked() or skm.failTags() later.
     * @param numTagsToDeliver Only if the estimated available tags are below the threshold.
                               Set to zero to disable tag delivery. You must set to zero if you are not
                               equipped to confirm delivery and call skm.tagsAcked() or skm.failTags() later.
     * @param skm non-null
     * @throws IllegalArgumentException on error
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             int numTagsToDeliver, SessionKeyManager skm) {
        return buildMessage(ctx, config, wrappedKey, wrappedTags, numTagsToDeliver, skm.getLowThreshold(), skm);
    }

    /**
     * called by netdb and above
     *
     * @param ctx scope
     * @param config how/what to wrap
     * @param wrappedKey output parameter that will be filled with the sessionKey used
     * @param wrappedTags Output parameter that will be filled with the sessionTags used.
                          If non-empty on return you must call skm.tagsDelivered() when sent
                          and then call skm.tagsAcked() or skm.failTags() later.
     * @param numTagsToDeliver only if the estimated available tags are below the threshold.
                               Set to zero to disable tag delivery. You must set to zero if you are not
                               equipped to confirm delivery and call skm.tagsAcked() or failTags() later.
                               If this is always 0, it forces ElGamal every time.
     * @param lowTagsThreshold the threshold
     * @param skm non-null
     * @throws IllegalArgumentException on error
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             int numTagsToDeliver, int lowTagsThreshold, SessionKeyManager skm) {
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

            curTag = skm.consumeNextAvailableTag(key, curKey);
            
            int availTags = skm.getAvailableTags(key, curKey);
            if (log.shouldLog(Log.DEBUG))
                log.debug("Available tags for encryption to " + key + ": " + availTags);

            if (numTagsToDeliver > 0 && skm.shouldSendTags(key, curKey, lowTagsThreshold)) {
                for (int i = 0; i < numTagsToDeliver; i++)
                    wrappedTags.add(new SessionTag(true));
                if (log.shouldLog(Log.INFO))
                    log.info("Too few tags available so we're including " + numTagsToDeliver);
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
     * @param wrappedTags Output parameter that will be filled with the sessionTags used.
                          If non-empty on return you must call skm.tagsDelivered() when sent
                          and then call skm.tagsAcked() or skm.failTags() later.
     * @param target public key of the location being garlic routed to (may be null if we 
     *               know the encryptKey and encryptTag)
     * @param encryptKey sessionKey used to encrypt the current message
     * @param encryptTag sessionTag used to encrypt the current message
     * @throws IllegalArgumentException on error
     */
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set<SessionTag> wrappedTags,
                                             PublicKey target, SessionKey encryptKey, SessionTag encryptTag) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        if (config == null)
            throw new IllegalArgumentException("Null config specified");
        
        GarlicMessage msg = new GarlicMessage(ctx);
        
        //noteWrap(ctx, msg, config);
        
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
    
/****
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
****/
    
    /**
     * Build the unencrypted GarlicMessage specified by the config.
     * It contains the number of cloves, followed by each clove,
     * followed by a certificate, ID, and expiration date.
     *
     * @throws IllegalArgumentException on error
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
                }
                
                int len = 1;
                for (int i = 0; i < cloves.length; i++)
                    len += cloves[i].length;
                baos = new ByteArrayOutputStream(len + 16);
                DataHelper.writeLong(baos, 1, cloves.length);
                for (int i = 0; i < cloves.length; i++)
                    baos.write(cloves[i]);
            }
            config.getCertificate().writeBytes(baos);
            DataHelper.writeLong(baos, 4, config.getId());
            DataHelper.writeLong(baos, DataHelper.DATE_LENGTH, config.getExpiration());
        } catch (IOException ioe) {
            log.error("Error building the clove set", ioe);
            throw new IllegalArgumentException("Error building the clove set", ioe);
        } catch (DataFormatException dfe) {
            log.error("Error building the clove set", dfe);
            throw new IllegalArgumentException("Error building the clove set", dfe);
        }
        return baos.toByteArray();
    }
    
    private static byte[] buildClove(RouterContext ctx, PayloadGarlicConfig config) throws DataFormatException, IOException {
        GarlicClove clove = new GarlicClove(ctx);
        clove.setData(config.getPayload());
        return buildCommonClove(clove, config);
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
        return buildCommonClove(clove, config);
    }
    
    private static byte[] buildCommonClove(GarlicClove clove, GarlicConfig config) throws DataFormatException, IOException {
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
