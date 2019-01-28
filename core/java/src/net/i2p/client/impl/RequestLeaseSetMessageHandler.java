package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.EOFException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.MetaLease;
import net.i2p.data.MetaLeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP RequestLeaseSetMessage from the router by granting all leases,
 * using the specified expiration time for each lease.
 *
 * @author jrandom
 */
class RequestLeaseSetMessageHandler extends HandlerImpl {
    private final Map<Destination, LeaseInfo> _existingLeaseSets;
    protected int _ls2Type = DatabaseEntry.KEY_TYPE_LS2;

    // LS 1
    private static final String PROP_LS_ENCRYPT = "i2cp.encryptLeaseSet";
    private static final String PROP_LS_KEY = "i2cp.leaseSetKey";
    private static final String PROP_LS_PK = "i2cp.leaseSetPrivateKey";
    private static final String PROP_LS_SPK = "i2cp.leaseSetSigningPrivateKey";
    // LS 2
    private static final String PROP_LS_TYPE = "i2cp.leaseSetType";
    private static final String PROP_LS_ENCTYPE = "i2cp.leaseSetEncType";

    public RequestLeaseSetMessageHandler(I2PAppContext context) {
        this(context, RequestLeaseSetMessage.MESSAGE_TYPE);
    }

    /**
     *  For extension
     *  @since 0.9.7
     */
    protected RequestLeaseSetMessageHandler(I2PAppContext context, int messageType) {
        super(context, messageType);
        // not clear why there would ever be more than one
        _existingLeaseSets = new ConcurrentHashMap<Destination, LeaseInfo>(4);
    }
    
