package net.i2p.router.networkdb.kademlia;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.MessageSelector;
import net.i2p.router.ProfileManager;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.Log;

/**
 * Send a netDb lookup to a floodfill peer - If it is found, great,
 * but if they reply back saying they dont know it, queue up a store of the
 * key to a random floodfill peer again (via FloodfillStoreJob)
 *
 */
class FloodfillVerifyStoreJob extends JobImpl {
    private final Log _log;
    private final Hash _key, _client;
    private volatile Hash _target;
    private final Hash _sentTo;
    private final FloodfillNetworkDatabaseFacade _facade;
    private long _expiration;
    private long _sendTime;
    private int _attempted;
    private final long _published;
    private final int _type;
    private final boolean _isRouterInfo;
    private final boolean _isLS2;
    private MessageWrapper.WrappedMessage _wrappedMessage;
    private final Set<Hash> _ignore;
    private final MaskedIPSet _ipSet;
    
    private static final int START_DELAY = 18*1000;
    private static final int START_DELAY_RAND = 9*1000;
    private static final int VERIFY_TIMEOUT = 20*1000;
    private static final int MAX_PEERS_TO_TRY = 4;
    private static final int IP_CLOSE_BYTES = 3;
    
    /**
     *  Delay a few seconds, then start the verify
     *  @param client generally the same as key, unless encrypted LS2; non-null
     *  @param published getDate() for RI or LS1, getPublished() for LS2
     *  @param sentTo who to give the credit or blame to, can be null
     *  @param toSkip don't query any of these peers, may be null
     *  @since 0.9.53 added toSkip param
     */
    public FloodfillVerifyStoreJob(RouterContext ctx, Hash key, Hash client, long published, int type,
                                   Hash sentTo, Set<Hash> toSkip, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        facade.verifyStarted(key);
        _key = key;
        _client = client;
        _published = published;
        _isRouterInfo = type == DatabaseEntry.KEY_TYPE_ROUTERINFO;
        _isLS2 = !_isRouterInfo && type != DatabaseEntry.KEY_TYPE_LEASESET;
        _type = type;
        _log = ctx.logManager().getLog(getClass());
        _sentTo = sentTo;
        _facade = facade;
        _ignore = new HashSet<Hash>(8);
        if (toSkip != null) {
            synchronized(toSkip) {
                _ignore.addAll(toSkip);
            }
        }
        if (sentTo != null) {
            _ipSet = new MaskedIPSet(ctx, sentTo, IP_CLOSE_BYTES);
            _ignore.add(_sentTo);
        } else {
            _ipSet = new MaskedIPSet(4);
        }
        // wait some time before trying to verify the store
        getTiming().setStartAfter(ctx.clock().now() + START_DELAY + ctx.random().nextInt(START_DELAY_RAND));
        getContext().statManager().createRateStat("netDb.floodfillVerifyOK", "How long a floodfill verify takes when it succeeds", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyFail", "How long a floodfill verify takes when it fails", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyTimeout", "How long a floodfill verify takes when it times out", "NetworkDatabase", new long[] { 60*60*1000 });
    }

    public String getName() { return "Verify netdb store"; }

