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

import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.router.MessageHistory;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Build garlic messages based on a GarlicConfig
 *
 */
public class GarlicMessageBuilder {
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config) {
        return buildMessage(ctx, config, new SessionKey(), new HashSet());
    }
    public static GarlicMessage buildMessage(RouterContext ctx, GarlicConfig config, SessionKey wrappedKey, Set wrappedTags) {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        if (config == null)
            throw new IllegalArgumentException("Null config specified");
        
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
        GarlicMessage msg = new GarlicMessage(ctx);
        
        noteWrap(ctx, msg, config);
        
        log.info("Encrypted with public key " + key + " to expire on " + new Date(config.getExpiration()));
        
        byte cloveSet[] = buildCloveSet(ctx, config);
        
        SessionKey curKey = ctx.sessionKeyManager().getCurrentKey(key);
        if (curKey == null)
            curKey = ctx.sessionKeyManager().createSession(key);
        wrappedKey.setData(curKey.getData());
        
        int availTags = ctx.sessionKeyManager().getAvailableTags(key, curKey);
        log.debug("Available tags for encryption to " + key + ": " + availTags);
        
        if (availTags < 10) { // arbitrary threshold
            for (int i = 0; i < 20; i++)
                wrappedTags.add(new SessionTag(true));
            log.info("Less than 10 tags are available (" + availTags + "), so we're including 20 more");
        } else if (ctx.sessionKeyManager().getAvailableTimeLeft(key, curKey) < 30*1000) {
            // if we have > 10 tags, but they expire in under 30 seconds, we want more
            for (int i = 0; i < 20; i++)
                wrappedTags.add(new SessionTag(true));
            log.info("Tags are almost expired, adding 20 new ones");
        } else {
            // always tack on at least one more - not necessary.
            //wrappedTags.add(new SessionTag(true));
        }
        SessionTag curTag = ctx.sessionKeyManager().consumeNextAvailableTag(key, curKey);
        byte encData[] = ctx.elGamalAESEngine().encrypt(cloveSet, key, curKey, wrappedTags, curTag, 1024);
        msg.setData(encData);
        Date exp = new Date(config.getExpiration());
        msg.setMessageExpiration(exp);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        try {
            if (config instanceof PayloadGarlicConfig) {
                DataHelper.writeLong(baos, 1, 1);
                baos.write(buildClove(ctx, (PayloadGarlicConfig)config));
            } else {
                DataHelper.writeLong(baos, 1, config.getCloveCount());
                for (int i = 0; i < config.getCloveCount(); i++) {
                    GarlicConfig c = config.getClove(i);
                    byte clove[] = null;
                    if (c instanceof PayloadGarlicConfig) {
                        log.debug("Subclove IS a payload garlic clove");
                        clove = buildClove(ctx, (PayloadGarlicConfig)c);
                    } else {
                        log.debug("Subclove IS NOT a payload garlic clove");
                        clove = buildClove(ctx, c);
                    }
                    if (clove == null)
                        throw new DataFormatException("Unable to build clove");
                    else
                        baos.write(clove);
                }
            }
            config.getCertificate().writeBytes(baos);
            DataHelper.writeLong(baos, 4, config.getId());
            DataHelper.writeDate(baos, new Date(config.getExpiration()));
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
        specifySourceRouteBlock(ctx, clove, config);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        clove.writeBytes(baos);
        return baos.toByteArray();
    }
    
    private static void specifySourceRouteBlock(RouterContext ctx, GarlicClove clove, GarlicConfig config) throws DataFormatException {
        Log log = ctx.logManager().getLog(GarlicMessageBuilder.class);
        boolean includeBlock = false;
        if (config.getRequestAck()) {
            clove.setSourceRouteBlockAction(GarlicClove.ACTION_STATUS);
            includeBlock = true;
        } else if (config.getReplyInstructions() != null) {
            clove.setSourceRouteBlockAction(GarlicClove.ACTION_MESSAGE_SPECIFIC);
            includeBlock = true;
        } else {
            clove.setSourceRouteBlockAction(GarlicClove.ACTION_NONE);
        }
        
        if (includeBlock) {
            log.debug("Specifying source route block");
            
            SessionKey replySessionKey = ctx.keyGenerator().generateSessionKey();
            SessionTag tag = new SessionTag(true);
            
            // make it so we'll read the session tag correctly and use the right session key
            HashSet tags = new HashSet(1);
            tags.add(tag);
            ctx.sessionKeyManager().tagsReceived(replySessionKey, tags);
            
            SourceRouteBlock block = new SourceRouteBlock();
            PublicKey pk = config.getReplyThroughRouter().getIdentity().getPublicKey();
            block.setData(ctx, config.getReplyInstructions(), config.getReplyBlockMessageId(),
                          config.getReplyBlockCertificate(), config.getReplyBlockExpiration(), pk);
            block.setRouter(config.getReplyThroughRouter().getIdentity().getHash());
            block.setKey(replySessionKey);
            block.setTag(tag);
            clove.setSourceRouteBlock(block);
        } else {
            clove.setSourceRouteBlock(null);
        }
    }
}
