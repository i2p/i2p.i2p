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
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Handle a particular client message that is destined for a remote destination.
 *
 */
class OutboundClientMessageJobHelper {
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
     * Unused?
     *
     * @param bundledReplyLeaseSet if specified, the given LeaseSet will be packaged with the message (allowing
     *                             much faster replies, since their netDb search will return almost instantly)
     * @return garlic, or null if no tunnels were found (or other errors)
     */
    static GarlicMessage createGarlicMessage(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                             Payload data, Hash from, Destination dest, TunnelInfo replyTunnel,
                                             SessionKey wrappedKey, Set<SessionTag> wrappedTags, 
                                             boolean requireAck, LeaseSet bundledReplyLeaseSet) {
        PayloadGarlicConfig dataClove = buildDataClove(ctx, data, dest, expiration);
        return createGarlicMessage(ctx, replyToken, expiration, recipientPK, dataClove, from, dest, replyTunnel, wrappedKey, 
                                   wrappedTags, requireAck, bundledReplyLeaseSet);
    }
    /**
     * Allow the app to specify the data clove directly, which enables OutboundClientMessage to resend the
     * same payload (including expiration and unique id) in different garlics (down different tunnels)
     *
     * This is called from OCMOSJ
     *
     * @return garlic, or null if no tunnels were found (or other errors)
     */
    static GarlicMessage createGarlicMessage(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                             PayloadGarlicConfig dataClove, Hash from, Destination dest, TunnelInfo replyTunnel, SessionKey wrappedKey, 
                                             Set<SessionTag> wrappedTags, boolean requireAck, LeaseSet bundledReplyLeaseSet) {
        GarlicConfig config = createGarlicConfig(ctx, replyToken, expiration, recipientPK, dataClove, from, dest, replyTunnel, requireAck, bundledReplyLeaseSet);
        if (config == null)
            return null;
        SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(from);
        if (skm == null)
            return null;
        GarlicMessage msg = GarlicMessageBuilder.buildMessage(ctx, config, wrappedKey, wrappedTags,
                                                              skm);
        return msg;
    }
    
    private static GarlicConfig createGarlicConfig(RouterContext ctx, long replyToken, long expiration, PublicKey recipientPK, 
                                                   PayloadGarlicConfig dataClove, Hash from, Destination dest, TunnelInfo replyTunnel, boolean requireAck,
                                                   LeaseSet bundledReplyLeaseSet) {
        Log log = ctx.logManager().getLog(OutboundClientMessageJobHelper.class);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Reply token: " + replyToken);
        GarlicConfig config = new GarlicConfig();
        
        config.addClove(dataClove);
        
        if (requireAck) {
            PayloadGarlicConfig ackClove = buildAckClove(ctx, from, replyTunnel, replyToken, expiration);
            if (ackClove == null)
                return null; // no tunnels
            config.addClove(ackClove);
        }
        
        if (bundledReplyLeaseSet != null) {
            PayloadGarlicConfig leaseSetClove = buildLeaseSetClove(ctx, expiration, bundledReplyLeaseSet);
            config.addClove(leaseSetClove);
        }
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        // defaults
        //instructions.setDelayRequested(false);
        //instructions.setDelaySeconds(0);
        //instructions.setEncrypted(false);
        //instructions.setEncryptionKey(null);
        //instructions.setRouter(null);
        //instructions.setTunnelId(null);
        
        config.setCertificate(Certificate.NULL_CERT);
        config.setDeliveryInstructions(instructions);
        config.setId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        config.setExpiration(expiration); // +2*Router.CLOCK_FUDGE_FACTOR);
        config.setRecipientPublicKey(recipientPK);
        config.setRequestAck(false);
        
        if (log.shouldLog(Log.INFO))
            log.info("Creating garlic config to be encrypted to " + recipientPK 
                     + " for destination " + dest.calculateHash().toBase64());
        
        return config;
    }
    
