package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.client.I2PClient;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.Payload;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.BlindingInfoMessage;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateLeaseSet2Message;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.HostLookupMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;
import net.i2p.data.i2cp.ReconfigureSessionMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.RandomSource;

/**
 * Receive events from the client and handle them accordingly (updating the runner when
 * necessary)
 *
 */
class ClientMessageEventListener implements I2CPMessageReader.I2CPMessageEventListener {
    private final Log _log;
    protected final RouterContext _context;
    protected final ClientConnectionRunner _runner;
    private final boolean  _enforceAuth;
    private volatile boolean _authorized;
    
    private static final String PROP_AUTH = "i2cp.auth";
    /** if true, user/pw must be in GetDateMessage */
    private static final String PROP_AUTH_STRICT = "i2cp.strictAuth";

    /**
     *  @param enforceAuth set false for in-JVM, true for socket access
     */
    public ClientMessageEventListener(RouterContext context, ClientConnectionRunner runner, boolean enforceAuth) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessageEventListener.class);
        _runner = runner;
        _enforceAuth = enforceAuth;
        if ((!_enforceAuth) || !_context.getBooleanProperty(PROP_AUTH))
            _authorized = true;
        _context.statManager().createRateStat("client.distributeTime", "How long it took to inject the client message into the router", "ClientMessages", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    /**
     * Handle an incoming message and dispatch it to the appropriate handler
     *
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        if (_runner.isDead()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received but runner dead: \n" + message);
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Message received: \n" + message);
        int type = message.getType();
        if (!_authorized) {
            // Default true as of 0.9.16
            boolean strict = _context.getBooleanPropertyDefaultTrue(PROP_AUTH_STRICT);
            if ((strict && type != GetDateMessage.MESSAGE_TYPE) ||
                (type != CreateSessionMessage.MESSAGE_TYPE &&
                 type != GetDateMessage.MESSAGE_TYPE &&
                 type != DestLookupMessage.MESSAGE_TYPE &&
                 type != GetBandwidthLimitsMessage.MESSAGE_TYPE)) {
                _log.error("Received message type " + type + " without required authentication");
                _runner.disconnectClient("Authorization required");
                return;
            }
        }
        switch (message.getType()) {
            case GetDateMessage.MESSAGE_TYPE:
                handleGetDate((GetDateMessage)message);
                break;
            case SetDateMessage.MESSAGE_TYPE:
                handleSetDate((SetDateMessage)message);
                break;
            case CreateSessionMessage.MESSAGE_TYPE:
                handleCreateSession((CreateSessionMessage)message);
                break;
            case SendMessageMessage.MESSAGE_TYPE:
                handleSendMessage((SendMessageMessage)message);
                break;
            case SendMessageExpiresMessage.MESSAGE_TYPE:
                handleSendMessage((SendMessageExpiresMessage)message);
                break;
            case ReceiveMessageBeginMessage.MESSAGE_TYPE:
                handleReceiveBegin((ReceiveMessageBeginMessage)message);
                break;
            case ReceiveMessageEndMessage.MESSAGE_TYPE:
                handleReceiveEnd((ReceiveMessageEndMessage)message);
                break;
            case CreateLeaseSetMessage.MESSAGE_TYPE:
            case CreateLeaseSet2Message.MESSAGE_TYPE:
                handleCreateLeaseSet((CreateLeaseSetMessage)message);
                break;
            case DestroySessionMessage.MESSAGE_TYPE:
                handleDestroySession((DestroySessionMessage)message);
                break;
            case DestLookupMessage.MESSAGE_TYPE:
                handleDestLookup((DestLookupMessage)message);
                break;
            case HostLookupMessage.MESSAGE_TYPE:
                handleHostLookup((HostLookupMessage)message);
                break;
            case ReconfigureSessionMessage.MESSAGE_TYPE:
                handleReconfigureSession((ReconfigureSessionMessage)message);
                break;
            case GetBandwidthLimitsMessage.MESSAGE_TYPE:
                handleGetBWLimits((GetBandwidthLimitsMessage)message);
                break;
            case BlindingInfoMessage.MESSAGE_TYPE:
                handleBlindingInfo((BlindingInfoMessage)message);
                break;
            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unhandled I2CP type received: " + message.getType());
        }
    }

    /**
     * Handle notification that there was an error
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.ERROR))
            _log.error("Error occurred", error);
        // Is this is a little drastic for an unknown message type?
        // Send the whole exception string over for diagnostics
        _runner.disconnectClient(error.toString());
        _runner.stopRunning();
    }
  
    public void disconnected(I2CPMessageReader reader) {
        if (_runner.isDead()) return;
        _runner.disconnected();
    }
    
    /**
     *  Defaults in GetDateMessage options are NOT honored.
     *  Defaults are not serialized out-of-JVM, and the router does not recognize defaults in-JVM.
     *  Client side must promote defaults to the primary map.
     */
    private void handleGetDate(GetDateMessage message) {
        // sent by clients >= 0.8.7
        String clientVersion = message.getVersion();
        if (clientVersion != null)
            _runner.setClientVersion(clientVersion);
        Properties props = message.getOptions();
        if (!checkAuth(props))
            return;
        try {
            // only send version if the client can handle it (0.8.7 or greater)
            _runner.doSend(new SetDateMessage(clientVersion != null ? CoreVersion.PUBLISHED_VERSION : null));
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the setDate message", ime);
        }
    }

    /**
     *  As of 0.8.7, does nothing. Do not allow a client to set the router's clock.
     */
    private void handleSetDate(SetDateMessage message) {
        //_context.clock().setNow(message.getDate().getTime());
    }
    
    /** 
     * Handle a CreateSessionMessage.
     * On errors, we could perhaps send a SessionStatusMessage with STATUS_INVALID before
     * sending the DisconnectMessage... but right now the client will send _us_ a
     * DisconnectMessage in return, and not wait around for our DisconnectMessage.
     * So keep it simple.
     *
     * Defaults in SessionConfig options are, in general, NOT honored.
     * In-JVM client side must promote defaults to the primary map.
     */
    private void handleCreateSession(CreateSessionMessage message) {
        SessionConfig in = message.getSessionConfig();
        Destination dest = in.getDestination();
        if (dest.getEncType() != EncType.ELGAMAL_2048) {
            // Enc type in key cert, proposal 145, unsupported
            _runner.disconnectClient("Non-ElGamal encryption type in key certificate unsupported");
            return;
        }
        if (in.verifySignature()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Signature verified correctly on create session message");
        } else {
            // For now, we do NOT send a SessionStatusMessage - see javadoc above
            int itype = dest.getCertificate().getCertificateType();
            SigType stype = SigType.getByCode(itype);
            if (stype == null || !stype.isAvailable()) {
                _log.error("Client requested unsupported signature type " + itype);
                _runner.disconnectClient("Unsupported signature type " + itype);
            } else if (in.tooOld()) {
                long skew = _context.clock().now() - in.getCreationDate().getTime();
                String msg = "Create session message client clock skew? ";
                if (skew >= 0)
                    msg += DataHelper.formatDuration(skew) + " in the past";
                else
                    msg += DataHelper.formatDuration(0 - skew) + " in the future";
                _log.error(msg);
                _runner.disconnectClient(msg);
            } else if (in.getOfflineSignature() != null && in.getOfflineExpiration() < _context.clock().now()) {
                String msg = "Offline signature for tunnel expired " + DataHelper.formatTime(in.getOfflineExpiration());
                _log.log(Log.CRIT, msg);
                _runner.disconnectClient(msg);
            } else {
                _log.error("Signature verification failed on a create session message:\n" + in);
                _runner.disconnectClient("Invalid signature on CreateSessionMessage");
            }
            return;
        }

        // Auth, since 0.8.2
        Properties inProps = in.getOptions();
        if (!checkAuth(inProps))
            return;

        SessionId id = _runner.getSessionId(dest.calculateHash());
        if (id != null) {
            _runner.disconnectClient("Already have session " + id);
            return;
        }

        // Copy over the whole config structure so we don't later corrupt it on
        // the client side if we change settings or later get a
        // ReconfigureSessionMessage
        SessionConfig cfg = new SessionConfig(dest);
        cfg.setSignature(in.getSignature());
        Properties props = new Properties();
        boolean isPrimary = _runner.getSessionIds().isEmpty();
        if (!isPrimary) {
            // all the primary options, then the overrides from the alias
            SessionConfig pcfg = _runner.getPrimaryConfig();
            if (pcfg != null) {
                props.putAll(pcfg.getOptions());
            } else {
                _log.error("no primary config?");
            }
        }
        props.putAll(inProps);
        String senc = props.getProperty("i2cp.leaseSetEncType");
        if (senc != null) {
            String[] senca = DataHelper.split(senc, ",");
            for (String sencaa : senca) {
                EncType type = EncType.parseEncType(sencaa);
                if (type != null) {
                    if (!type.isAvailable()) {
                        String msg = "Unsupported crypto type: " + type;
                        _log.error(msg);
                        _runner.disconnectClient(msg);
                        return;
                    }
                } else {
                    String msg = "Unsupported crypto type: " + sencaa;
                    _log.error(msg);
                    _runner.disconnectClient(msg);
                    return;
                }
            }
        }
        String lsType = props.getProperty("i2cp.leaseSetType");
        if ("5".equals(lsType)) {
            SigType stype = dest.getSigningPublicKey().getType();
            if (stype != SigType.EdDSA_SHA512_Ed25519 &&
                stype != SigType.RedDSA_SHA512_Ed25519) {
                _runner.disconnectClient("Invalid sig type for encrypted LS");
                return;
            }
        } else if ("7".equals(lsType)) {
            // Prevent tunnel builds for Meta LS
            // more TODO
            props.setProperty("inbound.length", "0");
            props.setProperty("outbound.length", "0");
            props.setProperty("inbound.lengthVariance", "0");
            props.setProperty("outbound.lengthVariance", "0");
        } else if (lsType == null && props.getProperty(SessionConfig.PROP_OFFLINE_SIGNATURE) != null) {
            // force type 3
            props.setProperty("i2cp.leaseSetType", "3");
        }
        cfg.setOptions(props);
        // this sets the session id
        int status = _runner.sessionEstablished(cfg);
        if (status != SessionStatusMessage.STATUS_CREATED) {
            // For now, we do NOT send a SessionStatusMessage - see javadoc above
            if (_log.shouldLog(Log.ERROR))
                _log.error("Session establish failed: code = " + status);
            String msg;
            if (status == SessionStatusMessage.STATUS_DUP_DEST) {
                msg = "duplicate destination";
                // not in spec, change to INVALID if we send it
                // status = SessionStatusMessage.STATUS_INVALID
            } else if (status == SessionStatusMessage.STATUS_INVALID) {
                msg = "bad session configuration parameters";
            } else if (status == SessionStatusMessage.STATUS_REFUSED) {
                msg = "session limit exceeded";
            } else {
                msg = "unknown error";
            }
            _runner.disconnectClient(msg);
            return;
        }
        // get the new session ID
        id = _runner.getSessionId(dest.calculateHash());

        if (_log.shouldLog(Log.INFO))
            _log.info("Session " + id + " established for " + dest.calculateHash());
        if (isPrimary) {
            sendStatusMessage(id, status);
            startCreateSessionJob(cfg);
        } else {
            SessionConfig pcfg = _runner.getPrimaryConfig();
            if (pcfg != null) {
                ClientTunnelSettings settings = new ClientTunnelSettings(dest.calculateHash());
                settings.readFromProperties(props);
                // addAlias() sends the create lease set msg, so we have to send the SMS first
                sendStatusMessage(id, status);
                boolean ok = _context.tunnelManager().addAlias(dest, settings, pcfg.getDestination());
                if (!ok) {
                    _log.error("Add alias failed");
                    // FIXME cleanup
                }
            } else {
                _log.error("no primary config?");
                status = SessionStatusMessage.STATUS_INVALID;
                sendStatusMessage(id, status);
                // FIXME cleanup
            }
        }
    }
    
    /**
     *  Side effect - sets _authorized.
     *  Side effect - disconnects session if not authorized.
     *
     *  @param props contains i2cp.username and i2cp.password, may be null
     *  @return success
     *  @since 0.9.11
     */
    private boolean checkAuth(Properties props) {
        if (_authorized)
            return true;
        if (_enforceAuth && _context.getBooleanProperty(PROP_AUTH)) {
            String user = null;
            String pw = null;
            if (props != null) {
                user = props.getProperty(I2PClient.PROP_USER);
                pw = props.getProperty(I2PClient.PROP_PW);
            }
            if (user == null || user.length() == 0 || pw == null || pw.length() == 0) {
                _log.logAlways(Log.WARN, "I2CP authentication failed");
                _runner.disconnectClient("Authorization required, specify i2cp.username and i2cp.password in options");
                _authorized = false;
                return false;
            }
            PasswordManager mgr = new PasswordManager(_context);
            if (!mgr.checkHash(PROP_AUTH, user, pw)) {
                _log.logAlways(Log.WARN, "I2CP authentication failed, user: " + user);
                _runner.disconnectClient("Authorization failed, user = " + user);
                _authorized = false;
                return false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("I2CP auth success user: " + user);
        }
        _authorized = true;
        return true;
    }

    /**
     *  Override for testing
     *  @since 0.9.8
     *
     */
    protected void startCreateSessionJob(SessionConfig config) {
        _context.jobQueue().addJob(new CreateSessionJob(_context, config));
    }
    
    /**
     * Handle a SendMessageMessage: give it a message Id, have the ClientManager distribute
     * it, and send the client an ACCEPTED message
     *
     */
    private void handleSendMessage(SendMessageMessage message) {
        SessionId sid = message.getSessionId();
        SessionConfig cfg = _runner.getConfig(sid);
        if (cfg == null) {
            List<SessionId> current = _runner.getSessionIds();
            String msg = "SendMessage invalid session: " + sid + " current: " + current;
            if (_log.shouldLog(Log.ERROR))
                _log.error(msg);
            // Just drop the message for now, don't kill the whole socket...
            // bugs on client side, esp. prior to 0.9.21, may cause sending
            // of messages before the session is established
            //_runner.disconnectClient(msg);
            // do this instead:
            if (sid != null && message.getNonce() > 0) {
                MessageStatusMessage status = new MessageStatusMessage();
                status.setMessageId(_runner.getNextMessageId());
                status.setSessionId(sid.getSessionId());
                status.setSize(0);
                status.setNonce(message.getNonce()); 
                status.setStatus(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION);
                try {
                    _runner.doSend(status);
                } catch (I2CPMessageException ime) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error writing out the message status message", ime);
                }
            }
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("handleSendMessage called");
        long beforeDistribute = _context.clock().now();
        MessageId id;
        try {
            // ClientMessagePool runs OCMOSJ inline
            // don't let bugs there kill the whole session
            id = _runner.distributeMessage(message);
        } catch (Exception e) {
            _log.error("Error sending message", e);
            MessageStatusMessage status = new MessageStatusMessage();
            status.setMessageId(_runner.getNextMessageId());
            status.setSessionId(sid.getSessionId());
            status.setSize(0);
            status.setNonce(message.getNonce()); 
            status.setStatus(MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER);
            try {
                _runner.doSend(status);
            } catch (I2CPMessageException ime) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error writing out the message status message", ime);
            }
            return;
        }
        long timeToDistribute = _context.clock().now() - beforeDistribute;
        // TODO validate session id
        _runner.ackSendMessage(sid, id, message.getNonce());
        _context.statManager().addRateData("client.distributeTime", timeToDistribute);
        if ( (timeToDistribute > 50) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Took too long to distribute the message (which holds up the ack): " + timeToDistribute);
    }

    
    /**
     * The client asked for a message, so we send it to them.  
     *
     * This is only when not in fast receive mode.
     * In the default fast receive mode, data is sent in MessageReceivedJob.
     */
    private void handleReceiveBegin(ReceiveMessageBeginMessage message) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling receive begin: id = " + message.getMessageId());
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(message.getMessageId());
        // TODO validate session id
        msg.setSessionId(message.getSessionId());
        Payload payload = _runner.getPayload(new MessageId(message.getMessageId()));
        if (payload == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Payload for message id [" + message.getMessageId() 
                           + "] is null!  Dropped or Unknown message id");
            return;
        }
        msg.setPayload(payload);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            String emsg = "Error sending data to client " + _runner.getDestHash();
            if (_log.shouldWarn())
                _log.warn(emsg, ime);
            else
                _log.logAlways(Log.WARN, emsg);
            _runner.removePayload(new MessageId(message.getMessageId()));
        }
    }
    
    /**
     * The client told us that the message has been received completely.  This currently
     * does not do any security checking prior to removing the message from the 
     * pending queue, though it should.
     *
     */
    private void handleReceiveEnd(ReceiveMessageEndMessage message) {
        _runner.removePayload(new MessageId(message.getMessageId()));
    }
    
    private void handleDestroySession(DestroySessionMessage message) {
        SessionId id = message.getSessionId();
        if (id != null) {
            _runner.removeSession(id);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("destroy session with null ID");
        }
        int left = _runner.getSessionIds().size();
        if (left <= 0 || id == null) {
            _runner.stopRunning();
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Still " + left + " sessions left");
        }
    }
    
    /** override for testing */
    protected void handleCreateLeaseSet(CreateLeaseSetMessage message) {
        LeaseSet ls = message.getLeaseSet();
        if (ls == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null lease set granted: " + message);
            _runner.disconnectClient("Invalid CreateLeaseSetMessage - null LS");
            return;
        }
        int type = ls.getType();
        if (type != DatabaseEntry.KEY_TYPE_META_LS2 &&
            type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 &&
            message.getPrivateKey() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null private keys: " + message);
            _runner.disconnectClient("Invalid CreateLeaseSetMessage - null private keys");
            return;
        }
        if (type == DatabaseEntry.KEY_TYPE_LEASESET && message.getSigningPrivateKey() == null) {
            // revocation keys only in LS1
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null private keys: " + message);
            _runner.disconnectClient("Invalid CreateLeaseSetMessage - null private keys");
            return;
        }
        SessionId id = message.getSessionId();
        SessionConfig cfg = _runner.getConfig(id);
        if (cfg == null) {
            List<SessionId> current = _runner.getSessionIds();
            String msg = "CreateLeaseSet invalid session: " + id + " current: " + current;
            if (_log.shouldLog(Log.ERROR))
                _log.error(msg);
            _runner.disconnectClient(msg);
            return;
        }
        Destination dest = cfg.getDestination();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // so we can decrypt it
            // secret must be set before destination
            String secret = cfg.getOptions().getProperty("i2cp.leaseSetSecret");
            if (secret != null) {
                EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                secret = DataHelper.getUTF8(Base64.decode(secret));
                encls.setSecret(secret);
            }
            try {
                ls.setDestination(dest);
            } catch (RuntimeException re) {
                if (_log.shouldError())
                    _log.error("Error decrypting leaseset from client", re);
                _runner.disconnectClient(re.toString());
                return;
            }
            // per-client auth
            // we have to do this before verifySignature()
            String pk = cfg.getOptions().getProperty("i2cp.leaseSetPrivKey");
            if (pk != null) {
                byte[] priv = Base64.decode(pk);
                PrivateKey privkey = new PrivateKey(EncType.ECIES_X25519, priv);
                EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                encls.setClientPrivateKey(privkey);
            }
            // we have to do this before checking encryption keys below
            if (!ls.verifySignature()) {
                if (_log.shouldError())
                    _log.error("Error decrypting leaseset from client");
                _runner.disconnectClient("Error decrypting leaseset from client");
                return;
            }
        } else {
            Destination ndest = ls.getDestination();
            if (!dest.equals(ndest)) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Different destination in LS");
                _runner.disconnectClient("Different destination in LS");
                return;
            }
        }
        if (type != DatabaseEntry.KEY_TYPE_META_LS2) {
            LeaseSetKeys keys = _context.keyManager().getKeys(dest);
            if (keys == null ||
                !message.getPrivateKey().equals(keys.getDecryptionKey())) {
                // Verify and register crypto keys if new or if changed
                // Private crypto key should never change, and if it does,
                // one of the checks below will fail
                if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                    // LS1
                    PublicKey pk;
                    try {
                        pk = message.getPrivateKey().toPublic();
                    } catch (IllegalArgumentException iae) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Bad private key in LS");
                        _runner.disconnectClient("Bad private key in LS");
                        return;
                    }
                    if (!pk.equals(ls.getEncryptionKey())) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Private/public crypto key mismatch in LS");
                        _runner.disconnectClient("Private/public crypto key mismatch in LS");
                        return;
                    }
                    // just register new SPK, don't verify, unused
                    _context.keyManager().registerKeys(dest, message.getSigningPrivateKey(), message.getPrivateKey());
                } else {
                    // LS2
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    CreateLeaseSet2Message msg2 = (CreateLeaseSet2Message) message;
                    List<PublicKey> eks = ls2.getEncryptionKeys();
                    List<PrivateKey> pks = msg2.getPrivateKeys();
                    for (int i = 0; i < eks.size(); i++) {
                        PublicKey ek = eks.get(i);
                        PublicKey pk;
                        try {
                            pk = pks.get(i).toPublic();
                        } catch (IllegalArgumentException iae) {
                            if (_log.shouldLog(Log.ERROR))
                                _log.error("Bad private key in LS: " + i);
                            _runner.disconnectClient("Bad private key in LS: " + i);
                            return;
                        }
                        if (!pk.equals(ek)) {
                            if (_log.shouldLog(Log.ERROR))
                                _log.error("Private/public crypto key mismatch in LS for key: " + i);
                            _runner.disconnectClient("Private/public crypto key mismatch in LS for key: " + i);
                            return;
                        }
                    }
                    // just register new SPK, don't verify, unused
                    _context.keyManager().registerKeys(dest, message.getSigningPrivateKey(), pks);
                }
            } else if (message.getSigningPrivateKey() != null &&
                       !message.getSigningPrivateKey().equals(keys.getRevocationKey())) {
                // just register new SPK, don't verify, unused
                _context.keyManager().registerKeys(dest, message.getSigningPrivateKey(), message.getPrivateKey());
            }
        }
        try {
            if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                // so the client manager knows it's a local hash
                // we could put this in runner.leaseSetCreated(), but
                // need to set it before calling publish()
                Hash newHash = ls.getHash();
                boolean ok = _runner.registerEncryptedLS(newHash);
                if (!ok) {
                    _runner.disconnectClient("Duplicate hash of encrypted LS2");
                    return;
                }
            }
            if (_log.shouldDebug())
                _log.debug("Publishing: " + ls);
            _context.netDb().publish(ls);
            if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                // store the decrypted ls also
                EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                if (_log.shouldDebug())
                    _log.debug("Storing decrypted: " + encls.getDecryptedLeaseSet());
                _context.netDb().store(dest.getHash(), encls.getDecryptedLeaseSet());
            }
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid leaseset from client", iae);
            _runner.disconnectClient("Invalid leaseset: " + iae);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("New lease set granted for destination " + dest);

        // leaseSetCreated takes care of all the LeaseRequestState stuff (including firing any jobs)
        _runner.leaseSetCreated(ls);
    }

    /** override for testing */
    protected void handleDestLookup(DestLookupMessage message) {
        // no session id in DLM
        _context.jobQueue().addJob(new LookupDestJob(_context, _runner, message.getHash(),
                                                     _runner.getDestHash()));
    }

    /**
     * override for testing
     * @since 0.9.11
     */
    protected void handleHostLookup(HostLookupMessage message) {
        SessionId sid = message.getSessionId();
        Hash h;
        if (sid != null) {
            h = _runner.getDestHash(sid);
        } else {
            // fixup if necessary
            if (message.getReqID() >= 0)
                sid = new SessionId(65535);
            h = null;
        }
        if (h == null) {
            h = _runner.getDestHash();
            // h may still be null, an LS lookup for b32 will go out expl. tunnels
        }
        _context.jobQueue().addJob(new LookupDestJob(_context, _runner, message.getReqID(),
                                                     message.getTimeout(), sid,
                                                     message.getHash(), message.getHostname(), h));
    }

    /**
     * Message's Session ID ignored. This doesn't support removing previously set options.
     * Nor do we bother with message.getSessionConfig().verifySignature() ... should we?
     * Nor is the Date checked.
     *
     * Note that this does NOT update the few options handled in
     * ClientConnectionRunner.sessionEstablished(). Those can't be changed later.
     *
     * Defaults in SessionConfig options are, in general, NOT honored.
     * In-JVM client side must promote defaults to the primary map.
     */
    private void handleReconfigureSession(ReconfigureSessionMessage message) {
        SessionId id = message.getSessionId();
        SessionConfig cfg = _runner.getConfig(id);
        if (cfg == null) {
            List<SessionId> current = _runner.getSessionIds();
            String msg = "ReconfigureSession invalid session: " + id + " current: " + current;
            if (_log.shouldLog(Log.ERROR))
                _log.error(msg);
            //sendStatusMessage(id, SessionStatusMessage.STATUS_INVALID);
            _runner.disconnectClient(msg);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating options - old: " + cfg + " new: " + message.getSessionConfig());
        if (!message.getSessionConfig().getDestination().equals(cfg.getDestination())) {
            _log.error("Dest mismatch");
            sendStatusMessage(id, SessionStatusMessage.STATUS_INVALID);
            _runner.stopRunning();
            return;
        }
        Hash dest = cfg.getDestination().calculateHash();
        cfg.getOptions().putAll(message.getSessionConfig().getOptions());
        ClientTunnelSettings settings = new ClientTunnelSettings(dest);
        Properties props = new Properties();
        props.putAll(cfg.getOptions());
        settings.readFromProperties(props);
        _context.tunnelManager().setInboundSettings(dest,
                                                    settings.getInboundSettings());
        _context.tunnelManager().setOutboundSettings(dest,
                                                     settings.getOutboundSettings());
        sendStatusMessage(id, SessionStatusMessage.STATUS_UPDATED);
    }
    
    private void sendStatusMessage(SessionId id, int status) {
        SessionStatusMessage msg = new SessionStatusMessage();
        msg.setSessionId(id);
        msg.setStatus(status);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the session status message", ime);
        }
    }

    /**
     * Divide router limit by 1.75 for overhead.
     * This could someday give a different answer to each client.
     * But it's not enforced anywhere.
     *
     * protected for unit test override
     */
    protected void handleGetBWLimits(GetBandwidthLimitsMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Got BW Limits request");
        int in = _context.bandwidthLimiter().getInboundKBytesPerSecond() * 4 / 7;
        int out = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 4 / 7;
        BandwidthLimitsMessage msg = new BandwidthLimitsMessage(in, out);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing bw limits msg", ime);
        }
    }

    /**
     *
     * @since 0.9.43
     */
    private void handleBlindingInfo(BlindingInfoMessage message) {
        if (_log.shouldInfo())
            _log.info("Got Blinding info");
        BlindData bd = message.getBlindData();
        if (bd == null) {
            // hash or hostname lookup? don't support for now
            if (_log.shouldWarn())
                _log.warn("Unsupported BlindingInfo type: " + message);
            return;
        }
        SigningPublicKey spk = bd.getUnblindedPubKey();
        if (spk == null) {
            // hash or hostname lookup? don't support for now
            if (_log.shouldWarn())
                _log.warn("Unsupported BlindingInfo type: " + message);
            return;
        }
        BlindData obd = _context.netDb().getBlindData(spk);
        if (obd == null) {
            _context.netDb().setBlindData(bd);
            if (_log.shouldWarn())
                _log.warn("New: " + bd);
        } else {
            // update if changed
            PrivateKey okey = obd.getAuthPrivKey();
            PrivateKey nkey = bd.getAuthPrivKey();
            String osec = obd.getSecret();
            String nsec = bd.getSecret();
            if ((nkey != null && !nkey.equals(okey)) ||
                (nsec != null && !nsec.equals(osec))) {
                // don't lose destination
                if (obd.getDestination() != null && bd.getDestination() == null) {
                    try {
                        bd.setDestination(obd.getDestination());
                    } catch (IllegalArgumentException iae) {
                        if (_log.shouldWarn())
                            _log.warn("Dest mismatch: " + obd + bd, iae);
                        return;
                    }
                }
                _context.netDb().setBlindData(bd);
                if (_log.shouldWarn())
                    _log.warn("Updated: " + bd);
            } else {
                long oexp = obd.getExpiration();
                long nexp = bd.getExpiration();
                if (nexp > oexp) {
                    obd.setExpiration(nexp);
                    // to force save at shutdown
                    _context.netDb().setBlindData(obd);
                    if (_log.shouldWarn())
                        _log.warn("Updated expiration: " + obd);
                } else {
                    if (_log.shouldWarn())
                        _log.warn("No change: " + obd);
                }
            }
        }
    }
}
