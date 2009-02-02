package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.AbuseReason;
import net.i2p.data.i2cp.AbuseSeverity;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.ReconfigureSessionMessage;
import net.i2p.data.i2cp.ReportAbuseMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.util.Log;

/**
 * Produce the various messages the session needs to send to the router.
 *
 * @author jrandom
 */
class I2CPMessageProducer {
    private final static Log _log = new Log(I2CPMessageProducer.class);
    private I2PAppContext _context;
    private int _sendBps;
    private long _sendPeriodBytes;
    private long _sendPeriodBeginTime;

    public I2CPMessageProducer(I2PAppContext context) {
        _context = context;
        _sendBps = 0;
        context.statManager().createRateStat("client.sendBpsRaw", "How fast we pump out I2CP data messages", "ClientMessages", new long[] { 60*1000, 5*60*1000, 10*60*1000, 60*60*1000 });
    }
    
    /** 
     * Send all the messages that a client needs to send to a router to establish
     * a new session.  
     */
    public void connect(I2PSessionImpl session) throws I2PSessionException {
        CreateSessionMessage msg = new CreateSessionMessage();
        SessionConfig cfg = new SessionConfig(session.getMyDestination());
        cfg.setOptions(session.getOptions());
        if (_log.shouldLog(Log.DEBUG)) _log.debug("config created");
        try {
            cfg.signSessionConfig(session.getPrivateKey());
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Unable to sign the session config", dfe);
        }
        if (_log.shouldLog(Log.DEBUG)) _log.debug("config signed");
        msg.setSessionConfig(cfg);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("config loaded into message");
        session.sendMessage(msg);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("config message sent");
    }

    /**
     * Send messages to the router destroying the session and disconnecting
     *
     */
    public void disconnect(I2PSessionImpl session) throws I2PSessionException {
        if (session.isClosed()) return;
        DestroySessionMessage dmsg = new DestroySessionMessage();
        dmsg.setSessionId(session.getSessionId());
        session.sendMessage(dmsg);
        // use DisconnectMessage only if we fail and drop connection... 
        // todo: update the code to fire off DisconnectMessage on socket error
        //DisconnectMessage msg = new DisconnectMessage();
        //msg.setReason("Destroy called");
        //session.sendMessage(msg);
    }

    /**
     * Package up and send the payload to the router for delivery
     *
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload, SessionTag tag,
                            SessionKey key, Set tags, SessionKey newKey, long expires) throws I2PSessionException {
        SendMessageMessage msg;
        if (expires > 0) {
            msg = new SendMessageExpiresMessage();
            ((SendMessageExpiresMessage)msg).setExpiration(new Date(expires));
        } else
            msg = new SendMessageMessage();
        msg.setDestination(dest);
        msg.setSessionId(session.getSessionId());
        msg.setNonce(nonce);
        Payload data = createPayload(dest, payload, tag, key, tags, newKey);
        msg.setPayload(data);
        session.sendMessage(msg);
        updateBps(payload.length);
    }

    private void updateBps(int len) {
        long now = _context.clock().now();
        float period = ((float)now-_sendPeriodBeginTime)/1000f;
        if (period >= 1f) {
            // first term decays on slow transmission
            _sendBps = (int)(((float)0.9f * (float)_sendBps) + ((float)0.1f*((float)_sendPeriodBytes)/period));
            _sendPeriodBytes = len;
            _sendPeriodBeginTime = now;
            _context.statManager().addRateData("client.sendBpsRaw", _sendBps, 0);
        } else {
            _sendPeriodBytes += len;
        }
    }
    
    /** 
     * Should we include the I2CP end to end crypto (which is in addition to any
     * garlic crypto added by the router)
     *
     */
    static final boolean END_TO_END_CRYPTO = false;
    
    /**
     * Create a new signed payload and send it off to the destination
     *
     */
    private Payload createPayload(Destination dest, byte[] payload, SessionTag tag, SessionKey key, Set tags,
                                  SessionKey newKey) throws I2PSessionException {
        if (dest == null) throw new I2PSessionException("No destination specified");
        if (payload == null) throw new I2PSessionException("No payload specified");

        Payload data = new Payload();
        if (!END_TO_END_CRYPTO) {
            data.setEncryptedData(payload);
            return data;
        }
        // no padding at this level
        // the garlic may pad, and the tunnels may pad, and the transports may pad
        int size = payload.length;
        byte encr[] = _context.elGamalAESEngine().encrypt(payload, dest.getPublicKey(), key, tags, tag, newKey, size);
        // yes, in an intelligent component, newTags would be queued for confirmation along with key, and
        // generateNewTags would only generate tags if necessary

        data.setEncryptedData(encr);
        //_log.debug("Encrypting the payload to public key " + dest.getPublicKey().toBase64() + "\nPayload: "
        //           + data.calculateHash());
        return data;
    }

    /**
     * Send an abuse message to the router
     */
    public void reportAbuse(I2PSessionImpl session, int msgId, int severity) throws I2PSessionException {
        ReportAbuseMessage msg = new ReportAbuseMessage();
        MessageId id = new MessageId();
        id.setMessageId(msgId);
        msg.setMessageId(id);
        AbuseReason reason = new AbuseReason();
        reason.setReason("Not specified");
        msg.setReason(reason);
        AbuseSeverity sv = new AbuseSeverity();
        sv.setSeverity(severity);
        msg.setSeverity(sv);
        session.sendMessage(msg);
    }

    /**
     * Create a new signed leaseSet in response to a request to do so and send it
     * to the router
     * 
     */
    public void createLeaseSet(I2PSessionImpl session, LeaseSet leaseSet, SigningPrivateKey signingPriv, PrivateKey priv)
                                                                                                                         throws I2PSessionException {
        CreateLeaseSetMessage msg = new CreateLeaseSetMessage();
        msg.setLeaseSet(leaseSet);
        msg.setPrivateKey(priv);
        msg.setSigningPrivateKey(signingPriv);
        msg.setSessionId(session.getSessionId());
        session.sendMessage(msg);
    }

    /**
     * Update number of tunnels
     * 
     * @param tunnels 0 for original configured number
     */
    public void updateTunnels(I2PSessionImpl session, int tunnels) throws I2PSessionException {
        ReconfigureSessionMessage msg = new ReconfigureSessionMessage();
        SessionConfig cfg = new SessionConfig(session.getMyDestination());
        Properties props = session.getOptions();
        if (tunnels > 0) {
            Properties newprops = new Properties();
            newprops.putAll(props);
            props = newprops;
            props.setProperty("inbound.quantity", "" + tunnels);
            props.setProperty("outbound.quantity", "" + tunnels);
            props.setProperty("inbound.backupQuantity", "0");
            props.setProperty("outbound.backupQuantity", "0");
        }
        cfg.setOptions(props);
        try {
            cfg.signSessionConfig(session.getPrivateKey());
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Unable to sign the session config", dfe);
        }
        msg.setSessionConfig(cfg);
        msg.setSessionId(session.getSessionId());
        session.sendMessage(msg);
    }
}
