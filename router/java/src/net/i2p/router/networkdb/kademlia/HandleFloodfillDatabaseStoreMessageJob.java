package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.TunnelId;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.OutboundCache;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Receive DatabaseStoreMessage data and store it in the local net db
 *
 */
class HandleFloodfillDatabaseStoreMessageJob extends JobImpl {
    private final Log _log;
    private final DatabaseStoreMessage _message;
    private final RouterIdentity _from;
    private Hash _fromHash;
    private final FloodfillNetworkDatabaseFacade _facade;
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = OutNetMessage.PRIORITY_NETDB_REPLY;
    // must be lower than LIMIT_ROUTERS in StartExplorersJob
    // because exploration does not register a reply job
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 1000 : 4000;
    private final long _msgIDBloomXor;

    /**
     * @param receivedMessage must never have reply token set if it came down a tunnel
     */
    public HandleFloodfillDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage,
                                                  RouterIdentity from, Hash fromHash,
                                                  FloodfillNetworkDatabaseFacade facade, long msgIDBloomXor) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
        _facade = facade;
        _msgIDBloomXor = msgIDBloomXor;
    }
    
    public void runJob() {
        long recvBegin = System.currentTimeMillis();
        
        String invalidMessage = null;
        // set if invalid store but not his fault
        boolean dontBlamePeer = false;
        boolean wasNew = false;
        RouterInfo prevNetDb = null;
        Hash key = _message.getKey();
        DatabaseEntry entry = _message.getEntry();
        int type = entry.getType();
        if (DatabaseEntry.isLeaseSet(type)) {
            getContext().statManager().addRateData("netDb.storeLeaseSetHandled", 1);
            if (_log.shouldDebug())
                _log.debug("(dbid: " + _facade
                           + ") Starting handling of dbStore of leaseset " + _message);

            try {
                // With the introduction of segmented netDb, the handling of
                // local LeaseSets has changed substantially, based on the role
                // being assumed.
                // Role #1) The 'floodfill' netDb when the router is a FloodFill
                //          The LS will be checked to make sure it arrived directly,
                //          and handled as a normal LS.
                // Role #2) The 'floodfill' netDb when the router is *NOT* an I2P
                //          network Floodfill.
                //          In this case, the 'floodfill' netDb primarily stores RouterInfos.
                //          However, there are a number of normal cases where it might contain
                //          one or more LeaseSets:
                //            1. We used to be a floodfill but aren't anymore
                //            2. We performed a lookup without an active session locally(It won't be RAP)
                // Role #3) Client netDb will only receive LeaseSets from their client
                //          tunnels, and clients will only publish their LeaseSet out
                //          their client tunnel.
                //          In this role, the only LeaseSet store that should be rejected
                //          is the subDb's client's own LeaseSet.
                //
                //          Currently, the 'floodfill' netDb will be excluded
                //          from directly receiving a client LeaseSet, due to the
                //          way the selection of FloodFill routers are selected
                //          when flooding a LS.
                //          But even if the host router does not directly receive the
                //          LeaseSets of the clients it hosts, those LeaseSets will
                //          usually be flooded back to it.

                LeaseSet ls = (LeaseSet) entry;
                // If this was received as a response to a query,
                // FloodOnlyLookupMatchJob called setReceivedAsReply(),
                // and we are seeing this only as a duplicate,
                // so we don't set the receivedAsPublished() flag.
                // Otherwise, mark it as something we received unsolicited, so we'll answer queries 
                // for it.  This flag must NOT get set on entries that we 
                // receive in response to our own lookups.
                // See ../HDLMJ for more info
                if (!ls.getReceivedAsReply())
                    ls.setReceivedAsPublished();
                if (_facade.isClientDb()) {
                    // This is where we deal with what happens if a client subDB tries to store
                    // a leaseSet which it is the owner/publisher of.
                    // Look up a ls hash in the netDbSegmentor, and compare it to the _facade that we have.
                    // If they are equal, reject the store.
                    if (getContext().netDbSegmentor().clientNetDB(ls.getHash()).equals(_facade)) {
                        getContext().statManager().addRateData("netDb.storeLocalLeaseSetToLocalClient", 1, 0);
                        dontBlamePeer = true;
                        throw new IllegalArgumentException("(dbid: " + _facade
                            + ") Peer attempted to store local leaseSet: "
                            + key.toBase32() + " to client subDB " + _facade + "which is it's own publisher");
                    }
                }
                //boolean oldrar = ls.getReceivedAsReply();
                //boolean oldrap = ls.getReceivedAsPublished();

                //boolean rap = ls.getReceivedAsPublished();
                //if (_log.shouldLog(Log.INFO))
                //    _log.info("oldrap? " + oldrap + " oldrar? " + oldrar + " newrap? " + rap);
                LeaseSet match = _facade.store(key, ls);
                if (match == null) {
                    wasNew = true;
                } else if (match.getEarliestLeaseDate() < ls.getEarliestLeaseDate()) {
                    wasNew = true;
                    // If it is in our keyspace and we are talking to it
                    //if (match.getReceivedAsPublished())
                    //    ls.setReceivedAsPublished(true);
                } else if (type != DatabaseEntry.KEY_TYPE_LEASESET &&
                           match.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    LeaseSet2 match2 = (LeaseSet2) match;
                    if (match2.getPublished() < ls2.getPublished()) {
                        wasNew = true;
                        //if (match.getReceivedAsPublished())
                        //    ls.setReceivedAsPublished(true);
                    } else {
                        wasNew = false;
                    }
                } else {
                    wasNew = false;
                    // The FloodOnlyLookupSelector goes away after the first good reply
                    // So on the second reply, FloodOnlyMatchJob is not called to set ReceivedAsReply.
                    // So then we think it's an unsolicited store.
                    // So we should skip this.
                    // If the 2nd reply is newer than the first, ReceivedAsPublished will be set incorrectly,
                    // that will hopefully be rare.
                    // A more elaborate solution would be a List of recent ReceivedAsReply LeaseSets, with receive time ?
                    // A real unsolicited store is likely to be new - hopefully...
                    //if (!ls.getReceivedAsReply())
                    //    match.setReceivedAsPublished(true);
                }
            } catch (UnsupportedCryptoException uce) {
               if (_log.shouldError())
                   _log.error("UCE: ", uce);
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            RouterInfo ri = (RouterInfo) entry;
            if (_log.shouldDebug())
                _log.debug("(dbid: " + _facade
                           + ") Starting handling of dbStore of RI " + _message);
            getContext().statManager().addRateData("netDb.storeRouterInfoHandled", 1);
            if (_fromHash == null && _from != null)
                _fromHash = _from.getHash();

            boolean isUs = getContext().routerHash().equals(key);
            if (!key.equals(_fromHash) && !isUs) {
                if (_message.getReceivedAsReply()) {
                    ri.setReceivedAsReply();
                    if (_message.getReplyToken() > 0)
                        ri.setReceivedAsPublished();
                } else {
                    ri.setReceivedAsPublished();
                }
            }
            if (_log.shouldInfo()) {
                String req = ((_message.getReplyToken() > 0) ? " reply req." : "") +
                             ((_fromHash == null && ri.getReceivedAsPublished()) ? " unsolicited" : "");
                if (_fromHash == null)
                    _log.info("(dbid: " + _facade
                              + ") Handling dbStore (_fromHash == NULL) of router "
                              + key.toBase64() + " published "
                              + DataHelper.formatTime(ri.getPublished())
                              + " (ReceivedAsReply["
                              + ri.getReceivedAsReply()
                              + "], ReceivedAsPublished["
                              + ri.getReceivedAsPublished()
                              + "])" + req);
                else if (_fromHash.equals(key))
                    _log.info("(dbid: " + _facade
                              + ") Handling dbStore (_fromHash equals key) of router "
                              + key.toBase64() + " published "
                              + DataHelper.formatTime(ri.getPublished())
                              + " from that router "
                              + " (ReceivedAsReply["
                              + ri.getReceivedAsReply()
                              + "], ReceivedAsPublished["
                              + ri.getReceivedAsPublished()
                              + "])" + req);
                else
                    _log.info("(dbid: " + _facade
                              + ") Handling dbStore of router "
                              + key.toBase64() + " published "
                              + DataHelper.formatTime(ri.getPublished())
                              + " from: " + _fromHash.toBase64()
                              + " (ReceivedAsReply["
                              + ri.getReceivedAsReply()
                              + "], ReceivedAsPublished["
                              + ri.getReceivedAsPublished()
                              + "])" + req);
            }
            try {
                // Never store our RouterInfo received from somebody else.
                // This generally happens from a FloodfillVerifyStoreJob.
                // If it is valid, it shouldn't be newer than what we have - unless
                // somebody has our keys... 
                if (isUs) {
                    //getContext().statManager().addRateData("netDb.storeLocalRouterInfoAttempt", 1, 0);
                    // This is initiated by PeerTestJob from another peer
                    // throw rather than return, so that we send the ack below (prevent easy attack)
                    dontBlamePeer = true;
                    throw new IllegalArgumentException("Peer attempted to store our RouterInfo");
                }
                // If we're in the client netDb context, log a warning since this is not expected.
                // This is probably impossible but log it if we ever see it so it can be investigated.
                if (_facade.isClientDb() && _log.shouldWarn())
                    _log.warn("[dbid: " + _facade
                        + "]:  Handling RI dbStore in client netDb context of router " + key.toBase64());
                boolean shouldStore = true;
                if (ri.getReceivedAsPublished()) {
                    // these are often just dup stores from concurrent lookups
                    prevNetDb = (RouterInfo) _facade.lookupLocallyWithoutValidation(key);
                    if (prevNetDb == null) {
                        // actually new
                        int count = _facade.getDataStore().size();
                        if (count > LIMIT_ROUTERS) {
                            String caps = ri.getCapabilities();
                            boolean isU = caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
                            boolean isFF = caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
                            boolean notFrom = !key.equals(_fromHash);
                            if (_facade.floodfillEnabled()) {
                                // determine if they're "close enough"
                                // we will still ack and flood by setting wasNew = true even if we don't store locally
                                // so even just-reseeded new routers will get stored to the right place
                                RouterKeyGenerator gen = getContext().routerKeyGenerator();
                                byte[] rkey = gen.getRoutingKey(key).getData();
                                byte[] ourRKey = getContext().routerHash().getData();
                                int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                                ((rkey[1] ^ ourRKey[1]) & 0xff);
                                // they have to be within 1/256 of the keyspace
                                if (distance >= 256) {
                                    long until = gen.getTimeTillMidnight();
                                    if (until > FloodfillNetworkDatabaseFacade.NEXT_RKEY_RI_ADVANCE_TIME) {
                                        // appx. 90% max drop rate so even just-reseeded new routers will make it eventually
                                        int pdrop = Math.min(110, (128 * count / LIMIT_ROUTERS) - 128);
                                        if (isU)
                                            pdrop *= 3;
                                        if (isFF)
                                            pdrop *= 3;
                                        if (notFrom)
                                            pdrop *= 3;
                                        if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                            if (_log.shouldWarn())
                                                _log.warn("(dbid: " + _facade
                                                          + ") Dropping new unsolicited dbStore of " + ri.getCapabilities()
                                                          + " router " + key.toBase64() + " with distance " + distance
                                                          + " drop probability " + (pdrop * 100 / 128));
                                            shouldStore = false;
                                            // still flood if requested
                                            if (_message.getReplyToken() > 0)
                                                wasNew = true;
                                        }
                                    } else {
                                        // almost midnight, recheck with tomorrow's keys
                                        rkey = gen.getNextRoutingKey(key).getData();
                                        ourRKey = gen.getNextRoutingKey(getContext().routerHash()).getData();
                                        distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                                    ((rkey[1] ^ ourRKey[1]) & 0xff);
                                        if (distance >= 256) {
                                            int pdrop = Math.min(110, (128 * count / LIMIT_ROUTERS) - 128);
                                            if (isU)
                                                pdrop *= 3;
                                            if (isFF)
                                                pdrop *= 3;
                                            if (notFrom)
                                                pdrop *= 3;
                                            if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                                if (_log.shouldWarn())
                                                    _log.warn("(dbid: " + _facade
                                                              + ") Dropping new unsolicited dbStore of router " + key.toBase64()
                                                              + " with distance " + distance);
                                                shouldStore = false;
                                                // still flood if requested
                                                if (_message.getReplyToken() > 0)
                                                    wasNew = true;
                                            }
                                        }
                                    }
                                }
                                if (shouldStore && _log.shouldDebug())
                                    _log.debug("(dbid: " + _facade
                                               + ") Allowing new unsolicited dbStore of router " + key.toBase64()
                                               + " with distance " + distance);
                            } else {
                                // non-ff
                                // up to 100% drop rate
                                int pdrop = (128 * count / LIMIT_ROUTERS) - 128;
                                if (isU)
                                    pdrop *= 3;
                                if (isFF)
                                    pdrop *= 3;
                                if (notFrom)
                                    pdrop *= 3;
                                if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                    if (_log.shouldWarn())
                                        _log.warn("(dbid: " + _facade
                                                  + ") Dropping new unsolicited dbStore of router " + key.toBase64()
                                                  + " drop probability " + (pdrop * 100 / 128));
                                    shouldStore = false;
                                    // don't bother checking ban/blocklists.
                                    //wasNew = true;
                                }
                            }
                        }
                        if (shouldStore && _log.shouldDebug())
                            _log.debug("(dbid: " + _facade
                                      + ") Handling new unsolicited dbStore of router " + key.toBase64());
                    } else if (prevNetDb.getPublished() >= ri.getPublished()) {
                        shouldStore = false;
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("(dbid: " + _facade
                                      + ") Newer RouterInfo encountered in dbStore Message (for router "
                                      + key.toBase64()
                                      + ") with a pulished date ("
                                      + ri.getPublished()
                                      + ") newer than the one for the RI already stored in our database ("
                                      + prevNetDb.getPublished() + ")");
                            // new RouterIdentity prevIdentity = prevNetDb.getIdentity();
                            // new RouterIdentity newIdentity = ri.getIdentity();
                            if (!ri.getIdentity().getPublicKey().equals(prevNetDb.getIdentity().getPublicKey()))
                                _log.info("(dbid: " + _facade
                                          + ") Warning! The old ("
                                          + prevNetDb.getIdentity().getPublicKey()
                                          + ") and new ("
                                          + ri.getIdentity().getPublicKey()
                                          + ") public keys do not match!");
                            if (!ri.getIdentity().getSigningPublicKey().equals(prevNetDb.getIdentity().getSigningPublicKey()))
                                _log.info("(dbid: " + _facade + ") Warning! The old ("
                                          + prevNetDb.getIdentity().getSigningPublicKey()
                                          + ") and new ("
                                          + ri.getIdentity().getSigningPublicKey()
                                          + ") signing public keys do not match!");
                        }
                    }
                }
                if (shouldStore) {
                    if (_log.shouldDebug())
                        _log.debug("[dbid: " + _facade
                                   + "]: Storing RI with the context netDb " + key.toBase64());
                    prevNetDb = _facade.store(key, ri);
                    if (_facade.isClientDb() && _log.shouldWarn())
                        _log.warn("[dbid: " + _facade
                                  + "] Storing RI to client netDb (this is rare, should have been handled by IBMD) "
                                  + key.toBase64());
                    wasNew = ((null == prevNetDb) || (prevNetDb.getPublished() < ri.getPublished()));
                }
                // Check new routerinfo address against blocklist
                if (wasNew) {
                    // TODO should we not flood temporarily banned routers either?
                    boolean forever = getContext().banlist().isBanlistedForever(key);
                    if (forever) {
                        wasNew = false; // don't flood
                        shouldStore = false; // don't call heardAbout()
                    }
                    if (prevNetDb == null) {
                        if (!forever &&
                            getContext().blocklist().isBlocklisted(ri)) {
                            if (_log.shouldWarn())
                                _log.warn("(dbid: " + _facade + ") Blocklisting new peer " + key + ' ' + ri);
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    } else if (!forever) {
                        Collection<RouterAddress> oldAddr = prevNetDb.getAddresses();
                        Collection<RouterAddress> newAddr = ri.getAddresses();
                        if ((!newAddr.equals(oldAddr)) &&
                            getContext().blocklist().isBlocklisted(ri)) {
                            if (_log.shouldWarn())
                                _log.warn("(dbid: " + _facade + ") New address received, Blocklisting old peer " + key + ' ' + ri);
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    }
                }
                if (shouldStore && ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) >= 0)
                    getContext().profileManager().heardAbout(key);
            } catch (UnsupportedCryptoException uce) {
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("(dbid: " + _facade
                           + ") Invalid DatabaseStoreMessage data type - " + type
                           + ": " + _message);
            // don't ack or flood
            return;
        }
        
        long recvEnd = System.currentTimeMillis();
        getContext().statManager().addRateData("netDb.storeRecvTime", recvEnd-recvBegin);
        
        // ack even if invalid
        // in particular, ack our own RI (from PeerTestJob)
        // TODO any cases where we shouldn't?
        if (_message.getReplyToken() > 0)
            sendAck(key);
        long ackEnd = System.currentTimeMillis();
        
        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null) {
            if (invalidMessage == null || dontBlamePeer) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", ackEnd-recvEnd);
            } else {
                // Should we record in the profile?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("(dbid: " + _facade
                              + ") Peer " + _fromHash.toBase64() + " sent bad data key: " + key.toBase64() + " replyreq? " + (_message.getReplyToken() > 0) + ": " + invalidMessage);
            }
        } else if (invalidMessage != null && !dontBlamePeer) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("(dbid: " + _facade + ") Unknown peer sent bad data key: " + key.toBase64() + " replyreq? " + (_message.getReplyToken() > 0) + ": " + invalidMessage);
        }

        // flood it
        if (invalidMessage == null &&
            _facade.floodfillEnabled() &&
            _message.getReplyToken() > 0) {
            if (wasNew) {
                // DOS prevention
                // Note this does not throttle the ack above
                if (_facade.shouldThrottleFlood(key)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("(dbid: " + _facade + "Too many recent stores, not flooding key: " + key);
                    getContext().statManager().addRateData("netDb.floodThrottled", 1);
                    return;
                }
                long floodBegin = System.currentTimeMillis();
                _facade.flood(entry);
                // ERR: see comment in HandleDatabaseLookupMessageJob regarding hidden mode
                //else if (!_message.getRouterInfo().isHidden())
                long floodEnd = System.currentTimeMillis();
                getContext().statManager().addRateData("netDb.storeFloodNew", floodEnd-floodBegin, 60*1000);
            } else {
                // don't flood it *again*
                getContext().statManager().addRateData("netDb.storeFloodOld", 1);
            }
        }
    }
    
    private void sendAck(Hash storedKey) {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(getContext());
        msg.setMessageId(_message.getReplyToken());
        // Randomize for a little protection against clock-skew fingerprinting.
        // But the "arrival" isn't used for anything, right?
        // TODO we have no session to garlic wrap this with, needs new message
        msg.setArrival(getContext().clock().now() - getContext().random().nextInt(3*1000));
        // may be null
        TunnelId replyTunnel = _message.getReplyTunnel();
        // A store of our own RI, only if we are not FF
        DatabaseStoreMessage msg2;
        if (_facade.floodfillEnabled() ||
            storedKey.equals(getContext().routerHash())) {
            // don't send our RI if the store was our RI (from PeerTestJob)
            msg2 = null;
        } else {
            // we aren't ff, send a go-away message
            msg2 = new DatabaseStoreMessage(getContext());
            RouterInfo me = getContext().router().getRouterInfo();
            msg2.setEntry(me);
            if (_log.shouldWarn())
                _log.warn("(dbid: " + _facade
                          + ") Got a store w/ reply token, but we aren't ff: from: " + _from
                          + " fromHash: " + _fromHash + " msg: " + _message, new Exception());
        }
        Hash toPeer = _message.getReplyGateway();
        boolean toUs = getContext().routerHash().equals(toPeer);
        // to reduce connection congestion, send directly if connected already,
        // else through an exploratory tunnel.
        if (toUs && replyTunnel != null) {
            // if we are the gateway, act as if we received it
            TunnelGatewayMessage tgm = new TunnelGatewayMessage(getContext());
            tgm.setMessage(msg);
            tgm.setTunnelId(replyTunnel);
            tgm.setMessageExpiration(msg.getMessageExpiration());
            getContext().tunnelDispatcher().dispatch(tgm);
            if (msg2 != null) {
                TunnelGatewayMessage tgm2 = new TunnelGatewayMessage(getContext());
                tgm2.setMessage(msg2);
                tgm2.setTunnelId(replyTunnel);
                tgm2.setMessageExpiration(msg.getMessageExpiration());
                getContext().tunnelDispatcher().dispatch(tgm2);
            }
            return;
        }
        if (toUs) {
            if (_facade.isClientDb()) {
                _log.error("Error! SendMessageDirectJob (toUs) attempted in Client netDb ("
                           + _facade + ")! Message: " + msg);
                return;
            }
            Job send = new SendMessageDirectJob(getContext(), msg, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), msg2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
                send2.runJob();
            }
            return;
        }
        DatabaseEntry entry = _message.getEntry();
        int type = entry.getType();
        // only send direct for RI replies or non-tunnel
        boolean isEstab = (type == DatabaseEntry.KEY_TYPE_ROUTERINFO || replyTunnel == null) &&
                          getContext().commSystem().isEstablished(toPeer);
