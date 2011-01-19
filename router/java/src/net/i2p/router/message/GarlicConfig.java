package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.i2p.data.Certificate;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DeliveryInstructions;

/**
 * Define the contents of a garlic chunk that contains 1 or more sub garlics
 *
 */
class GarlicConfig {
    private RouterInfo _recipient;
    private PublicKey _recipientPublicKey;
    private Certificate _cert;
    private long _id;
    private long _expiration;
    private final List _cloveConfigs;
    private DeliveryInstructions _instructions;
    private boolean _requestAck;
    private RouterInfo _replyThroughRouter; // router through which any replies will be sent before delivery to us
    private DeliveryInstructions _replyInstructions; // how the message will be sent from the replyThroughRouter to us
    private Certificate _replyBlockCertificate;
    private long _replyBlockMessageId;
    private long _replyBlockExpiration;
    
    public GarlicConfig() {
	_id = -1;
	_expiration = -1;
	_cloveConfigs = new ArrayList();
	_replyBlockMessageId = -1;
	_replyBlockExpiration = -1;
    }
    
    /**
     * Router to receive and process this clove - the router that will open the
     * delivery instructions and decide what to do process it locally as an I2NPMessage,
     * forward it as an I2NPMessage to a router, forward it as an I2NPMessage to a Destination,
     * or forward it as an I2NPMessage to a tunnel.
     *
     */
    public void setRecipient(RouterInfo info) { _recipient = info; }
    public RouterInfo getRecipient() { return _recipient; }
    
    /**
     * Public key of the router to receive and process this clove.  This is useful
     * for garlic routed messages encrypted to the router at the end of a tunnel,
     * as their RouterIdentity is not known, but a PublicKey they handle is exposed
     * via the LeaseSet
     *
     */
    public void setRecipientPublicKey(PublicKey recipientPublicKey) { _recipientPublicKey = recipientPublicKey; }
    public PublicKey getRecipientPublicKey() { return _recipientPublicKey; }
    
    /**
     * Certificate for the getRecipient() to pay for their processing
     *
     */
    public void setCertificate(Certificate cert) { _cert = cert; }
    public Certificate getCertificate() { return _cert; }
    
    /**
     * Unique ID of the clove
     *
     */
    public void setId(long id) { _id = id; }
    public long getId() { return _id; }
    
    /**
     * Expiration of the clove, after which it should be dropped
     *
     */
    public void setExpiration(long expiration) { _expiration = expiration; }
    public long getExpiration() { return _expiration; }
 
    /**
     * Specify how the I2NPMessage in the clove should be handled.
     *
     */
    public void setDeliveryInstructions(DeliveryInstructions instructions) { _instructions = instructions; }
    public DeliveryInstructions getDeliveryInstructions() { return _instructions; }
    
    /**
     * If true, the recipient of this clove is requested to send a DeliveryStatusMessage
     * back via the replyThroughRouter using the getId() value for the status' message Id.
     * Since those reply blocks are good for one use only, this flag should only be set if
     * no reply is expected.
     * 
     */
    public void setRequestAck(boolean request) { _requestAck = request; }
    public boolean getRequestAck() { return _requestAck; }
    
    /**
     * Specify the router through which a reply to this clove can be sent.  The
     * getReplyInstructions() are passed to this router during the reply process
     * and it them uses those to send the reply to this router.
     *
     */
    public void setReplyThroughRouter(RouterInfo replyThroughRouter) { _replyThroughRouter = replyThroughRouter; }
    public RouterInfo getReplyThroughRouter() { return _replyThroughRouter; }
    
    /**
     * Specify how any reply will be routed so that it reaches this router after being
     * delivered to the getReplyThroughRouter.  These instructions are not exposed to the
     * router who receives this garlic message in cleartext - they are instead encrypted to
     * the replyThrough router
     *
     */
    public void setReplyInstructions(DeliveryInstructions instructions) { _replyInstructions = instructions; }
    public DeliveryInstructions getReplyInstructions() { return _replyInstructions; }
    
    public long getReplyBlockMessageId() { return _replyBlockMessageId; }
    public void setReplyBlockMessageId(long id) { _replyBlockMessageId = id; }
    
    public Certificate getReplyBlockCertificate() { return _replyBlockCertificate; }
    public void setReplyBlockCertificate(Certificate cert) { _replyBlockCertificate = cert; }
    
    public long getReplyBlockExpiration() { return _replyBlockExpiration; }
    public void setReplyBlockExpiration(long expiration) { _replyBlockExpiration = expiration; }
    
    /**
     * Add a clove to the current message - if any cloves are added, an I2NP message
     * cannot be specified via setPayload.  This means that the resulting GarlicClove 
     * represented by this GarlicConfig must be a GarlicMessage itself
     *
     */
    public void addClove(GarlicConfig config) {
	if (config != null) {
	    _cloveConfigs.add(config);
	}
    }
    public int getCloveCount() { return _cloveConfigs.size(); }
    public GarlicConfig getClove(int index) { return (GarlicConfig)_cloveConfigs.get(index); }
    public void clearCloves() { _cloveConfigs.clear(); }
    
    
    protected String getSubData() { return ""; }
    private final static String NL = System.getProperty("line.separator");
    @Override
    public String toString() {
	StringBuilder buf = new StringBuilder();
	buf.append("<garlicConfig>").append(NL);
	buf.append("<certificate>").append(getCertificate()).append("</certificate>").append(NL);
	buf.append("<instructions>").append(getDeliveryInstructions()).append("</instructions>").append(NL);
	buf.append("<expiration>").append(new Date(getExpiration())).append("</expiration>").append(NL);
	buf.append("<garlicId>").append(getId()).append("</garlicId>").append(NL);
	buf.append("<recipient>").append(getRecipient()).append("</recipient>").append(NL);
	buf.append("<recipientPublicKey>").append(getRecipientPublicKey()).append("</recipientPublicKey>").append(NL);
	buf.append("<replyBlockCertificate>").append(getReplyBlockCertificate()).append("</replyBlockCertificate>").append(NL);
	buf.append("<replyBlockExpiration>").append(new Date(getReplyBlockExpiration())).append("</replyBlockExpiration>").append(NL);
	buf.append("<replyBlockMessageId>").append(getReplyBlockMessageId()).append("</replyBlockMessageId>").append(NL);
	buf.append("<replyInstructions>").append(getReplyInstructions()).append("</replyInstructions>").append(NL);
	buf.append("<replyThroughRouter>").append(getReplyThroughRouter()).append("</replyThroughRouter>").append(NL);
	buf.append("<requestAck>").append(getRequestAck()).append("</requestAck>").append(NL);
	buf.append(getSubData());
	buf.append("<subcloves>").append(NL);
	for (int i = 0; i < getCloveCount(); i++)
	    buf.append("<clove>").append(getClove(i)).append("</clove>").append(NL);
	buf.append("</subcloves>").append(NL);
	buf.append("</garlicConfig>").append(NL);
	return buf.toString();
    }
}
