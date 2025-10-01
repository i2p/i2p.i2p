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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
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
import net.i2p.util.OrderedProperties;

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
    public static final String PROP_LS_TYPE = "i2cp.leaseSetType";
    private static final String PROP_LS_ENCTYPE = "i2cp.leaseSetEncType";
    private static final String PROP_SECRET = "i2cp.leaseSetSecret";
    private static final String PROP_AUTH_TYPE = "i2cp.leaseSetAuthType";
    private static final String PROP_PRIV_KEY = "i2cp.leaseSetPrivKey";
    private static final String PROP_DH = "i2cp.leaseSetClient.dh.";
    private static final String PROP_PSK = "i2cp.leaseSetClient.psk.";

    private static final boolean PREFER_NEW_ENC = true;

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
        // we do this check first because we must set _ls2Type regardless
        String s = session.getOptions().getProperty(PROP_LS_TYPE);
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
        if (session.isOffline())
            return true;
        s = session.getOptions().getProperty(PROP_LS_ENCTYPE);
        if (s != null) {
            if (!s.equals("0") && !s.equals("ELGAMAL_2048"))
                return true;
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
            LeaseSet2 ls2;
            if (_ls2Type == DatabaseEntry.KEY_TYPE_LS2) {
                ls2 = new LeaseSet2();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                ls2 = new EncryptedLeaseSet();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                ls2= new MetaLeaseSet();
            } else {
              session.propogateError("Unsupported LS2 type", new Exception());
              session.destroySession();
              return;
            }
            if (Boolean.parseBoolean(session.getOptions().getProperty("i2cp.dontPublishLeaseSet")))
                ls2.setUnpublished();

            // Service records, proposal 167
            String k = "i2cp.leaseSetOption.0";
            Properties props = null;
            for (int i = 0; i < 10; i++) {
                String v = session.getOptions().getProperty(k);
                if (v == null)
                    break;
                String[] vs = DataHelper.split(v, "=", 2);
                if (vs.length < 2)
                    continue;
                if (props == null)
                    props = new OrderedProperties();
                props.setProperty(vs[0], vs[1]);
                k = "i2cp.leaseSetOption." + (i + 1);
            }
            if (props != null)
                ls2.setOptions(props);

            // ensure 1-second resolution timestamp is higher than last one
            long now = Math.max(_context.clock().now(), session.getLastLS2SignTime() + 1000);
            ls2.setPublished(now);
            session.setLastLS2SignTime(now);
            leaseSet = ls2;
        } else {
            leaseSet = new LeaseSet();
        }
        // Full Meta support TODO
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
            lease.setEndDate(msg.getEndDate().getTime());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        signLeaseSet(leaseSet, isLS2, session);
    }

    /**
     *  Finish creating and signing the new LeaseSet
     *  @since 0.9.7
     */
    protected synchronized void signLeaseSet(LeaseSet leaseSet, boolean isLS2, I2PSessionImpl session) {
        // must be before setDestination()
        if (isLS2 && _ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            String secret = session.getOptions().getProperty(PROP_SECRET);
            if (secret != null) {
                EncryptedLeaseSet encls2 = (EncryptedLeaseSet) leaseSet;
                secret = DataHelper.getUTF8(Base64.decode(secret));
                encls2.setSecret(secret);
            }
        }
        Destination dest = session.getMyDestination();
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(dest);

        // reuse the old keys for the client
        LeaseInfo li = _existingLeaseSets.get(dest);
        if (li == null) {
            List<EncType> types = new ArrayList<EncType>(2);
            String senc = session.getOptions().getProperty(PROP_LS_ENCTYPE);
            if (senc != null) {
                if (!PREFER_NEW_ENC && senc.equals("4,0"))
                    senc = "0,4";
                else if (PREFER_NEW_ENC && senc.equals("0,4"))
                    senc = "4,0";
                String[] senca = DataHelper.split(senc, ",");
                for (String sencaa : senca) {
                    EncType newtype = EncType.parseEncType(sencaa);
                    if (newtype != null) {
                        if (types.contains(newtype)) {
                            _log.error("Duplicate crypto type: " + newtype);
                            continue;
                        }
                        if (newtype.isAvailable()) {
                            types.add(newtype);
                        } else {
                            _log.error("Unsupported crypto type: " + newtype);
                        }
                    } else {
                        _log.error("Unsupported crypto type: " + sencaa);
                    }
                }
            }
            if (types.isEmpty()) {
                //if (_log.shouldDebug())
                //    _log.debug("Using default crypto type");
                types.add(EncType.ELGAMAL_2048);
            }

            // [enctype:]b64,... of private keys
            String spk = session.getOptions().getProperty(PROP_LS_PK);
            // [sigtype:]b64 of private key
            // only for LS1
            String sspk = isLS2 ? null : session.getOptions().getProperty(PROP_LS_SPK);
            List<PrivateKey> privKeys = new ArrayList<PrivateKey>(2);
            SigningPrivateKey signingPrivKey = null;
            if (spk != null && (isLS2 || sspk != null)) {
                boolean useOldKeys = true;
                if (!isLS2) {
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
                    if (useOldKeys) {
                        try {
                            signingPrivKey = new SigningPrivateKey(type);
                            signingPrivKey.fromBase64(sspk);
                        } catch (DataFormatException dfe) {
                            useOldKeys = false;
                            signingPrivKey = null;
                        }
                    }
                }
                if (useOldKeys) {
                    parsePrivateKeys(spk, privKeys, types);
                }
            }
            if (privKeys.isEmpty() && !_existingLeaseSets.isEmpty()) {
                // look for private keys from another dest using same pubkey
                PublicKey pk = dest.getPublicKey();
                for (Map.Entry<Destination, LeaseInfo> e : _existingLeaseSets.entrySet()) {
                    if (pk.equals(e.getKey().getPublicKey())) {
                        privKeys.addAll(e.getValue().getPrivateKeys());
                        if (_log.shouldInfo())
                            _log.info("Creating leaseInfo for " + dest.toBase32() + " with private key from " + e.getKey().toBase32());
                        break;
                    }
                }
            }
            if (!privKeys.isEmpty()) {
                if (signingPrivKey != null) {
                    li = new LeaseInfo(privKeys, signingPrivKey);
                    if (_log.shouldInfo())
                        _log.info("Creating leaseInfo for " + dest.toBase32() + " LS1 WITH configured private keys");
                } else if (isLS2) {
                    li = new LeaseInfo(privKeys);
                    if (_log.shouldInfo())
                        _log.info("Creating leaseInfo for " + dest.toBase32() + " LS2 WITH configured private keys");
                } else {
                    li = new LeaseInfo(privKeys, dest);
                    if (_log.shouldInfo())
                        _log.info("Creating leaseInfo for " + dest.toBase32() + " LS1 WITH configured private keys and new revocation key");
                }
            } else {
                li = new LeaseInfo(dest, types, isLS2);
                if (_log.shouldInfo())
                    _log.info("Creating leaseInfo for " + dest.toBase32() + " without configured private keys");
            }
            _existingLeaseSets.put(dest, li);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caching the old leaseInfo keys for " 
                           + dest.toBase32());
        }

        if (isLS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            if (_ls2Type != DatabaseEntry.KEY_TYPE_META_LS2) {
                for (PublicKey key : li.getPublicKeys()) {
                    ls2.addEncryptionKey(key);
                }
            }
        } else {
            leaseSet.setEncryptionKey(li.getPublicKey());
            // revocation key
            leaseSet.setSigningKey(li.getSigningPublicKey());
        }
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
            long exp = session.getOfflineExpiration();
            boolean ok = ls2.setOfflineSignature(exp, session.getTransientSigningPublicKey(),
                                                 session.getOfflineSignature());
            if (!ok) {
                String s;
                if (exp <= _context.clock().now())
                    s = "Offline signature for tunnel expired " + DataHelper.formatTime(exp);
                else
                    s = "Bad offline signature";
                session.propogateError(s, new Exception());
                session.destroySession();
            }
        }
        try {
            if (isLS2 && _ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                EncryptedLeaseSet els2 = (EncryptedLeaseSet) leaseSet;
                String at = opts.getProperty(PROP_AUTH_TYPE, "0");
                if (at.equals("1")) {
                    int authType = BlindData.AUTH_DH;
                    List<PublicKey> clientKeys = new ArrayList<PublicKey>(4);
                    String pfx = PROP_DH;
                    String p = opts.getProperty(PROP_PRIV_KEY);
                    if (p == null) {
                        _log.error("No " + PROP_PRIV_KEY + " for DH auth");
                    } else {
                        byte[] b = Base64.decode(p);
                        try {
                            PrivateKey pk = new PrivateKey(EncType.ECIES_X25519, b);
                            clientKeys.add(pk.toPublic());
                        } catch (IllegalArgumentException iae) {
                            _log.error("Bad priv key: " + p, iae);
                        }
                    }
                    int i = 0;
                    while ((p = opts.getProperty(pfx + i)) != null) {
                        int colon = p.indexOf(':');
                        if (colon >= 0)
                            p = p.substring(colon + 1);
                        byte[] b = Base64.decode(p);
                        try {
                            PublicKey pk = new PublicKey(EncType.ECIES_X25519, b);
                            clientKeys.add(pk);
                        } catch (IllegalArgumentException iae) {
                            _log.error("Bad client key: " + p, iae);
                        }
                        i++;
                    }
                    els2.sign(session.getPrivateKey(), authType, clientKeys);
                } else if (at.equals("2")) {
                    int authType = BlindData.AUTH_PSK;
                    List<PrivateKey> clientKeys = new ArrayList<PrivateKey>(4);
                    String pfx = PROP_PSK;
                    String p = opts.getProperty(PROP_PRIV_KEY);
                    if (p == null) {
                        _log.error("No " + PROP_PRIV_KEY + " for PSK auth");
                    } else {
                        byte[] b = Base64.decode(p);
                        try {
                            PrivateKey pk = new PrivateKey(EncType.ECIES_X25519, b);
                            clientKeys.add(pk);
                        } catch (IllegalArgumentException iae) {
                            _log.error("Bad priv key: " + p, iae);
                        }
                    }
                    int i = 0;
                    while ((p = opts.getProperty(pfx + i)) != null) {
                        int colon = p.indexOf(':');
                        if (colon >= 0)
                            p = p.substring(colon + 1);
                        byte[] b = Base64.decode(p);
                        try {
                            PrivateKey pk = new PrivateKey(EncType.ECIES_X25519, b);
                            clientKeys.add(pk);
                        } catch (IllegalArgumentException iae) {
                            _log.error("Bad client key: " + p, iae);
                        }
                        i++;
                    }
                    els2.sign(session.getPrivateKey(), authType, clientKeys);
                } else {
                    els2.sign(session.getPrivateKey());
                }
            } else {
                leaseSet.sign(session.getPrivateKey());
            }
            SigningPrivateKey spk = li.getSigningPrivateKey();
            if (isLS2) {
                // no revocation key in LS2
                spk = null;
            } else if (!_context.isRouterContext() && spk.getType() != SigType.DSA_SHA1) {
                // Workaround for unparsable serialized signing private key for revocation
                // Send him a dummy DSA_SHA1 private key since it's unused anyway
                // See CreateLeaseSetMessage.doReadMessage()
                // For LS1 only
                byte[] dummy = new byte[SigningPrivateKey.KEYSIZE_BYTES];
                _context.random().nextBytes(dummy);
                spk = new SigningPrivateKey(dummy);
                if (_log.shouldDebug())
                    _log.debug("Generated random dummy SPK " + spk);
            }
            session.getProducer().createLeaseSet(session, leaseSet, spk, li.getPrivateKeys());
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

    /**
     *  @param spk non-null [type:]b64[,[type:]b64]...
     *  @param privKeys out parameter
     *  @since 0.9.39
     */
    private void parsePrivateKeys(String spkl, List<PrivateKey> privKeys, List<EncType> allowedTypes) {
        String[] spks = DataHelper.split(spkl, ",");
        for (String spk : spks) {
            int colon = spk.indexOf(':');
            if (colon > 0) {
                EncType type = EncType.parseEncType(spk.substring(0, colon));
                if (type != null) {
                    if (type.isAvailable()) {
                        if (allowedTypes.contains(type)) {
                            try {
                                PrivateKey privKey = new PrivateKey(type);
                                privKey.fromBase64(spk.substring(colon + 1));
                                privKeys.add(privKey);
                            } catch (DataFormatException dfe) {
                                _log.error("Bad private key: " + spk, dfe);
                            }
                        } else {
                            if (_log.shouldDebug())
                                _log.debug("Ignoring private key with unconfigured crypto type: " + type);
                        }
                    } else {
                        _log.error("Unsupported crypto type: " + type);
                    }
                } else {
                    _log.error("Unsupported crypto type: " + spk);
                }
            } else if (colon < 0) {
                EncType type = EncType.ELGAMAL_2048;
                if (allowedTypes.contains(type)) {
                    try {
                        PrivateKey privKey = new PrivateKey();
                        privKey.fromBase64(spk);
                        privKeys.add(privKey);
                    } catch (DataFormatException dfe) {
                        _log.error("Bad private key: " + spk, dfe);
                    }
                } else {
                    if (_log.shouldDebug())
                        _log.debug("Ignoring private key with unconfigured crypto type: " + type);
                }
            } else {
                _log.error("Empty crypto type");
            }
        }
    }

    /**
     *  Multiple encryption keys supported, as of 0.9.39, for LS2
     */
    private static class LeaseInfo {
        private final List<PublicKey> _pubKeys;
        private final List<PrivateKey> _privKeys;
        private final SigningPublicKey _signingPubKey;
        private final SigningPrivateKey _signingPrivKey;

        /**
         *  New keys
         *  @param types must be available
         */
        public LeaseInfo(Destination dest, List<EncType> types, boolean isLS2) {
            if (types.size() > 1 && PREFER_NEW_ENC) {
                Collections.sort(types, Collections.reverseOrder());
            }
            _privKeys = new ArrayList<PrivateKey>(types.size());
            _pubKeys = new ArrayList<PublicKey>(types.size());
            for (EncType type : types) {
                KeyPair encKeys = KeyGenerator.getInstance().generatePKIKeys(type);
                _pubKeys.add(encKeys.getPublic());
                _privKeys.add(encKeys.getPrivate());
            }
            if (isLS2) {
                _signingPubKey = null;
                _signingPrivKey = null;
            } else {
                // must be same type as the Destination's signing key
                SimpleDataStructure signKeys[];
                try {
                    signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
                } catch (GeneralSecurityException gse) {
                    throw new IllegalStateException(gse);
                }
                _signingPubKey = (SigningPublicKey) signKeys[0];
                _signingPrivKey = (SigningPrivateKey) signKeys[1];
            }
        }

        /**
         *  Existing keys, LS1 only
         *  @param privKeys all EncTypes must be available
         *  @since 0.9.18
         */
        public LeaseInfo(List<PrivateKey> privKeys, SigningPrivateKey signingPrivKey) {
            if (privKeys.size() > 1) {
                Collections.sort(privKeys, new PrivKeyComparator());
            }
            _privKeys = privKeys;
            _pubKeys = new ArrayList<PublicKey>(privKeys.size());
            for (PrivateKey privKey : privKeys) {
                _pubKeys.add(KeyGenerator.getPublicKey(privKey));
            }
            _signingPubKey = KeyGenerator.getSigningPublicKey(signingPrivKey);
            _signingPrivKey = signingPrivKey;
        }

        /**
         *  Existing crypto keys, new signing key, LS1 only
         *  @param privKeys all EncTypes must be available
         *  @since 0.9.21
         */
        public LeaseInfo(List<PrivateKey> privKeys, Destination dest) {
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _privKeys = privKeys;
            _pubKeys = new ArrayList<PublicKey>(privKeys.size());
            for (PrivateKey privKey : privKeys) {
                _pubKeys.add(KeyGenerator.getPublicKey(privKey));
            }
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        /**
         *  Existing keys, LS2 only
         *  @param privKeys all EncTypes must be available
         *  @since 0.9.47
         */
        public LeaseInfo(List<PrivateKey> privKeys) {
            if (privKeys.size() > 1) {
                Collections.sort(privKeys, new PrivKeyComparator());
            }
            _privKeys = privKeys;
            _pubKeys = new ArrayList<PublicKey>(privKeys.size());
            for (PrivateKey privKey : privKeys) {
                _pubKeys.add(KeyGenerator.getPublicKey(privKey));
            }
            _signingPubKey = null;
            _signingPrivKey = null;
        }

        /** @return the first one if more than one */
        public PublicKey getPublicKey() {
            return _pubKeys.get(0);
        }

        /** @return the first one if more than one */
        public PrivateKey getPrivateKey() {
            return _privKeys.get(0);
        }

        /** @since 0.9.39 */
        public List<PublicKey> getPublicKeys() {
            return _pubKeys;
        }

        /** @since 0.9.39 */
        public List<PrivateKey> getPrivateKeys() {
            return _privKeys;
        }

        /** @return null for LS2 */
        public SigningPublicKey getSigningPublicKey() {
            return _signingPubKey;
        }

        /** @return null for LS2 */
        public SigningPrivateKey getSigningPrivateKey() {
            return _signingPrivKey;
        }

        /**
         *  Reverse order by enc type
         *  @since 0.9.39
         */
        private static class PrivKeyComparator implements Comparator<PrivateKey> {
            public int compare(PrivateKey l, PrivateKey r) {
                return r.getType().compareTo(l.getType());
            }
        }
    }
}