    /**
     * Build a clove that sends a DeliveryStatusMessage to us
     */
    private static PayloadGarlicConfig buildAckClove(RouterContext ctx, Hash from, TunnelInfo replyToTunnel, long replyToken, long expiration) {
        Log log = ctx.logManager().getLog(OutboundClientMessageJobHelper.class);
        PayloadGarlicConfig ackClove = new PayloadGarlicConfig();
        
        Hash replyToTunnelRouter = null; // inbound tunnel gateway
        TunnelId replyToTunnelId = null; // tunnel id on that gateway
        
        if (replyToTunnel == null) {
            if (log.shouldLog(Log.WARN))
                log.warn("Unable to send client message from " + from.toBase64() 
                         + ", as there are no inbound tunnels available");
            return null;
        }
        replyToTunnelId = replyToTunnel.getReceiveTunnelId(0);
        replyToTunnelRouter = replyToTunnel.getPeer(0);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Ack for the data message will come back along tunnel " + replyToTunnelId 
                      + ":\n" + replyToTunnel);
        
        DeliveryInstructions ackInstructions = new DeliveryInstructions();
        ackInstructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
        ackInstructions.setRouter(replyToTunnelRouter);
        ackInstructions.setTunnelId(replyToTunnelId);
        // defaults
        //ackInstructions.setDelayRequested(false);
        //ackInstructions.setDelaySeconds(0);
        //ackInstructions.setEncrypted(false);
        
        DeliveryStatusMessage msg = new DeliveryStatusMessage(ctx);
        msg.setArrival(ctx.clock().now());
        msg.setMessageId(replyToken);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Delivery status message key: " + replyToken + " arrival: " + msg.getArrival());
        
        ackClove.setCertificate(Certificate.NULL_CERT);
        ackClove.setDeliveryInstructions(ackInstructions);
        ackClove.setExpiration(expiration);
        ackClove.setId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        ackClove.setPayload(msg);
        ackClove.setRecipient(ctx.router().getRouterInfo());
        // defaults
        //ackClove.setRequestAck(false);
        
        if (log.shouldLog(Log.DEBUG))
            log.debug("Delivery status message is targetting us [" 
                      + ackClove.getRecipient().getIdentity().getHash().toBase64() 
                      + "] via tunnel " + replyToTunnelId.getTunnelId() + " on " 
                      + replyToTunnelRouter.toBase64());
        
        return ackClove;
    }
    
    /**
     * Build a clove that sends the payload to the destination
     */
    static PayloadGarlicConfig buildDataClove(RouterContext ctx, Payload data, Destination dest, long expiration) {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(dest.calculateHash());
        
        // defaults
        //instructions.setDelayRequested(false);
        //instructions.setDelaySeconds(0);
        //instructions.setEncrypted(false);
        
        clove.setCertificate(Certificate.NULL_CERT);
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(expiration);
        clove.setId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        DataMessage msg = new DataMessage(ctx);
        msg.setData(data.getEncryptedData());
        clove.setPayload(msg);
        // defaults
        //clove.setRecipientPublicKey(null);
        //clove.setRequestAck(false);
        
        return clove;
    }
    
    
    /**
     * Build a clove that stores the leaseSet locally 
     */
    static PayloadGarlicConfig buildLeaseSetClove(RouterContext ctx, long expiration, LeaseSet replyLeaseSet) {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        // defaults
        //instructions.setDelayRequested(false);
        //instructions.setDelaySeconds(0);
        //instructions.setEncrypted(false);
        
        clove.setCertificate(Certificate.NULL_CERT);
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(expiration);
        clove.setId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        DatabaseStoreMessage msg = new DatabaseStoreMessage(ctx);
        msg.setEntry(replyLeaseSet);
        msg.setMessageExpiration(expiration);
        clove.setPayload(msg);
        // defaults
        //clove.setRecipientPublicKey(null);
        //clove.setRequestAck(false);
        
        return clove;
    }
}
