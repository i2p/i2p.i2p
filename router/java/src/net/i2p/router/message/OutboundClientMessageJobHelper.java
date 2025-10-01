package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.crypto.ratchet.ReplyCallback;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.util.Log;

/**
 * Static methods to create a Garlic Message with one or more cloves, as follows:
 *
 * <pre>
 *
 * GarlicMessage
 *     Ack Clove (optional)
 *         Tunnel delivery instructions
 *         Garlic Message
 *             Delivery Status Clove
 *                 Local delivery instructions
 *                 Delivery Status Message
 *     LeaseSet Clove (optional)
 *         Local delivery instructions
 *         Database Store Message
 *     Data Clove (required)
 *         Destination delivery instructions
 *         Data Message
 *
 * </pre>
 *
 * The low-level construction is in GarlicMessageBuilder.
 *
 */
class OutboundClientMessageJobHelper {

    private static final long ACK_EXTRA_EXPIRATION = 60*1000;

    /**
     * Build a garlic message that will be delivered to the router on which the target is located.
     * Inside the message are two cloves: one containing the payload with instructions for
     * delivery to the (now local) destination, and the other containing a DeliveryStatusMessage with
     * instructions for delivery to an inbound tunnel of this router.
     *
     * How the DeliveryStatusMessage is wrapped can vary - it can be simply sent to a tunnel (as above),
     * wrapped in a GarlicMessage and source routed a few hops before being tunneled, source routed the
     * entire way back, or not wrapped at all - in which case the payload clove contains a SourceRouteBlock
     * and a request for a reply.
     *
     * For now, its just a tunneled DeliveryStatusMessage
     *
     * @param wrappedKey non-null with null data,
     *                   output parameter that will be filled with the SessionKey used
     * @param wrappedTags output parameter that will be filled with the sessionTags used
     * @param bundledReplyLeaseSet if specified, the given LeaseSet will be packaged with the message (allowing
     *                             much faster replies, since their netDb search will return almost instantly)
     * @param replyTunnel non-null if requireAck is true or bundledReplyLeaseSet is non-null
     * @param requireAck if true, bundle replyToken in an ack clove
     * @return garlic, or null if no tunnels were found (or other errors)
     */
/****
    static GarlicMessage createGarlicMessage(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                             Payload data, Hash from, Destination dest, TunnelInfo replyTunnel,
                                             SessionKey wrappedKey, Set<SessionTag> wrappedTags, 
                                             boolean requireAck, LeaseSet bundledReplyLeaseSet) {
        PayloadGarlicConfig dataClove = buildDataClove(ctx, data, dest, expiration);
        return createGarlicMessage(ctx, replyToken, expiration, recipientPK, dataClove, from, dest, replyTunnel,
                                   0, 0, wrappedKey, wrappedTags, requireAck, bundledReplyLeaseSet);
    }
****/

    /**
     * Allow the app to specify the data clove directly, which enables OutboundClientMessage to resend the
     * same payload (including expiration and unique id) in different garlics (down different tunnels)
     *
     * This is called from OCMOSJ
     *
     * @param dataClove may be null for ECIES-layer ack
     * @param tagsToSendOverride if &gt; 0, use this instead of skm's default
     * @param lowTagsOverride if &gt; 0, use this instead of skm's default
     * @param wrappedKey for ElGamal, non-null with null data,
     *                   output parameter that will be filled with the SessionKey used,
     *                   may be null for ECIES
     * @param wrappedTags for ElGamal, output parameter that will be filled with the sessionTags used,
     *                    may be null for ECIES
     * @param replyTunnel non-null if requireAck is true or bundledReplyLeaseSet is non-null
     * @param requireAck if true, bundle replyToken in an ack clove
     * @param bundledReplyLeaseSet may be null; if non-null, put it in a clove
     * @param callback only for ECIES, may be null
     * @return garlic, or null if no tunnels were found (or other errors)
     */
    static GarlicMessage createGarlicMessage(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                             PayloadGarlicConfig dataClove, Hash from, Destination dest, TunnelInfo replyTunnel,
                                             int tagsToSendOverride, int lowTagsOverride, SessionKey wrappedKey, 
                                             Set<SessionTag> wrappedTags, boolean requireAck, LeaseSet bundledReplyLeaseSet,
                                             ReplyCallback callback) {

        SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(from);
        if (skm == null)
            return null;
        boolean isECIES = recipientPK.getType() != EncType.ELGAMAL_2048;
        // force ack off if ECIES
        boolean ackInGarlic = isECIES ? false : requireAck;
        GarlicConfig config = createGarlicConfig(ctx, replyToken, expiration, recipientPK, dataClove,
                                                 from, dest, replyTunnel, ackInGarlic, bundledReplyLeaseSet, skm);
        if (config == null)
            return null;
        GarlicMessage msg;
        if (isECIES) {
            msg = GarlicMessageBuilder.buildECIESMessage(ctx, config, from, dest, skm, callback);
        } else {
            // no use sending tags unless we have a reply token set up already
            int tagsToSend = replyToken >= 0 ? (tagsToSendOverride > 0 ? tagsToSendOverride : skm.getTagsToSend()) : 0;
            int lowThreshold = lowTagsOverride > 0 ? lowTagsOverride : skm.getLowThreshold();
            msg = GarlicMessageBuilder.buildMessage(ctx, config, wrappedKey, wrappedTags,
                                                    tagsToSend, lowThreshold, skm);
        }
        return msg;
    }
    
