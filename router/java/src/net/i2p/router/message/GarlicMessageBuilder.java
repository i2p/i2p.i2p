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
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageHistory;
import net.i2p.util.Log;

/**
 * Build garlic messages based on a GarlicConfig
 *
 */
public class GarlicMessageBuilder {
    private final static Log _log = new Log(GarlicMessageBuilder.class);
    
    public static GarlicMessage buildMessage(GarlicConfig config) {
	return buildMessage(config, new SessionKey(), new HashSet());
    }
    public static GarlicMessage buildMessage(GarlicConfig config, SessionKey wrappedKey, Set wrappedTags) {
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
	GarlicMessage msg = new GarlicMessage();
	
	noteWrap(msg, config);
	
	_log.info("Encrypted with public key " + key + " to expire on " + new Date(config.getExpiration()));
	
	byte cloveSet[] = buildCloveSet(config);
	
	SessionKey curKey = SessionKeyManager.getInstance().getCurrentKey(key);
	if (curKey == null)
	    curKey = SessionKeyManager.getInstance().createSession(key);
	wrappedKey.setData(curKey.getData());
	
	int availTags = SessionKeyManager.getInstance().getAvailableTags(key, curKey);
	_log.debug("Available tags for encryption to " + key + ": " + availTags);
	
	if (availTags < 10) { // arbitrary threshold
	    for (int i = 0; i < 20; i++)
		wrappedTags.add(new SessionTag(true));
	    _log.info("Less than 10 tags are available (" + availTags + "), so we're including 20 more");
	} else if (SessionKeyManager.getInstance().getAvailableTimeLeft(key, curKey) < 30*1000) {
	    // if we have > 10 tags, but they expire in under 30 seconds, we want more
	    for (int i = 0; i < 20; i++)
		wrappedTags.add(new SessionTag(true));
	    _log.info("Tags are almost expired, adding 20 new ones");
	} else {
	    // always tack on at least one more - not necessary.
	    //wrappedTags.add(new SessionTag(true));
	}
	SessionTag curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(key, curKey);
	byte encData[] = ElGamalAESEngine.encrypt(cloveSet, key, curKey, wrappedTags, curTag, 1024);
	msg.setData(encData);
	Date exp = new Date(config.getExpiration());
	msg.setMessageExpiration(exp);
	return msg;
    }
    
    private static void noteWrap(GarlicMessage wrapper, GarlicConfig contained) {
	for (int i = 0; i < contained.getCloveCount(); i++) {
	    GarlicConfig config = contained.getClove(i);
	    if (config instanceof PayloadGarlicConfig) {
		I2NPMessage msg = ((PayloadGarlicConfig)config).getPayload();
		String bodyType = msg.getClass().getName();
		MessageHistory.getInstance().wrap(bodyType, msg.getUniqueId(), GarlicMessage.class.getName(), wrapper.getUniqueId());
	    }
	}
    }
    
    /**
     * Build an unencrypted set of cloves specified by the config.  
     *
     */
    private static byte[] buildCloveSet(GarlicConfig config) {
	ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	try {
	    if (config instanceof PayloadGarlicConfig) {
		DataHelper.writeLong(baos, 1, 1);
		baos.write(buildClove((PayloadGarlicConfig)config));
	    } else {
		DataHelper.writeLong(baos, 1, config.getCloveCount());
		for (int i = 0; i < config.getCloveCount(); i++) {
		    GarlicConfig c = config.getClove(i);
		    byte clove[] = null;
		    if (c instanceof PayloadGarlicConfig) {
			_log.debug("Subclove IS a payload garlic clove");
			clove = buildClove((PayloadGarlicConfig)c);
		    } else {
			_log.debug("Subclove IS NOT a payload garlic clove");
			clove = buildClove(c); 
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
	    _log.error("Error building the clove set", ioe);
	} catch (DataFormatException dfe) {
	    _log.error("Error building the clove set", dfe);
	}
	return baos.toByteArray();
    }
    
    private static byte[] buildClove(PayloadGarlicConfig config) throws DataFormatException, IOException {
	GarlicClove clove = new GarlicClove();
	clove.setData(config.getPayload());
	return buildCommonClove(clove, config);
    }
    
    private static byte[] buildClove(GarlicConfig config) throws DataFormatException, IOException {
	GarlicClove clove = new GarlicClove();
	GarlicMessage msg = buildMessage(config);
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
	specifySourceRouteBlock(clove, config);
	ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	clove.writeBytes(baos);
	return baos.toByteArray();
    }
    
    private static void specifySourceRouteBlock(GarlicClove clove, GarlicConfig config) throws DataFormatException {
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
	    _log.debug("Specifying source route block");
	    
	    SessionKey replySessionKey = KeyGenerator.getInstance().generateSessionKey();
	    SessionTag tag = new SessionTag(true);
	    
	    // make it so we'll read the session tag correctly and use the right session key
	    HashSet tags = new HashSet(1);
	    tags.add(tag);
	    SessionKeyManager.getInstance().tagsReceived(replySessionKey, tags);
	    
	    SourceRouteBlock block = new SourceRouteBlock();
	    PublicKey pk = config.getReplyThroughRouter().getIdentity().getPublicKey();
	    block.setData(config.getReplyInstructions(), config.getReplyBlockMessageId(), 
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
