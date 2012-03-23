package net.i2p.router.networkdb.kademlia;

import java.util.HashSet;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.util.Log;

/**
 *  Method and class for garlic encrypting outbound netdb traffic,
 *  including management of the ElGamal/AES tags
 *
 *  @since 0.7.10
 */
class MessageWrapper {

    //private static final Log _log = RouterContext.getGlobalContext().logManager().getLog(MessageWrapper.class);

    private static final int NETDB_TAGS_TO_DELIVER = 6;
    private static final int NETDB_LOW_THRESHOLD = 3;

    /**
     *  Garlic wrap a message from a client or this router, destined for a router,
     *  to hide the contents from the OBEP.
     *  Caller must call acked() or fail() on the returned object.
     *
     *  @param from must be a local client with a session key manager,
     *              or null to use the router's session key manager
     *  @return null on encrypt failure
     */
    static WrappedMessage wrap(RouterContext ctx, I2NPMessage m, Hash from, RouterInfo to) {
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);

        PayloadGarlicConfig payload = new PayloadGarlicConfig();
        payload.setCertificate(Certificate.NULL_CERT);
        payload.setId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        payload.setPayload(m);
        payload.setRecipient(to);
        payload.setDeliveryInstructions(instructions);
        payload.setExpiration(m.getMessageExpiration());

        SessionKeyManager skm;
        if (from != null)
            skm = ctx.clientManager().getClientSessionKeyManager(from);
        else
            skm = ctx.sessionKeyManager();
        if (skm == null)
            return null;
        SessionKey sentKey = new SessionKey();
        Set<SessionTag> sentTags = new HashSet();
        GarlicMessage msg = GarlicMessageBuilder.buildMessage(ctx, payload, sentKey, sentTags, 
                                                              NETDB_TAGS_TO_DELIVER, NETDB_LOW_THRESHOLD, false, skm);
        if (msg == null)
            return null;
        TagSetHandle tsh = null;
        PublicKey sentTo = to.getIdentity().getPublicKey();
        if (!sentTags.isEmpty())
            tsh = skm.tagsDelivered(sentTo, sentKey, sentTags);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Sent to: " + to.getIdentity().getHash() + " with key: " + sentKey + " and tags: " + sentTags.size());
        return new WrappedMessage(msg, skm, sentTo, sentKey, tsh);
    }

    /**
     *  Wrapper so that we can keep track of the key and tags
     *  for later notification to the SKM
     */
    static class WrappedMessage {
        private GarlicMessage msg;
        private SessionKeyManager skm;
        private PublicKey sentTo;
        private SessionKey sessionKey;
        private TagSetHandle tsh;        

        WrappedMessage(GarlicMessage msg, SessionKeyManager skm, PublicKey sentTo, SessionKey sentKey, TagSetHandle tsh) {
            this.msg = msg;
            this.skm = skm;
            this.sentTo = sentTo;
            this.sessionKey = sentKey;
            this.tsh = tsh;
        }

        GarlicMessage getMessage() {
            return this.msg;
        }

        /** delivered tags (if any) were acked */
        void acked() {
            if (this.tsh != null) {
                this.skm.tagsAcked(this.sentTo, this.sessionKey, this.tsh);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Tags acked for key: " + this.sessionKey);
            }
        }

        /** delivered tags (if any) were not acked */
        void fail() {
            if (this.tsh != null) {
                this.skm.failTags(this.sentTo, this.sessionKey, this.tsh);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Tags NOT acked for key: " + this.sessionKey);
            }
        }
    }    
}    