    /**
     *  Query a random floodfill for the leaseset or routerinfo
     *  that we just stored to a (hopefully different) floodfill peer.
     *
     *  If it fails (after a timeout period), resend the data.
     *  If the queried data is older than what we stored, that counts as a fail.
     **/
    public void runJob() { 
        _target = pickTarget();
        if (_target == null) {
            _facade.verifyFinished(_key);
            return;
        }        

        final RouterContext ctx = getContext();
        boolean isInboundExploratory;
        TunnelInfo replyTunnelInfo;
        if (_isRouterInfo || ctx.keyRing().get(_key) != null ||
            _type == DatabaseEntry.KEY_TYPE_META_LS2) {
            replyTunnelInfo = ctx.tunnelManager().selectInboundExploratoryTunnel(_target);
            isInboundExploratory = true;
        } else {
            replyTunnelInfo = ctx.tunnelManager().selectInboundTunnel(_client, _target);
            isInboundExploratory = false;
        }
        if (replyTunnelInfo == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No inbound tunnels to get a reply from!");
            _facade.verifyFinished(_key);
            return;
        }
        DatabaseLookupMessage lookup = buildLookup(replyTunnelInfo);

        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        TunnelInfo outTunnel;
        if (_isRouterInfo || ctx.keyRing().get(_key) != null ||
            _type == DatabaseEntry.KEY_TYPE_META_LS2) {
            outTunnel = ctx.tunnelManager().selectOutboundExploratoryTunnel(_target);
        } else {
            outTunnel = ctx.tunnelManager().selectOutboundTunnel(_client, _target);
        }
        if (outTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to verify a store");
            _facade.verifyFinished(_key);
            return;
        }
        
        // garlic encrypt to hide contents from the OBEP
        RouterInfo peer = _facade.lookupRouterInfoLocally(_target);
        if (peer == null) {
             if (_log.shouldLog(Log.WARN))
                 _log.warn("(JobId: " + getJobId()
                           + "; dbid: " + _facade._dbid
                           + ") Fail looking up RI locally for target " + _target);
            _facade.verifyFinished(_key);
            return;
        }
        EncType type = peer.getIdentity().getPublicKey().getType();
        boolean supportsElGamal = true;
        boolean supportsRatchet = false;
        if (DatabaseLookupMessage.supportsEncryptedReplies(peer)) {
            // register the session with the right SKM
            MessageWrapper.OneTimeSession sess;
            if (isInboundExploratory) {
                EncType ourType = ctx.keyManager().getPublicKey().getType();
                supportsRatchet = ourType == EncType.ECIES_X25519 &&
                                  type == EncType.ECIES_X25519;
                supportsElGamal = ourType == EncType.ELGAMAL_2048 &&
                                  type == EncType.ELGAMAL_2048;
                if (supportsElGamal || supportsRatchet) {
                    sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), VERIFY_TIMEOUT, !supportsRatchet);
                } else {
                    // We don't have a compatible way to get a reply,
                    // skip it for now.
                     if (_log.shouldWarn())
                         _log.warn("Skipping store verify for incompatible router " + peer);
                    _facade.verifyFinished(_key);
                    return;
                }
            } else {
                LeaseSetKeys lsk = ctx.keyManager().getKeys(_client);
                // an ElG router supports ratchet replies
                supportsRatchet = lsk != null &&
                                  lsk.isSupported(EncType.ECIES_X25519) &&
                                  DatabaseLookupMessage.supportsRatchetReplies(peer);
                // but an ECIES router does not supports ElGamal requests
                supportsElGamal = lsk != null &&
                                  lsk.isSupported(EncType.ELGAMAL_2048) &&
                                  type == EncType.ELGAMAL_2048;
                if (supportsElGamal || supportsRatchet) {
                    // garlic encrypt
                    sess = MessageWrapper.generateSession(ctx, _client, VERIFY_TIMEOUT, !supportsRatchet);
                    if (sess == null) {
                         if (_log.shouldLog(Log.WARN))
                             _log.warn("No SKM to reply to");
                        _facade.verifyFinished(_key);
                        return;
                    }
                } else {
                    // We don't have a compatible way to get a reply,
                    // skip it for now.
                     if (_log.shouldWarn())
                         _log.warn("Skipping store verify to " + _target + " for ECIES- or ElG-only client " + _client.toBase32());
                    _facade.verifyFinished(_key);
                    return;
                }
            }
            if (sess.tag != null) {
                if (_log.shouldInfo())
                    _log.info(getJobId() + ": Requesting AES reply from " + _target + " with: " + sess.key + ' ' + sess.tag);
                lookup.setReplySession(sess.key, sess.tag);
            } else {
                if (_log.shouldInfo())
                    _log.info(getJobId() + ": Requesting AEAD reply from " + _target + " with: " + sess.key + ' ' + sess.rtag);
                lookup.setReplySession(sess.key, sess.rtag);
            }
        }
        Hash fromKey;
        I2NPMessage sent;
        if (supportsElGamal) {
            if (_isRouterInfo)
                fromKey = null;
            else
                fromKey = _client;
            _wrappedMessage = MessageWrapper.wrap(ctx, lookup, fromKey, peer);
            if (_wrappedMessage == null) {
                 if (_log.shouldLog(Log.WARN))
                    _log.warn("Fail Garlic encrypting");
                _facade.verifyFinished(_key);
                return;
            }
            sent = _wrappedMessage.getMessage();
        } else {
            // force full ElG for ECIES fromkey
            // or forces ECIES for ECIES peer
            sent = MessageWrapper.wrap(ctx, lookup, peer);
            if (sent == null) {
                 if (_log.shouldLog(Log.WARN))
                    _log.warn("Fail Garlic encrypting");
                _facade.verifyFinished(_key);
                return;
            }
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("[JobId: " + getJobId() + "; dbid: " + _facade._dbid
                      + "]: Starting verify (stored " + _key + " to " + _sentTo + "), asking " + _target);
        _sendTime = ctx.clock().now();
        _expiration = _sendTime + VERIFY_TIMEOUT;
        ctx.messageRegistry().registerPending(new VerifyReplySelector(),
                                                       new VerifyReplyJob(getContext()),
                                                       new VerifyTimeoutJob(getContext()));
        ctx.tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), _target);
        _attempted++;
    }
    
    /**
     *  Pick a responsive floodfill close to the key, but not the one we sent to
     */
    private Hash pickTarget() {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(_key);
        FloodfillPeerSelector sel = (FloodfillPeerSelector)_facade.getPeerSelector();
        Certificate keyCert = null;
        if (!_isRouterInfo) {
            Destination dest = _facade.lookupDestinationLocally(_key);
            if (dest != null) {
                Certificate cert = dest.getCertificate();
                if (cert.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY)
                    keyCert = cert;
            }
        }
        if (keyCert != null) {
            while (true) {
                List<Hash> peers = sel.selectFloodfillParticipants(rkey, 1, _ignore, _facade.getKBuckets());
                if (peers.isEmpty())
                    break;
                Hash peer = peers.get(0);
                RouterInfo ri = _facade.lookupRouterInfoLocally(peer);
                //if (ri != null && StoreJob.supportsCert(ri, keyCert)) {
                if (ri != null && StoreJob.shouldStoreTo(ri) &&
                    //(!_isLS2 || (StoreJob.shouldStoreLS2To(ri) &&
                                 (_type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || StoreJob.shouldStoreEncLS2To(ri))) {
                    Set<String> peerIPs = new MaskedIPSet(getContext(), ri, IP_CLOSE_BYTES);
                    if (!_ipSet.containsAny(peerIPs)) {
                        _ipSet.addAll(peerIPs);
                        return peer;
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info(getJobId() + ": Skipping verify w/ router too close to the store " + peer);
                    }
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping verify w/ router that is too old " + peer);
                }
                _ignore.add(peer);
            }
        } else {
            List<Hash> peers = sel.selectFloodfillParticipants(rkey, 1, _ignore, _facade.getKBuckets());
            if (!peers.isEmpty())
                return peers.get(0);
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn(getJobId() + ": No other peers to verify floodfill with, using the one we sent to");
        return _sentTo;
    }
    
    /** @return non-null */
    private DatabaseLookupMessage buildLookup(TunnelInfo replyTunnelInfo) {
        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        DatabaseLookupMessage m = new DatabaseLookupMessage(getContext(), true);
        m.setMessageExpiration(getContext().clock().now() + VERIFY_TIMEOUT);
        m.setReplyTunnel(replyTunnelInfo.getReceiveTunnelId(0));
        m.setFrom(replyTunnelInfo.getPeer(0));
        m.setSearchKey(_key);
        m.setSearchType(_isRouterInfo ? DatabaseLookupMessage.Type.RI : DatabaseLookupMessage.Type.LS);
        return m;
    }
    
    private class VerifyReplySelector implements MessageSelector {
        public boolean continueMatching() { 
            return false; // only want one match
        }
        
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            int type = message.getType();
            if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
                return _key.equals(dsm.getKey());
            } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
                boolean rv = _key.equals(dsrm.getSearchKey());
                if (rv) {
                    if (_log.shouldInfo())
                        _log.info("[JobId: " + getJobId() + "; dbid: " + _facade._dbid
                                  + "DSRM key match successful.");
                } else {
                    if (_log.shouldWarn())
                        _log.warn("[JobId: " + getJobId() + "; dbid: " + _facade._dbid
                                  + "]: DSRM key mismatch for key " + _key
                                  + " with DSRM: " + message);
                }
                return rv;
            }
            return false;
        }
    }
    
    private class VerifyReplyJob extends JobImpl implements ReplyJob {
        private I2NPMessage _message;

        public VerifyReplyJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "Handle floodfill verification reply"; }

        public void runJob() {
            final RouterContext ctx = getContext();
            long delay = ctx.clock().now() - _sendTime;
            if (_wrappedMessage != null)
                _wrappedMessage.acked();
            _facade.verifyFinished(_key);
            final ProfileManager pm = ctx.profileManager();
            final int type = _message.getType();
            if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                // Verify it's as recent as the one we sent
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)_message;
                DatabaseEntry entry = dsm.getEntry();
                if (!entry.verifySignature()) {
                    if (_log.shouldWarn())
                        _log.warn(getJobId() + ": Sent bad data for verify: " + _target);
                    pm.dbLookupFailed(_target);
                    ctx.banlist().banlistRouterHard(_target, "Sent bad netdb data");
                    ctx.statManager().addRateData("netDb.floodfillVerifyFail", delay);
                    resend();
                    return;
                }
                long newDate;
                boolean success;
                if (_isLS2 &&
                    entry.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO &&
                    entry.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                    LeaseSet2 ls2 = (LeaseSet2) entry;
                    success = ls2.getPublished() >= _published;
                } else {
                    success = entry.getDate() >= _published;
                }
                if (success) {
                    // store ok, w00t!
                    pm.dbLookupSuccessful(_target, delay);
                    if (_sentTo != null)
                        pm.dbStoreSuccessful(_sentTo);
                    ctx.statManager().addRateData("netDb.floodfillVerifyOK", delay);
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Verify success for " + _key);
                    if (_isRouterInfo)
                        _facade.routerInfoPublishSuccessful();
                    return;
                }
                if (_log.shouldWarn()) {
                    _log.warn(getJobId() + ": Verify failed (older) for " + _key);
                    if (_log.shouldInfo())
                        _log.info(getJobId() + ": Rcvd older data: " + dsm.getEntry());
                }
            } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage) _message;
                // assume 0 old, all new, 0 invalid, 0 dup
                pm.dbLookupReply(_target,  0,
                                dsrm.getNumReplies(), 0, 0, delay);
                // ToDo: Clarify the following log message.
                // This message is phrased in a manner that draws attention, and indicates
                // the possibility of a problem that may need follow-up. But examination
                // of the code did not provide insight as to what is being verified,
                // and what is failing. This message will be displayed unconditionally
                // every time a DSRM is handled here.
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": DSRM verify failed (dbid: "
                              + _facade._dbid + ") for " + _key);
                // only for RI... LS too dangerous?
                if (_isRouterInfo) {
                    if (_facade.isClientDb())
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("[Jobid: " + getJobId() + "; dbid: " + _facade._dbid
                                      + "Warning! Client is starting a SingleLookupJob (DIRECT?) for RouterInfo");
                    ctx.jobQueue().addJob(new SingleLookupJob(ctx, dsrm));
                }
            }
            // store failed, boo, hiss!
            // blame the sent-to peer, but not the verify peer
            if (_sentTo != null)
                pm.dbStoreFailed(_sentTo);
            // Blame the verify peer also.
            // We must use dbLookupFailed() or dbStoreFailed(), neither of which is exactly correct,
            // but we have to use one of them to affect the FloodfillPeerSelector ordering.
            // If we don't do this we get stuck using the same verify peer every time even
            // though it is the real problem.
            if (!_target.equals(_sentTo))
                pm.dbLookupFailed(_target);
            ctx.statManager().addRateData("netDb.floodfillVerifyFail", delay);
            resend();
        }        

        public void setMessage(I2NPMessage message) { _message = message; }
    }
    
    /**
     *  the netDb store failed to verify, so resend it to a random floodfill peer
     *  Fixme - since we now store closest-to-the-key, this is likely to store to the
     *  very same ff as last time, until the stats get bad enough to switch.
     *  Therefore, pass the failed ff through as a don't-store-to.
     *  Let's also add the one we just tried to verify with, as they could be a pair of no-flooders.
     *  So at least we'll try THREE ffs round-robin if things continue to fail...
     */
    private void resend() {
        // It's safe to check the default netDb first, but if the lookup is for
        // a client, nearly all RI is expected to be found in the FF netDb.
        DatabaseEntry ds = getContext().netDbSegmentor().lookupLocally(_key, _facade._dbid);
        if ((ds == null) && _facade.isClientDb() && _isRouterInfo)
            // It's safe to check the floodfill netDb for RI
            ds = getContext().netDbSegmentor().lookupLocally(_key, FloodfillNetworkDatabaseSegmentor.MAIN_DBID);
        if (ds != null) {
            // By the time we get here, a minute or more after the store started, 
            // we may have already started a new store
            // (probably, for LS, and we don't verify by default for RI)
            long newDate;
            if (_isLS2 &&
                ds.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO &&
                ds.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet2 ls2 = (LeaseSet2) ds;
                newDate = ls2.getPublished();
            } else {
                newDate = ds.getDate();
            }
            if (newDate > _published) {
                if (_log.shouldInfo())
                    _log.info(getJobId() + ": Verify failed, but new store already happened for: " + _key);
                return;
            }
            Set<Hash> toSkip = new HashSet<Hash>(8);
            if (_sentTo != null)
                toSkip.add(_sentTo);
            toSkip.add(_target);
            // pass over all the ignores for the next attempt
            // unless we've had a crazy number of attempts, then start over
            if (_ignore.size() < 20)
                toSkip.addAll(_ignore);
            if (_log.shouldWarn())
                _log.warn(getJobId() + ": Verify failed, starting new store for: " + _key);
            _facade.sendStore(_key, ds, null, null, FloodfillNetworkDatabaseFacade.PUBLISH_TIMEOUT, toSkip);
        }
    }
    
    private class VerifyTimeoutJob extends JobImpl {
        public VerifyTimeoutJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Floodfill verification timeout"; }
        public void runJob() { 
            if (_wrappedMessage != null)
                _wrappedMessage.fail();
            // Only blame the verify peer
            getContext().profileManager().dbLookupFailed(_target);
            //if (_sentTo != null)
            //    getContext().profileManager().dbStoreFailed(_sentTo);
            getContext().statManager().addRateData("netDb.floodfillVerifyTimeout", getContext().clock().now() - _sendTime);
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Verify timed out for: " + _key);
            if (_attempted < MAX_PEERS_TO_TRY) {
                // Don't resend, simply rerun FVSJ.this inline and
                // chose somebody besides _target for verification
                _ignore.add(_target);
                FloodfillVerifyStoreJob.this.runJob();
            } else {
                _facade.verifyFinished(_key);
                resend(); 
            }
        }
    }
}
