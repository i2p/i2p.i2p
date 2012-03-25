package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    private final Log _log;
    private final I2PAppContext _context;
    private int _maxBytesPerSecond;
    private volatile int _sendPeriodBytes;
    private volatile long _sendPeriodBeginTime;
    private final ReentrantLock _lock;
    private static final String PROP_MAX_BW = "i2cp.outboundBytesPerSecond";
    /** see ConnectionOptions in streaming  - MTU + streaming overhead + gzip overhead */
    private static final int TYP_SIZE = 1730 + 28 + 23;
    private static final int MIN_RATE = 2 * TYP_SIZE;

    public I2CPMessageProducer(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2CPMessageProducer.class);
        _lock = new ReentrantLock(true);
        context.statManager().createRateStat("client.sendThrottled", "Times waited for bandwidth", "ClientMessages", new long[] { 60*1000 });
        context.statManager().createRateStat("client.sendDropped", "Length of msg dropped waiting for bandwidth", "ClientMessages", new long[] { 60*1000 });
    }
    
    /** 
     * Update the bandwidth setting
     * @since 0.8.4
     */
    public void updateBandwidth(I2PSessionImpl session) {
        String max = session.getOptions().getProperty(PROP_MAX_BW);
        if (max != null) {
            try {
                int iMax = Integer.parseInt(max);
                if (iMax > 0)
                    // round up to next higher TYP_SIZE for efficiency, then add some fudge for small messages
                    _maxBytesPerSecond = 256 + Math.max(MIN_RATE, TYP_SIZE * ((iMax + TYP_SIZE - 1) / TYP_SIZE));
                else
                    _maxBytesPerSecond = 0;
            } catch (NumberFormatException nfe) {}
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Setting " + _maxBytesPerSecond + " BPS max");
    }

    /** 
     * Send all the messages that a client needs to send to a router to establish
     * a new session.  
     */
    public void connect(I2PSessionImpl session) throws I2PSessionException {
        updateBandwidth(session);
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
     * @param tag unused - no end-to-end crypto
     * @param tags unused - no end-to-end crypto
     * @param key unused - no end-to-end crypto
     * @param newKey unused - no end-to-end crypto
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload, SessionTag tag,
                            SessionKey key, Set tags, SessionKey newKey, long expires) throws I2PSessionException {
        sendMessage(session, dest, nonce, payload, expires, 0);
    }

    /**
     * Package up and send the payload to the router for delivery
     * @since 0.8.4
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload,
                            long expires, int flags) throws I2PSessionException {

        if (!updateBps(payload.length, expires))
            // drop the message... send fail notification?
            return;
        SendMessageMessage msg;
        if (expires > 0 || flags > 0) {
            SendMessageExpiresMessage smsg = new SendMessageExpiresMessage();
            smsg.setExpiration(expires);
            smsg.setFlags(flags);
            msg = smsg;
        } else
            msg = new SendMessageMessage();
        msg.setDestination(dest);
        msg.setSessionId(session.getSessionId());
        msg.setNonce(nonce);
        Payload data = createPayload(dest, payload, null, null, null, null);
        msg.setPayload(data);
        session.sendMessage(msg);
    }

    /**
     *  Super-simple bandwidth throttler.
     *  We only calculate on a one-second basis, so large messages
     *  (compared to the one-second limit) may exceed the limits.
     *  Tuned for streaming, may not work well for large datagrams.
     *
     *  This does poorly with low rate limits since it doesn't credit
     *  bandwidth across two periods. So the limit is rounded up,
     *  and the min limit is set to 2x the typ size, above.
     *
     *  Blocking so this could be very bad for retransmissions,
     *  as it could clog StreamingTimer.
     *  Waits are somewhat "fair" using ReentrantLock.
     *  While out-of-order transmission is acceptable, fairness
     *  reduces the chance of starvation. ReentrantLock does not
     *  guarantee in-order execution due to thread priority issues,
     *  so out-of-order may still occur. But shouldn't happen within
     *  the same thread anyway... Also note that small messages may
     *  go ahead of large ones that are waiting for the next window.
     *  Also, threads waiting a second time go to the back of the line.
     *
     *  Since this is at the I2CP layer, it includes streaming overhead,
     *  streaming acks and retransmissions,
     *  gzip overhead (or "underhead" for compression),
     *  repliable datagram overhead, etc.
     *  However, it does not, of course, include the substantial overhead
     *  imposed by the router for the leaseset, tags, encryption,
     *  and fixed-size tunnel messages.
     *
     *  @param expires if > 0, an expiration date
     *  @return true if we should send the message, false to drop it
     */
    private boolean updateBps(int len, long expires) {
        if (_maxBytesPerSecond <= 0)
            return true;
        //synchronized(this) {
        _lock.lock();
        try {
            int waitCount = 0;
            while (true) {
                long now = _context.clock().now();
                if (waitCount > 0 && expires > 0 && expires < now) {
                    // just say no to bufferbloat... drop the message right here
                    _context.statManager().addRateData("client.sendDropped", len, 0);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping " + len + " byte msg expired in queue");
                    return false;
                }

                long period = now - _sendPeriodBeginTime;
                if (period >= 2000) {
                    // start new period, always let it through no matter how big
                    _sendPeriodBytes = len;
                    _sendPeriodBeginTime = now;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("New period after idle, " + len + " bytes");
                    return true;
                }

                if (period >= 1000) {
                    // start new period
                    // Allow burst within 2 sec, only advance window by 1 sec, and
                    // every other second give credit for unused bytes in previous period
                    if (_sendPeriodBytes > 0 && ((_sendPeriodBeginTime / 1000) & 0x01) == 0)
                        _sendPeriodBytes += len - _maxBytesPerSecond;
                    else
                        _sendPeriodBytes = len;
                    _sendPeriodBeginTime += 1000;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("New period, " + len + " bytes");
                    return true;
                }

                if (_sendPeriodBytes + len <= _maxBytesPerSecond) {
                    // still bytes available in this period
                    _sendPeriodBytes += len;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Sending " + len + ", Elapsed " + period + "ms, total " + _sendPeriodBytes + " bytes");
                    return true;
                }

                if (waitCount >= 2) {
                    // just say no to bufferbloat... drop the message right here
                    _context.statManager().addRateData("client.sendDropped", len, 0);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping " + len + " byte msg after waiting " + waitCount + " times");
                    return false;
                }

                // wait until next period
                _context.statManager().addRateData("client.sendThrottled", ++waitCount, 0);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Throttled " + len + " bytes, wait #" + waitCount + ' ' + (1000 - period) + "ms" /*, new Exception()*/);
                try {
                    //this.wait(1000 - period);
                    _lock.newCondition().await(1000 - period, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {}
            }
        } finally {
            _lock.unlock();
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
     * @param tag unused - no end-to-end crypto
     * @param tags unused - no end-to-end crypto
     * @param key unused - no end-to-end crypto
     * @param newKey unused - no end-to-end crypto
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