    /**
     * Make the top-level config, with a data clove, an optional ack clove, and
     * an optional leaseset clove.
     *
     * The returned GarlicConfig will have the recipientPublicKey set.
     *
     * @param dataClove may be null for ECIES-layer ack
     * @param replyTunnel non-null if requireAck is true or bundledReplyLeaseSet is non-null
     * @param requireAck if true, bundle replyToken in an ack clove
     * @param bundledReplyLeaseSet may be null; if non-null, put it in a clove
     * @param skm encrypt dsm with this skm non-null
     * @return null on error
     */
    private static GarlicConfig createGarlicConfig(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                                   PayloadGarlicConfig dataClove, Hash from, Destination dest,
                                                   TunnelInfo replyTunnel, boolean requireAck,
                                                   LeaseSet bundledReplyLeaseSet, SessionKeyManager skm) {
        Log log = ctx.logManager().getLog(OutboundClientMessageJobHelper.class);
        if (replyToken >= 0 && log.shouldLog(Log.DEBUG))
            log.debug("Reply token: " + replyToken);
        // need random CloveSet ID as it's checked in receiver MessageValidator pre-0.9.44
        // See GarlicMessageReceiver
        GarlicConfig config = new GarlicConfig(Certificate.NULL_CERT,
                                               ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE),
                                               expiration, DeliveryInstructions.LOCAL);
        
        // for now, skip this for ratchet if there's no LS to bundle
        if (requireAck && (bundledReplyLeaseSet != null || recipientPK.getType() == EncType.ELGAMAL_2048)) {
            // extend the expiration of the return message
            PayloadGarlicConfig ackClove = buildAckClove(ctx, from, replyTunnel, replyToken,
                                                         expiration + ACK_EXTRA_EXPIRATION, skm);
            if (ackClove == null)
                return null; // no tunnels... TODO carry on anyway?
            config.addClove(ackClove);
        }
        
        if (bundledReplyLeaseSet != null) {
            PayloadGarlicConfig leaseSetClove = buildLeaseSetClove(ctx, expiration, bundledReplyLeaseSet);
            config.addClove(leaseSetClove);
        }
        
        // As of 0.9.2, since the receiver processes them in-order,
        // put data clove last to speed up the ack,
        // and get the leaseset stored before handling the data
        if (dataClove != null)
            config.addClove(dataClove);

        config.setRecipientPublicKey(recipientPK);
        
        if (log.shouldLog(Log.INFO))
            log.info("Creating garlic config to be encrypted to " + recipientPK 
                     + " for destination " + dest.calculateHash().toBase64());
        
