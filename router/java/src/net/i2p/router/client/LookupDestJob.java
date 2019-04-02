/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.router.client;

import java.util.Locale;

import net.i2p.crypto.Blinding;
import net.i2p.data.Base32;
import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Look up the lease of a hash, to convert it to a Destination for the client.
 * Or, since 0.9.11, lookup a host name in the naming service.
 */
class LookupDestJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final long _reqID;
    private final long _timeout;
    private final Hash _hash;
    private final String _name;
    private final SessionId _sessID;
    private final Hash _fromLocalDest;
    private final BlindData _blindData;

    private static final long DEFAULT_TIMEOUT = 15*1000;

    public LookupDestJob(RouterContext context, ClientConnectionRunner runner, Hash h, Hash fromLocalDest) {
        this(context, runner, -1, DEFAULT_TIMEOUT, null, h, null, fromLocalDest);
    }

    /**
     *  One of h or name non-null.
     *
     *  For hash or b32 name, the dest will be returned if the LS can be found,
     *  even if the dest uses unsupported crypto.
     *
     *  @param reqID must be &gt;= 0 if name != null
     *  @param sessID must non-null if reqID &gt;= 0
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.11
     */
    public LookupDestJob(RouterContext context, ClientConnectionRunner runner,
                         long reqID, long timeout, SessionId sessID, Hash h, String name,
                         Hash fromLocalDest) {
        super(context);
        _log = context.logManager().getLog(LookupDestJob.class);
        if ((h == null && name == null) ||
            (h != null && name != null) ||
            (reqID >= 0 && sessID == null) ||
            (reqID < 0 && name != null)) {
            _log.warn("bad args");
            throw new IllegalArgumentException();
        }
        _runner = runner;
        _reqID = reqID;
        _timeout = timeout;
        _sessID = sessID;
        _fromLocalDest = fromLocalDest;
        BlindData bd = null;
        if (name != null && name.length() >= 60) {
            // convert a b32 lookup to a hash lookup
            String nlc = name.toLowerCase(Locale.US);
            if (nlc.endsWith(".b32.i2p")) {
                byte[] b = Base32.decode(nlc.substring(0, nlc.length() - 8));
                if (b != null) {
                    if (b.length == Hash.HASH_LENGTH) {
                        h = Hash.create(b);
                        if (_log.shouldDebug())
                            _log.debug("Converting name lookup " + name + " to " + h);
                        name = null;
                    } else if (b.length >= 35) {
                        // encrypted LS2
                        // lookup the blinded hash
                        try {
                            bd = Blinding.decode(context, b);
                            SigningPublicKey spk = bd.getUnblindedPubKey();
                            BlindData bd2 = getContext().netDb().getBlindData(spk);
                            if (bd2 != null) {
                                bd = bd2;
                            } else {
                                getContext().netDb().setBlindData(bd);
                            }
                            h = bd.getBlindedHash();
                            if (_log.shouldDebug())
                                _log.debug("Converting name lookup " + name + " to blinded " + h);
                            name = null;
                        } catch (RuntimeException re) {
                            if (_log.shouldWarn())
                                _log.debug("Failed blinding conversion of " + name, re);
                            // Do NOT lookup as a name, naming service will call us again and infinite loop
                            name = null;
                            // h and name both null, runJob will fail immediately
                        }
                    }
                }
            }
        }
        _hash = h;
        _name = name;
        _blindData = bd;
    }
    
    public String getName() { return _name != null ?
                                     "HostName Lookup for Client" :
                                     "LeaseSet Lookup for Client";
    }

    public void runJob() {
        if (_blindData != null) {
            Destination d = _blindData.getDestination();
            if (d != null) {
                if (_log.shouldDebug())
                    _log.debug("Found cached b33 lookup " + _name + " to " + d);
                returnDest(d);
                return;
            }
        }
        if (_name != null) {
            // inline, ignore timeout
            Destination d = getContext().namingService().lookup(_name);
            if (d != null) {
                if (_log.shouldDebug())
                    _log.debug("Found name lookup " + _name + " to " + d);
                returnDest(d);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Failed name lookup " + _name);
                returnFail();
            }
        } else if (_hash != null) {
            DoneJob done = new DoneJob(getContext());
            // TODO tell router this is an encrypted lookup, skip 38 or earlier ffs?
            getContext().netDb().lookupDestination(_hash, done, _timeout, _fromLocalDest);
        } else {
            // blinding decode fail
            returnFail();
        }
    }

    private class DoneJob extends JobImpl {
        public DoneJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }
        public String getName() { return "LeaseSet Lookup Reply to Client"; }
        public void runJob() {
            Destination dest = getContext().netDb().lookupDestinationLocally(_hash);
            if (dest == null && _blindData != null) {
                // TODO store and lookup original hash instead
                LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_hash);
                if (ls != null && ls.getType() == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    // already decrypted
                    EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                    LeaseSet decls = encls.getDecryptedLeaseSet();
                    if (decls != null) {
                        dest = decls.getDestination();
                    }
                }
            }
            if (dest != null) {
                if (_log.shouldDebug())
                    _log.debug("Found hash lookup " + _hash + " to " + dest);
                returnDest(dest);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Failed hash lookup " + _hash);
                returnFail();
            }
        }
    }

    private void returnDest(Destination d) {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(_sessID, d, _reqID);
        else
            msg = new DestReplyMessage(d);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }

    /**
     *  Return the failed hash so the client can correlate replies with requests
     *  @since 0.8.3
     */
    private void returnFail() {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(_sessID, HostReplyMessage.RESULT_FAILURE, _reqID);
        else if (_hash != null)
            msg = new DestReplyMessage(_hash);
        else
            return; // shouldn't happen
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }
}