    /**
     *  Do we send a LeaseSet or a LeaseSet2?
     *
     *  Side effect: sets _ls2Type
     *
     *  @since 0.9.38
     */
    protected boolean requiresLS2(I2PSessionImpl session) {
        if (!session.supportsLS2())
            return false;
        if (session.isOffline())
            return true;
        String s = session.getOptions().getProperty(PROP_LS_ENCTYPE);
        if (s != null) {
            EncType type = EncType.parseEncType(s);
            if (type != null && type != EncType.ELGAMAL_2048 && type.isAvailable())
                return true;
        }
        s = session.getOptions().getProperty(PROP_LS_TYPE);
        if (s != null) {
            try {
                int type = Integer.parseInt(s);
                _ls2Type = type;
                if (type != DatabaseEntry.KEY_TYPE_LEASESET)
                    return true;
            } catch (NumberFormatException nfe) {
              session.propogateError("Bad LS2 type", nfe);
              session.destroySession();
              return true;
            }
        }
        return false;
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        RequestLeaseSetMessage msg = (RequestLeaseSetMessage) message;
        boolean isLS2 = requiresLS2(session);
        LeaseSet leaseSet;
        if (isLS2) {
            if (_ls2Type == DatabaseEntry.KEY_TYPE_LS2) {
                leaseSet = new LeaseSet2();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                leaseSet = new EncryptedLeaseSet();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                leaseSet = new MetaLeaseSet();
            } else {
              session.propogateError("Unsupported LS2 type", new Exception());
              session.destroySession();
              return;
            }
            if (Boolean.parseBoolean(session.getOptions().getProperty("i2cp.dontPublishLeaseSet")))
                ((LeaseSet2)leaseSet).setUnpublished();
        } else {
            leaseSet = new LeaseSet();
        }
        // Full Meta and Encrypted support TODO
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease;
            if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                lease = new MetaLease();
            } else if (isLS2) {
                lease = new Lease2();
                lease.setTunnelId(msg.getTunnelId(i));
            } else {
                lease = new Lease();
                lease.setTunnelId(msg.getTunnelId(i));
            }
            lease.setGateway(msg.getRouter(i));
            lease.setEndDate(msg.getEndDate());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        signLeaseSet(leaseSet, session);
    }

    /**
     *  Finish creating and signing the new LeaseSet
     *  @since 0.9.7
     */
    protected synchronized void signLeaseSet(LeaseSet leaseSet, I2PSessionImpl session) {
        Destination dest = session.getMyDestination();
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(dest);

        // reuse the old keys for the client
        LeaseInfo li = _existingLeaseSets.get(dest);
        if (li == null) {
            // [enctype:]b64 of private key
            String spk = session.getOptions().getProperty(PROP_LS_PK);
            // [sigtype:]b64 of private key
            String sspk = session.getOptions().getProperty(PROP_LS_SPK);
            PrivateKey privKey = null;
            SigningPrivateKey signingPrivKey = null;
            if (spk != null && sspk != null) {
                boolean useOldKeys = true;
                int colon = sspk.indexOf(':');
                SigType type = dest.getSigType();
                if (colon > 0) {
                    String stype = sspk.substring(0, colon);
                    SigType t = SigType.parseSigType(stype);
                    if (t == type)
                        sspk = sspk.substring(colon + 1);
                    else
                        useOldKeys = false;
                }
                colon = spk.indexOf(':');
                // just ignore for now, no other types supported
                if (colon >= 0)
                    spk = spk.substring(colon + 1);
                if (useOldKeys) {
                    try {
                        signingPrivKey = new SigningPrivateKey(type);
                        signingPrivKey.fromBase64(sspk);
                    } catch (DataFormatException iae) {
                        useOldKeys = false;
                        signingPrivKey = null;
                    }
                }
                if (useOldKeys) {
                    try {
                        privKey = new PrivateKey();
                        privKey.fromBase64(spk);
                    } catch (DataFormatException iae) {
                        privKey = null;
                    }
                }
            }
            if (privKey == null && !_existingLeaseSets.isEmpty()) {
                // look for keypair from another dest using same pubkey
                PublicKey pk = dest.getPublicKey();
                for (Map.Entry<Destination, LeaseInfo> e : _existingLeaseSets.entrySet()) {
                    if (pk.equals(e.getKey().getPublicKey())) {
                        privKey = e.getValue().getPrivateKey();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Creating new leaseInfo keys for " + dest + " with private key from " + e.getKey());
                        break;
                    }
                }
            }
            if (privKey != null) {
                if (signingPrivKey != null) {
                    li = new LeaseInfo(privKey, signingPrivKey);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Creating new leaseInfo keys for " + dest + " WITH configured private keys");
                } else {
                    li = new LeaseInfo(privKey, dest);
                }
            } else {
                EncType type = EncType.ELGAMAL_2048;
                String senc = session.getOptions().getProperty(PROP_LS_ENCTYPE);
                if (senc != null) {
                    EncType newtype = EncType.parseEncType(senc);
                    if (newtype != null) {
                        if (newtype.isAvailable()) {
                            type = newtype;
                            if (_log.shouldDebug())
                                _log.debug("Using crypto type: " + type);
                        } else {
                            _log.error("Unsupported crypto.encType: " + newtype);
                        }
                    } else {
                        _log.error("Bad crypto.encType: " + senc);
                    }
                } else {
                    if (_log.shouldDebug())
                        _log.debug("Using default crypto type");
                }
                li = new LeaseInfo(dest, type);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Creating new leaseInfo keys for " + dest + " without configured private keys");
            }
            _existingLeaseSets.put(dest, li);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caching the old leaseInfo keys for " 
                           + dest);
        }

        if (_ls2Type != DatabaseEntry.KEY_TYPE_META_LS2)
            leaseSet.setEncryptionKey(li.getPublicKey());
        leaseSet.setSigningKey(li.getSigningPublicKey());
        // SubSession options aren't updated via the gui, so use the primary options
        Properties opts;
        if (session instanceof SubSession)
            opts = ((SubSession) session).getPrimaryOptions();
        else
            opts = session.getOptions();
        boolean encrypt = Boolean.parseBoolean(opts.getProperty(PROP_LS_ENCRYPT));
        String sk = opts.getProperty(PROP_LS_KEY);
        Hash h = dest.calculateHash();
        if (encrypt && sk != null) {
            SessionKey key = new SessionKey();
            try {
                key.fromBase64(sk);
                leaseSet.encrypt(key);
                _context.keyRing().put(h, key);
            } catch (DataFormatException dfe) {
                _log.error("Bad leaseset key: " + sk);
                _context.keyRing().remove(h);
            }
        } else {
            _context.keyRing().remove(h);
        }
        // offline keys
        if (session.isOffline()) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            boolean ok = ls2.setOfflineSignature(session.getOfflineExpiration(), session.getTransientSigningPublicKey(),
                                                 session.getOfflineSignature());
            if (!ok) {
                session.propogateError("Bad offline signature", new Exception());
                session.destroySession();
            }
        }
        try {
            leaseSet.sign(session.getPrivateKey());
            // Workaround for unparsable serialized signing private key for revocation
            // Send him a dummy DSA_SHA1 private key since it's unused anyway
            // See CreateLeaseSetMessage.doReadMessage()
            // For LS1 only
            SigningPrivateKey spk = li.getSigningPrivateKey();
            if (!_context.isRouterContext() && spk.getType() != SigType.DSA_SHA1 &&
                !(leaseSet instanceof LeaseSet2)) {
                byte[] dummy = new byte[SigningPrivateKey.KEYSIZE_BYTES];
                _context.random().nextBytes(dummy);
                spk = new SigningPrivateKey(dummy);
            }
            session.getProducer().createLeaseSet(session, leaseSet, spk, li.getPrivateKey());
            session.setLeaseSet(leaseSet);
            if (_log.shouldDebug())
                _log.debug("Created and signed LeaseSet: " + leaseSet);
        } catch (DataFormatException dfe) {
            session.propogateError("Error signing the leaseSet", dfe);
            session.destroySession();
        } catch (I2PSessionException ise) {
            if (session.isClosed()) {
                // race, closed while signing leaseset
                // EOFExceptions are logged at WARN level (see I2PSessionImpl.propogateError())
                // so the user won't see this
                EOFException eof = new EOFException("Session closed while signing leaseset");
                eof.initCause(ise);
                session.propogateError("Session closed while signing leaseset", eof);
            } else {
                session.propogateError("Error sending the signed leaseSet", ise);
            }
        }
    }

    private static class LeaseInfo {
        private final PublicKey _pubKey;
        private final PrivateKey _privKey;
        private final SigningPublicKey _signingPubKey;
        private final SigningPrivateKey _signingPrivKey;

        /**
         *  New keys
         */
        public LeaseInfo(Destination dest, EncType type) {
            KeyPair encKeys = KeyGenerator.getInstance().generatePKIKeys(type);
            // must be same type as the Destination's signing key
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _pubKey = encKeys.getPublic();
            _privKey = encKeys.getPrivate();
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        /**
         *  Existing keys
         *  @since 0.9.18
         */
        public LeaseInfo(PrivateKey privKey, SigningPrivateKey signingPrivKey) {
            _pubKey = KeyGenerator.getPublicKey(privKey);
            _privKey = privKey;
            _signingPubKey = KeyGenerator.getSigningPublicKey(signingPrivKey);
            _signingPrivKey = signingPrivKey;
        }

        /**
         *  Existing crypto key, new signing key
         *  @since 0.9.21
         */
        public LeaseInfo(PrivateKey privKey, Destination dest) {
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _pubKey = KeyGenerator.getPublicKey(privKey);
            _privKey = privKey;
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        public PublicKey getPublicKey() {
            return _pubKey;
        }

        public PrivateKey getPrivateKey() {
            return _privKey;
        }

        public SigningPublicKey getSigningPublicKey() {
            return _signingPubKey;
        }

        public SigningPrivateKey getSigningPrivateKey() {
            return _signingPrivKey;
        }
        
        @Override
        public int hashCode() {
            return DataHelper.hashCode(_pubKey) + 7 * DataHelper.hashCode(_privKey) + 7 * 7
                   * DataHelper.hashCode(_signingPubKey) + 7 * 7 * 7 * DataHelper.hashCode(_signingPrivKey);
        }
        
        @Override
        public boolean equals(Object obj) {
            if ((obj == null) || !(obj instanceof LeaseInfo)) return false;
            LeaseInfo li = (LeaseInfo) obj;
            return DataHelper.eq(_pubKey, li.getPublicKey()) && DataHelper.eq(_privKey, li.getPrivateKey())
                   && DataHelper.eq(_signingPubKey, li.getSigningPublicKey())
                   && DataHelper.eq(_signingPrivKey, li.getSigningPrivateKey());
        }
    }
}