/*
        if (!isEstab && replyTunnel != null) {
            DatabaseEntry entry = _message.getEntry();
            int type = entry.getType();
            if (type == DatabaseEntry.KEY_TYPE_LEASESET || type == DatabaseEntry.KEY_TYPE_LS2) {
                // As of 0.9.42,
                // if reply GW and tunnel are in the LS, we can pick a different one from the LS,
                // so look for one that's connected to reduce connections
                LeaseSet ls = (LeaseSet) entry;
                int count = ls.getLeaseCount();
                if (count > 1) {
                    boolean found = false;
                    for (int i = 0; i < count; i++) {
                        Lease lease = ls.getLease(i);
                        if (lease.getGateway().equals(toPeer) && lease.getTunnelId().equals(replyTunnel)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        //_log.warn("Looking for alternate to " + toPeer + " reply gw in LS with " + count + " leases");
                        for (int i = 0; i < count; i++) {
                            Lease lease = ls.getLease(i);
                            Hash gw = lease.getGateway();
                            if (gw.equals(toPeer))
                                continue;
                            if (lease.isExpired())
                                continue;
                            if (getContext().commSystem().isEstablished(gw)) {
                                // switch to use this lease instead
                                toPeer = gw;
                                replyTunnel = lease.getTunnelId();
                                isEstab = true;
                                break;
                            }
                        }
                        if (_log.shouldWarn()) {
                            if (isEstab)
                                _log.warn("(dbid: " + _facade
                                          + ") Switched to alt connected peer " + toPeer
                                          + " in LS with " + count + " leases");
                            else
                                _log.warn("(dbid: " + _facade + ") Alt connected peer not found in LS with " + count + " leases");
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("(dbid: " + _facade
                                      + ") Reply gw " + toPeer + ' ' + replyTunnel
                                      + " not found in LS with " + count + " leases: " + ls);
                    }
                }
            }
        }
*/
        if (isEstab && !_facade.isClientDb()) {
            I2NPMessage out1 = msg;
            I2NPMessage out2 = msg2;
            if (replyTunnel != null) {
                // wrap reply in a TGM
                TunnelGatewayMessage tgm = new TunnelGatewayMessage(getContext());
                tgm.setMessage(msg);
                tgm.setTunnelId(replyTunnel);
                tgm.setMessageExpiration(msg.getMessageExpiration());
                out1 = tgm;
                if (out2 != null) {
                    TunnelGatewayMessage tgm2 = new TunnelGatewayMessage(getContext());
                    tgm2.setMessage(msg2);
                    tgm2.setTunnelId(replyTunnel);
                    tgm2.setMessageExpiration(msg.getMessageExpiration());
                    out2 = tgm2;
                }
            }
            Job send = new SendMessageDirectJob(getContext(), out1, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), out2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
                send2.runJob();
            }
            return;
        }

            // pick tunnel with endpoint closest to toPeer
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundExploratoryTunnel(toPeer);
            if (outTunnel == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("(dbid: " + _facade + ") No outbound tunnel could be found");
                return;
            }
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0),
                                                             replyTunnel, toPeer);
            if (msg2 != null)
                getContext().tunnelDispatcher().dispatchOutbound(msg2, outTunnel.getSendTunnelId(0),
                                                                 replyTunnel, toPeer);
    }
 
    public String getName() { return "Handle Database Store Message"; }
    
    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