        return config;
    }
    
    /**
     *  Build a clove that sends a DeliveryStatusMessage to us.
     *  As of 0.9.12, the DSM is wrapped in a GarlicMessage.
     *  @param skm encrypt dsm with this skm non-null
     *  @return null on error
     */
    private static PayloadGarlicConfig buildAckClove(RouterContext ctx, Hash from, TunnelInfo replyToTunnel,
                                                     long replyToken, long expiration,
                                                     SessionKeyManager skm) {
        Log log = ctx.logManager().getLog(OutboundClientMessageJobHelper.class);
        
        if (replyToTunnel == null) {
            if (log.shouldLog(Log.WARN))
                log.warn("Unable to send client message from " + from.toBase64() 
                         + ", as there are no inbound tunnels available");
            return null;
        }

        TunnelId replyToTunnelId = replyToTunnel.getReceiveTunnelId(0); // tunnel id on that gateway
        Hash replyToTunnelRouter = replyToTunnel.getPeer(0); // inbound tunnel gateway
        if (log.shouldLog(Log.DEBUG))
            log.debug("Ack for the data message will come back along tunnel " + replyToTunnelId 
                      + ": " + replyToTunnel);
        
        DeliveryInstructions ackInstructions = new DeliveryInstructions();
        ackInstructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
        ackInstructions.setRouter(replyToTunnelRouter);
        ackInstructions.setTunnelId(replyToTunnelId);
        // defaults
        //ackInstructions.setDelayRequested(false);
        //ackInstructions.setDelaySeconds(0);
        //ackInstructions.setEncrypted(false);
        
        DeliveryStatusMessage dsm = buildDSM(ctx, replyToken);
        // wrap the DSM if we can
        LeaseSetKeys lsk = ctx.keyManager().getKeys(from);
        I2NPMessage msg;
        if (lsk == null || lsk.isSupported(EncType.ELGAMAL_2048)) {
            msg = wrapDSM(ctx, skm, dsm, expiration);
            if (msg == null) {
                if (log.shouldLog(Log.WARN))
                    log.warn("Failed to wrap ack clove");
                return null;
            }
        } else {
            msg = dsm;
        }
        // need random CloveSet ID as it's checked in receiver GMR.isValid() MessageValidator pre-0.9.44
        // See GarlicMessageReceiver
        PayloadGarlicConfig ackClove = new PayloadGarlicConfig(Certificate.NULL_CERT,
                                                               ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE),
                                                               expiration, ackInstructions, msg);
        // this does nothing, the clove is not separately encrypted
        //ackClove.setRecipient(ctx.router().getRouterInfo());
        // defaults
        //ackClove.setRequestAck(false);
        
        //if (log.shouldLog(Log.DEBUG))
        //    log.debug("Delivery status message is targetting us [" 
        //              + ackClove.getRecipient().getIdentity().getHash().toBase64() 
        //              + "] via tunnel " + replyToTunnelId.getTunnelId() + " on " 
        //              + replyToTunnelRouter.toBase64());
        
        return ackClove;
    }
    
    /**
     *  Make a basic DSM
     *  @since 0.9.12
     */
    private static DeliveryStatusMessage buildDSM(RouterContext ctx, long replyToken) {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(ctx);
        msg.setArrival(ctx.clock().now());
        msg.setMessageId(replyToken);
        return msg;
    }

    /**
     *  As of 0.9.12, encrypt to hide it from the target and the return path OBEP and IBGW.
     *  Wrap a DSM in a GarlicMessage, add the fake session to the SKM.
     *
     *  @param skm encrypt dsm with this skm non-null
     *  @return null on error
     *  @since 0.9.12
     */
    private static GarlicMessage wrapDSM(RouterContext ctx, SessionKeyManager skm,
                                         DeliveryStatusMessage dsm, long expiration) {
        // garlic route that DeliveryStatusMessage to ourselves so the endpoints and gateways
        // can't tell its a test.  to simplify this, we encrypt it with a random key and tag,
        // remembering that key+tag so that we can decrypt it later.  this means we can do the
        // garlic encryption without any ElGamal (yay)
        long fromNow = expiration - ctx.clock().now();
        MessageWrapper.OneTimeSession sess = MessageWrapper.generateSession(ctx, skm, fromNow, true);
        GarlicMessage msg = MessageWrapper.wrap(ctx, dsm, sess);
        return msg;
    }

    /**
     * Build a clove that sends the payload to the destination
     */
/****
    private static PayloadGarlicConfig buildDataClove(RouterContext ctx, Payload data, Destination dest, long expiration) {
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(dest.calculateHash());
        
        // defaults
        //instructions.setDelayRequested(false);
        //instructions.setDelaySeconds(0);
        //instructions.setEncrypted(false);
        
        DataMessage msg = new DataMessage(ctx);
        msg.setData(data.getEncryptedData());
        // need random CloveSet ID as it's checked in receiver GMR.isValid() MessageValidator pre-0.9.44
        // See GarlicMessageReceiver
        PayloadGarlicConfig clove = new PayloadGarlicConfig(Certificate.NULL_CERT,
                                                            ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE),
                                                            expiration, instructions, msg);
        // defaults
        //clove.setRecipientPublicKey(null);
        //clove.setRequestAck(false);
        
        return clove;
    }
****/
    
    
    /**
     * Build a clove that stores the leaseSet locally 
     */
    private static PayloadGarlicConfig buildLeaseSetClove(RouterContext ctx, long expiration, LeaseSet replyLeaseSet) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(ctx);
        msg.setEntry(replyLeaseSet);
        msg.setMessageExpiration(expiration);
        // need random CloveSet ID as it's checked in receiver GMR.isValid() MessageValidator pre-0.9.44
        // See GarlicMessageReceiver
        PayloadGarlicConfig clove = new PayloadGarlicConfig(Certificate.NULL_CERT,
                                                            ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE),
                                                            expiration, DeliveryInstructions.LOCAL, msg);
        // defaults
        //clove.setRecipientPublicKey(null);
        //clove.setRequestAck(false);
        
        return clove;
    }
}
