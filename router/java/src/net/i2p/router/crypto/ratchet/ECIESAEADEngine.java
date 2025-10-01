package net.i2p.router.crypto.ratchet;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.southernstorm.noise.crypto.x25519.Curve25519;
import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.DHState;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.crypto.pqc.MLKEM;
import static net.i2p.router.crypto.ratchet.RatchetPayload.*;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.message.CloveSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Handles the actual ECIES+AEAD encryption and decryption scenarios using the
 * supplied keys and data.
 *
 * No, this does not extend ElGamalAESEngine or AEADEngine or CryptixAEADEngine.
 *
 * @since 0.9.44
 */
public final class ECIESAEADEngine {
    private final RouterContext _context;
    private final Log _log;
    private final MuxedEngine _muxedEngine;
    private final MuxedPQEngine _muxedPQEngine;
    private final HKDF _hkdf;
    private final Elg2KeyFactory _edhThread;
    private boolean _isRunning;

    private static final byte[] ZEROLEN = new byte[0];
    private static final int TAGLEN = 8;
    private static final int MACLEN = 16;
    private static final int KEYLEN = 32;
    private static final int BHLEN = RatchetPayload.BLOCK_HEADER_SIZE;  // 3
    private static final int DATETIME_SIZE = BHLEN + 4; // 7
    private static final int NS_OVERHEAD = KEYLEN + KEYLEN + MACLEN + MACLEN; // 96
    private static final int NS_N_OVERHEAD = KEYLEN + MACLEN; // 48
    private static final int NSR_OVERHEAD = TAGLEN + KEYLEN + MACLEN + MACLEN; // 72
    private static final int ES_OVERHEAD = TAGLEN + MACLEN; // 24
    private static final int MIN_NS_SIZE = NS_OVERHEAD + DATETIME_SIZE; // 103
    private static final int MIN_NS_N_SIZE = NS_N_OVERHEAD + DATETIME_SIZE; // 55
    private static final int MIN_NSR_SIZE = NSR_OVERHEAD; // 72
    private static final int MIN_ES_SIZE = ES_OVERHEAD; // 24
    private static final int MIN_ENCRYPTED_SIZE = MIN_ES_SIZE;
    private static final byte[] NULLPK = new byte[KEYLEN];
    private static final int MAXPAD = 16;
    static final long MAX_NS_AGE = 5*60*1000;
    private static final long MAX_NS_FUTURE = 2*60*1000;
    // debug, send ACKREQ in every ES
    private static final boolean ACKREQ_IN_ES = false;
    // return value for a payload failure after a successful decrypt,
    // so we don't continue with ElG
    private static final GarlicClove[] NO_GARLIC = new GarlicClove[] {};
    private static final CloveSet NO_CLOVES = new CloveSet(NO_GARLIC, Certificate.NULL_CERT, 0, 0);

    private static final String INFO_0 = "SessionReplyTags";
    private static final String INFO_6 = "AttachPayloadKDF";

    // These are the min sizes for the MLKEM New Session Message.
    // It contains an extra MLKEM key and MAC.
    // 112
    private static final int NS_MLKEM_OVERHEAD = NS_OVERHEAD + MACLEN;
    // 800 + 112 + 7 = 919
    private static final int MIN_NS_MLKEM512_SIZE = EncType.MLKEM512_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    // 1184 + 112 + 7 = 1303
    private static final int MIN_NS_MLKEM768_SIZE = EncType.MLKEM768_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    // 1568 + 112 + 7 = 1687
    private static final int MIN_NS_MLKEM1024_SIZE = EncType.MLKEM1024_X25519_INT.getPubkeyLen() + NS_MLKEM_OVERHEAD + DATETIME_SIZE;
    // 856
    private static final int MIN_NSR_MLKEM512_SIZE = EncType.MLKEM512_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;
    // 1176
    private static final int MIN_NSR_MLKEM768_SIZE = EncType.MLKEM768_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;
    // 1656
    private static final int MIN_NSR_MLKEM1024_SIZE = EncType.MLKEM1024_X25519_CT.getPubkeyLen() + MIN_NSR_SIZE + MACLEN;


    /**
     *  Caller MUST call startup() to get threaded generation.
     *  Will still work without, will just generate inline.
     *
     *  startup() is called from RatchetSKM constructor so it's deferred until we need it.
     */
    public ECIESAEADEngine(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(ECIESAEADEngine.class);
        _muxedEngine = new MuxedEngine(ctx);
        _muxedPQEngine = new MuxedPQEngine(ctx);
        _hkdf = new HKDF(ctx);
        _edhThread = new Elg2KeyFactory(ctx);
        
        _context.statManager().createFrequencyStat("crypto.eciesAEAD.encryptNewSession",
                                                   "how frequently we encrypt to a new ECIES/AEAD+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.eciesAEAD.encryptExistingSession",
                                                   "how frequently we encrypt to an existing ECIES/AEAD+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.eciesAEAD.decryptNewSession",
                                                   "how frequently we decrypt with a new ECIES/AEAD+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.eciesAEAD.decryptExistingSession",
                                                   "how frequently we decrypt with an existing ECIES/AEAD+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.eciesAEAD.decryptFailed",
                                                   "how frequently we fail to decrypt with ECIES/AEAD+SessionTag?",
                                                   "Encryption", new long[] { 60*60*1000l});
    }

    /**
     *  May be called multiple times
     */
    public synchronized void startup() {
        if (!_isRunning) {
            _edhThread.start();
            _isRunning = true;
        }
    }

    /**
     *  Cannot be restarted
     */
    public synchronized void shutdown() {
        _isRunning = false;
        _edhThread.shutdown();
    }

    //// start decrypt ////

    /**
     * Try to decrypt the message with one or both of the given private keys
     *
     * @param elgKey must be ElG, non-null
     * @param ecKey must be EC, non-null
     * @return decrypted data or null on failure
     */
    public CloveSet decrypt(byte data[], PrivateKey elgKey, PrivateKey ecKey, MuxedSKM keyManager) throws DataFormatException {
        return _muxedEngine.decrypt(data, elgKey, ecKey, keyManager);
    }

    /**
     * Try to decrypt the message with one or both of the given private keys
     *
     * @param ecKey must be EC, non-null
     * @param pqKey must be PQ, non-null
     * @return decrypted data or null on failure
     * @since 0.9.67
     */
    public CloveSet decrypt(byte data[], PrivateKey ecKey, PrivateKey pqKey, MuxedPQSKM keyManager) throws DataFormatException {
        return _muxedPQEngine.decrypt(data, ecKey, pqKey, keyManager);
    }

    /**
     * Decrypt the message using the given private key
     * and using tags from the specified key manager.
     * This works according to the
     * ECIES+AEAD algorithm in the data structure spec.
     *
     * Warning - use the correct SessionKeyManager. Clients should instantiate their own.
     * Clients using I2PAppContext.sessionKeyManager() may be correlated with the router,
     * unless you are careful to use different keys.
     *
     * @return decrypted data or null on failure
     */
    public CloveSet decrypt(byte data[], PrivateKey targetPrivateKey,
                            RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decrypt(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decrypt error", dfe);
            return NO_CLOVES;
        } catch (Exception e) {
            _log.error("ECIES decrypt error", e);
            return NO_CLOVES;
        }
    }

    private CloveSet x_decrypt(byte data[], PrivateKey targetPrivateKey,
                               RatchetSKM keyManager) throws DataFormatException {
        checkType(targetPrivateKey.getType());
        if (data == null) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Null data being decrypted?");
            return null;
        }
        if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldWarn())
                _log.warn("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte tag[] = new byte[TAGLEN];
        System.arraycopy(data, 0, tag, 0, TAGLEN);
        RatchetSessionTag st = new RatchetSessionTag(tag);
        SessionKeyAndNonce key = keyManager.consumeTag(st);
        CloveSet decrypted;
        if (key != null) {
            decrypted = xx_decryptFast(tag, st, key, data, targetPrivateKey, keyManager);
            // we do NOT retry as NS
        } else {
            decrypted = x_decryptSlow(data, targetPrivateKey, keyManager);
        }
        if (decrypted == null && _log.shouldDebug())
            _log.info("Decrypt fail NS/NSR/ES, possible tag: " + st);
        return decrypted;
    }

    /**
     * NSR/ES only. For MuxedEngine use only.
     *
     * @return decrypted data or null on failure
     * @since 0.9.46
     */
    CloveSet decryptFast(byte data[], PrivateKey targetPrivateKey,
                         RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decryptFast(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decrypt error", dfe);
            return NO_CLOVES;
        } catch (Exception e) {
            _log.error("ECIES decrypt error", e);
            return NO_CLOVES;
        }
    }

    /**
     * NSR/ES only.
     *
     * @return decrypted data or null on failure
     * @since 0.9.46
     */
    private CloveSet x_decryptFast(byte data[], PrivateKey targetPrivateKey,
                                   RatchetSKM keyManager) throws DataFormatException {
        if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldDebug())
                _log.debug("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }
        byte tag[] = new byte[TAGLEN];
        System.arraycopy(data, 0, tag, 0, TAGLEN);
        RatchetSessionTag st = new RatchetSessionTag(tag);
        SessionKeyAndNonce key = keyManager.consumeTag(st);
        CloveSet decrypted;
        if (key != null) {
            decrypted = xx_decryptFast(tag, st, key, data, targetPrivateKey, keyManager);
        } else {
            decrypted = null;
        }
        return decrypted;
    }

    /**
     * NSR/ES only.
     *
     * @param key non-null
     * @param data non-null
     * @return decrypted data or null on failure
     * @since 0.9.46
     */
    private CloveSet xx_decryptFast(byte[] tag, RatchetSessionTag st, SessionKeyAndNonce key,
                                    byte data[], PrivateKey targetPrivateKey,
                                    RatchetSKM keyManager) throws DataFormatException {
        CloveSet decrypted;
        final boolean shouldDebug = _log.shouldDebug();
        HandshakeState state = key.getHandshakeState();
        if (state == null) {
            if (shouldDebug)
                _log.debug("Decrypting ES with tag: " + st.toBase64() + " key: " + key + ": " + data.length + " bytes");
            decrypted = decryptExistingSession(tag, data, key, targetPrivateKey, keyManager);
        } else {
            // it's important not to attempt decryption for too-short packets,
            // because Noise will destroy() the handshake state on failure,
            // and we don't clone() on the first one, so it's fatal.
            EncType type = targetPrivateKey.getType();
            int min = getMinNSRSize(type);
            if (data.length >= min) {
                if (shouldDebug)
                    _log.debug("Decrypting NSR with tag: " + st.toBase64() + " key: " + key + ": " + data.length + " bytes");
                decrypted = decryptNewSessionReply(tag, data, state, keyManager);
            } else {
                decrypted = null;
                if (_log.shouldWarn())
                    _log.warn("NSR decrypt fail, tag: " + st.toBase64() + " but packet too small: " + data.length + " bytes, min is " +
                              min + " on state " + state);
            }
        }
        if (decrypted != null) {
            _context.statManager().updateFrequency("crypto.eciesAEAD.decryptExistingSession");
        } else {
            _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
            if (_log.shouldWarn()) {
                _log.warn("ECIES decrypt fail: known tag [" + st + "], failed decrypt with key " + key);
            }
        }
        return decrypted;
    }

    /**
     * NS only. For MuxedEngine use only.
     *
     * @return decrypted data or null on failure
     * @since 0.9.46
     */
    CloveSet decryptSlow(byte data[], PrivateKey targetPrivateKey,
                            RatchetSKM keyManager) throws DataFormatException {
        try {
            return x_decryptSlow(data, targetPrivateKey, keyManager);
        } catch (DataFormatException dfe) {
            if (_log.shouldWarn())
                _log.warn("ECIES decrypt error", dfe);
            return NO_CLOVES;
        } catch (Exception e) {
            _log.error("ECIES decrypt error", e);
            return NO_CLOVES;
        }
    }

    /**
     * NS only.
     *
     * @return decrypted data or null on failure
     * @since 0.9.46
     */
    private CloveSet x_decryptSlow(byte data[], PrivateKey targetPrivateKey,
                                   RatchetSKM keyManager) throws DataFormatException {
        CloveSet decrypted;
        EncType type = targetPrivateKey.getType();
        int minns = getMinNSSize(type);
        boolean isRouter = keyManager.getDestination() == null;
        if (data.length >= minns || (isRouter && data.length >= MIN_NS_N_SIZE)) {
            if (isRouter)
                decrypted = decryptNewSession_N(data, targetPrivateKey, keyManager);
            else
                decrypted = decryptNewSession(data, targetPrivateKey, keyManager);
            if (decrypted != null) {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptNewSession");
            } else {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
                // we'll get this a lot on muxed SKM
                if (_log.shouldInfo())
                    _log.info("Decrypt fail NS");
            }
        } else {
            decrypted = null;
            if (_log.shouldDebug())
                _log.debug("ECIES decrypt fail, too small for NS: " + data.length + " bytes, min is " +
                           minns + " for type " + type);
        }
        return decrypted;
    }

    /**
     * @throws IllegalArgumentException if unsupported
     * @since 0.9.67
     */
    private static void checkType(EncType type) {
        switch(type) {
          case ECIES_X25519:
          case MLKEM512_X25519:
          case MLKEM768_X25519:
          case MLKEM1024_X25519:
              return;
          default:
              throw new IllegalArgumentException("Unsupported key type " + type);
        }
    }

    /**
     * @since 0.9.67
     */
    private static String getNoisePattern(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return HandshakeState.PATTERN_ID_IK;
          case MLKEM512_X25519:
              return HandshakeState.PATTERN_ID_IKHFS_512;
          case MLKEM768_X25519:
              return HandshakeState.PATTERN_ID_IKHFS_768;
          case MLKEM1024_X25519:
              return HandshakeState.PATTERN_ID_IKHFS_1024;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * @since 0.9.67
     */
    private static KeyFactory getHybridKeyFactory(EncType type) {
        switch(type) {
          case MLKEM512_X25519:
              return MLKEM.MLKEM512KeyFactory;
          case MLKEM768_X25519:
              return MLKEM.MLKEM768KeyFactory;
          case MLKEM1024_X25519:
              return MLKEM.MLKEM1024KeyFactory;
          default:
              return null;
        }
    }

    /**
     * @since 0.9.67
     */
    private static int getMinNSSize(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return MIN_NS_SIZE;
          case MLKEM512_X25519:
              return MIN_NS_MLKEM512_SIZE;
          case MLKEM768_X25519:
              return MIN_NS_MLKEM768_SIZE;
          case MLKEM1024_X25519:
              return MIN_NS_MLKEM1024_SIZE;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * @since 0.9.67
     */
    private static int getMinNSRSize(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return MIN_NSR_SIZE;
          case MLKEM512_X25519:
              return MIN_NSR_MLKEM512_SIZE;
          case MLKEM768_X25519:
              return MIN_NSR_MLKEM768_SIZE;
          case MLKEM1024_X25519:
              return MIN_NSR_MLKEM1024_SIZE;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * @since 0.9.67
     */
    private static Set<EncType> getEncTypeSet(EncType type) {
        switch(type) {
          case ECIES_X25519:
              return LeaseSetKeys.SET_EC;
          case MLKEM512_X25519:
              return LeaseSetKeys.SET_PQ1;
          case MLKEM768_X25519:
              return LeaseSetKeys.SET_PQ2;
          case MLKEM1024_X25519:
              return LeaseSetKeys.SET_PQ3;
          default:
              throw new IllegalArgumentException("No pattern for " + type);
        }
    }

    /**
     * scenario 1: New Session Message
     *
     * Begin with 80 bytes, ECIES encrypted, containing:
     * <pre>
     *  - 32 byte Elligator2 key
     *  - 32 byte static key
     *  - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     *  - payload (7 bytes minimum for DateTime block)
     *  - 16 byte MAC
     * </pre>
     *
     * @param data 96 bytes minimum
     * @return null if decryption fails
     */
    private CloveSet decryptNewSession(byte data[], PrivateKey targetPrivateKey, RatchetSKM keyManager)
                                     throws DataFormatException {
        // Elg2
        byte[] xx = new byte[KEYLEN];
        System.arraycopy(data, 0, xx, 0, KEYLEN);
        // decode corrupts last byte, save for restore below
        byte xx31 = xx[KEYLEN - 1];
        PublicKey pk = Elligator2.decode(xx);
        if (pk == null) {
            // very unlikely
            if (_log.shouldDebug())
                _log.debug("Elg2 decode fail NS");
            data[KEYLEN - 1] = xx31;
            return null;
        }
        // fast MSB check for key < 2^255
        if ((pk.getData()[KEYLEN - 1] & 0x80) != 0) {
            if (_log.shouldDebug())
                _log.debug("Bad PK decode fail NS");
            data[KEYLEN - 1] = xx31;
            return null;
        }

        // rewrite in place, must restore below on failure
        System.arraycopy(pk.getData(), 0, data, 0, KEYLEN);

        HandshakeState state;
        EncType type = targetPrivateKey.getType();
        try {
            String pattern = getNoisePattern(type);
            // Bob does not need a key factory
            //state = new HandshakeState(pattern, HandshakeState.RESPONDER, _edhThread, getHybridKeyFactory(type));
            state = new HandshakeState(pattern, HandshakeState.RESPONDER, _edhThread);
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getLocalKeyPair().setKeys(targetPrivateKey.getData(), 0,
                                        targetPrivateKey.toPublic().getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before decrypt new session (" + data.length + " bytes) " + state);

        int payloadlen = data.length - (KEYLEN + KEYLEN + MACLEN + MACLEN);
        DHState hyb = state.getRemoteHybridKeyPair();
        if (hyb != null) {
            payloadlen -= hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] payload = new byte[payloadlen];
        try {
            state.readMessage(data, 0, data.length, payload, 0);
        } catch (GeneralSecurityException gse) {
            // we'll get this a lot on muxed SKM
            // logged at INFO in caller
            if (_log.shouldDebug())
                _log.debug("Decrypt fail NS " + data.length + " bytes, state at failure: " + state, gse);
            // restore original data for subsequent ElG attempt
            System.arraycopy(xx, 0, data, 0, KEYLEN - 1);
            data[KEYLEN - 1] = xx31;
            state.destroy();
            return null;
        }
        // bloom filter here based on ephemeral key
        // or should we do it based on apparent elg2-encoded key
        // at the very top, to prevent excess DH resource usage?
        // But that would put everything in the bloom filter.
        if (keyManager.isDuplicate(pk)) {
            if (_log.shouldWarn())
                _log.warn("Dup eph. key in IB NS: " + pk);
            state.destroy();
            return NO_CLOVES;
        }

        // payload
        if (payloadlen == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("Zero length payload in NS");
            state.destroy();
            return NO_CLOVES;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, true);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB NS");
        } catch (DataFormatException e) {
            state.destroy();
            throw e;
        } catch (Exception e) {
            state.destroy();
            throw new DataFormatException("NS payload error", e);
        }

        if (pc.datetime == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("No datetime block in IB NS");
            state.destroy();
            return NO_CLOVES;
        }

        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No garlic block in NS payload");
            state.destroy();
            return NO_CLOVES;
        }

        byte[] alicePK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(alicePK, 0);
        if (_log.shouldDebug()) {
            _log.debug("NS decrypt success from PK " + Base64.encode(alicePK));
            _log.debug("State after decrypt new session: " + state);
        }
        if (Arrays.equals(alicePK, NULLPK)) {
            state.destroy();
        } else {
            // tell the SKM
            PublicKey alice = new PublicKey(type, alicePK);
            keyManager.createSession(alice, null, state, null);
            setResponseTimerNS(alice, pc.cloveSet, keyManager);
        }

        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        return rv;
    }

    /**
     * scenario 1A: New Session Message Anonymous
     *
     * <pre>
     *  - 32 byte ephemeral key (NOT Elligator2)
     *  - payload (7 bytes minimum for DateTime block)
     *  - 16 byte MAC
     * </pre>
     *
     * @param data 55 bytes minimum
     * @return null if decryption fails
     * @since 0.9.49
     */
    private CloveSet decryptNewSession_N(byte data[], PrivateKey targetPrivateKey, RatchetSKM keyManager)
                                     throws DataFormatException {
        // fast MSB check for key < 2^255
        if ((data[KEYLEN - 1] & 0x80) != 0) {
            if (_log.shouldDebug())
                _log.debug("Bad PK N");
            return null;
        }

        HandshakeState state;
        try {
            state = new HandshakeState(HandshakeState.PATTERN_ID_N_NO_RESPONSE, HandshakeState.RESPONDER, _context.commSystem().getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getLocalKeyPair().setKeys(targetPrivateKey.getData(), 0,
                                        targetPrivateKey.toPublic().getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before decrypt new session: " + state);

        int payloadlen = data.length - (KEYLEN + MACLEN);
        byte[] payload = new byte[payloadlen];
        try {
            state.readMessage(data, 0, data.length, payload, 0);
        } catch (GeneralSecurityException gse) {
            // we'll get this a lot from very old routers
            // logged at INFO in caller
            if (_log.shouldDebug())
                _log.debug("Decrypt fail N, state at failure: " + state, gse);
            state.destroy();
            return null;
        }
        // bloom filter here based on ephemeral key
        byte[] xx = new byte[KEYLEN];
        System.arraycopy(data, 0, xx, 0, KEYLEN);
        PublicKey pk = new PublicKey(EncType.ECIES_X25519, xx);
        if (keyManager.isDuplicate(pk)) {
            if (_log.shouldWarn())
                _log.warn("Dup eph. key in IB N: " + pk);
            state.destroy();
            return NO_CLOVES;
        }

        // payload
        if (payloadlen == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("Zero length payload in N");
            state.destroy();
            return NO_CLOVES;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, true);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB N");
        } catch (DataFormatException e) {
            state.destroy();
            throw e;
        } catch (Exception e) {
            state.destroy();
            throw new DataFormatException("N payload error", e);
        }

        if (pc.datetime == 0) {
            // disallowed, datetime block required
            if (_log.shouldWarn())
                _log.warn("No datetime block in IB N");
            state.destroy();
            return NO_CLOVES;
        }

        if (pc.cloveSet.isEmpty()) {
            // this is legal ?
            if (_log.shouldDebug())
                _log.debug("No garlic block in N payload");
            state.destroy();
            return NO_CLOVES;
        }

        if (_log.shouldDebug()) {
            _log.debug("N decrypt success");
            //_log.debug("State after decrypt new session: " + state);
        }
        state.destroy();

        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        return rv;
    }

    /**
     * scenario 2: New Session Reply Message
     *
     * Begin with 56 bytes, containing:
     * <pre>
     *  - 8 byte SessionTag
     *  - 32 byte Elligator2 key
     *  - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @param tag 8 bytes, same as first 8 bytes of data
     * @param data 56 bytes minimum
     * @param state will be cloned here
     * @return null if decryption fails
     */
    private CloveSet decryptNewSessionReply(byte[] tag, byte[] data, HandshakeState oldState, RatchetSKM keyManager)
                                          throws DataFormatException {
        HandshakeState state;
        try {
            state = oldState.clone();
        } catch (CloneNotSupportedException e) {
            if (_log.shouldWarn())
                _log.warn("ECIES decrypt fail: clone()", e);
            return null;
        }

        // part 1 - handshake
        byte[] yy = new byte[KEYLEN];
        System.arraycopy(data, TAGLEN, yy, 0, KEYLEN);
        // decode corrupts last byte, save for restore below
        byte yy31 = yy[KEYLEN - 1];
        PublicKey k = Elligator2.decode(yy);
        if (k == null) {
            // very unlikely
            if (_log.shouldDebug())
                _log.debug("Elg2 decode fail NSR");
            data[TAGLEN + KEYLEN - 1] = yy31;
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State before decrypt new session reply: " + state);
        // rewrite in place, must restore below on failure
        System.arraycopy(k.getData(), 0, data, TAGLEN, KEYLEN);
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after mixhash tag before decrypt new session reply: " + state);
        int tmplen = 48;
        DHState hyb = state.getRemoteHybridKeyPair();
        if (hyb != null) {
            tmplen += hyb.getPublicKeyLength() + MACLEN;
        }
        try {
            state.readMessage(data, 8, tmplen, ZEROLEN, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Decrypt fail NSR part 1", gse);
                if (_log.shouldDebug())
                    _log.debug("State at failure: " + state);
            }
            // restore original data for subsequent ElG attempt
            // unlikely since we already matched the tag
            System.arraycopy(yy, 0, data, TAGLEN, KEYLEN - 1);
            data[TAGLEN + KEYLEN - 1] = yy31;
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State after decrypt new session reply: " + state);

        // split()
        // Noise does it too but it trashes the keys
        SplitKeys split = new SplitKeys(state, _hkdf);
        CipherStatePair ckp = state.split();
        CipherState rcvr = ckp.getReceiver();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(split.k_ba.getData(), ZEROLEN, INFO_6, encpayloadkey);
        rcvr.initializeKey(encpayloadkey, 0);
        int off = TAGLEN + KEYLEN + MACLEN;
        int plen = data.length - (TAGLEN + KEYLEN + MACLEN + MACLEN);
        if (hyb != null) {
            int len = hyb.getPublicKeyLength() + MACLEN;
            off += len;
            plen -= len;
        }
        byte[] payload = new byte[plen];
        try {
            rcvr.decryptWithAd(hash, data, off, payload, 0, plen + MACLEN);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Decrypt fail NSR part 2", gse);
                if (_log.shouldDebug())
                    _log.debug("State at failure: " + state);
            }
            state.destroy();
            return NO_CLOVES;
        }

        PLCallback pc;
        if (payload.length == 0) {
            // this is legal
            pc = null;
            if (_log.shouldDebug())
                _log.debug("Zero length payload in IB NSR");
        } else {
            pc = new PLCallback();
            try {
                int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, false);
                if (_log.shouldDebug())
                    _log.debug("Processed " + blocks + " blocks in IB NSR");
            } catch (DataFormatException e) {
                state.destroy();
                throw e;
            } catch (Exception e) {
                state.destroy();
                throw new DataFormatException("NSR payload error", e);
            }
        }

        byte[] bobPK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(bobPK, 0);
        if (_log.shouldDebug())
            _log.debug("NSR decrypt success from PK " + Base64.encode(bobPK));
        if (Arrays.equals(bobPK, NULLPK)) {
            // TODO
            if (_log.shouldWarn())
                _log.warn("NSR reply to zero static key NS");
            state.destroy();
            return NO_CLOVES;
        }

        // tell the SKM
        PublicKey bob = new PublicKey(keyManager.getType(), bobPK);
        keyManager.updateSession(bob, oldState, state, null, split);

        if (pc == null)
            return NO_CLOVES;
        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No garlic block in NSR payload");
            return NO_CLOVES;
        }
        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        setResponseTimer(bob, pc.cloveSet, keyManager);
        return rv;
    }

    /**
     * scenario 3: Existing Session Message
     *
     * <pre>
     *  - 8 byte SessionTag
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * If anything doesn't match up in decryption, it returns null
     *
     * @param tag 8 bytes for ad, same as first 8 bytes of data
     * @param data 24 bytes minimum, first 8 bytes will be skipped
     * @param keyManager for ack callbacks
     * @return decrypted data or null on failure
     *
     */
    private CloveSet decryptExistingSession(byte[] tag, byte[] data, SessionKeyAndNonce key,
                                            PrivateKey targetPrivateKey, RatchetSKM keyManager)
                                          throws DataFormatException {
        int nonce = key.getNonce();
        // this decrypts in-place
        boolean ok = decryptAEADBlock(tag, data, TAGLEN, data.length - TAGLEN, key, nonce);
        if (!ok) {
            if (_log.shouldWarn())
                _log.warn("Decrypt of ES failed");
            return null;
        }
        if (data.length == TAGLEN + MACLEN) {
            // legal?
            if (_log.shouldWarn())
                _log.warn("Zero length payload in ES");
            return NO_CLOVES;
        }
        PublicKey remote = key.getRemoteKey();
        PLCallback pc = new PLCallback(keyManager, remote);
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, data, TAGLEN, data.length - (TAGLEN + MACLEN), false);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB ES");
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("ES payload error", e);
        }
        boolean shouldAck = false;
        if (pc.nextKeys != null) {
            for (NextSessionKey nextKey : pc.nextKeys) {
                keyManager.nextKeyReceived(remote, nextKey);
                if (!nextKey.isReverse())
                    shouldAck = true;
            }
        }
        if (pc.ackRequested) {
            keyManager.ackRequested(remote, key.getID(), nonce);
            shouldAck = true;
        }
        if (shouldAck) {
            setResponseTimer(remote, pc.cloveSet, keyManager);
        }
        if (pc.cloveSet.isEmpty()) {
            // this is legal
            if (_log.shouldDebug())
                _log.debug("No garlic block in ES payload");
            return NO_CLOVES;
        }
        int num = pc.cloveSet.size();
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        return rv;
    }

    /*
     * With optional AD.
     * Decrypts IN PLACE. Decrypted data will be at encrypted[offset:offset + len - 16].
     *
     * @param ad may be null
     * @return success
     */
    private boolean decryptAEADBlock(byte[] ad, byte encrypted[], int offset, int encryptedLen, SessionKey key,
                                    long n) throws DataFormatException {
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(n);
        try {
            // this is safe to do in-place, it checks the mac before starting decryption
            chacha.decryptWithAd(ad, encrypted, offset, encrypted, offset, encryptedLen);
        } catch (GeneralSecurityException e) {
            if (_log.shouldWarn())
                _log.warn("Unable to decrypt AEAD block", e);
            return false;
        } finally {
            chacha.destroy();
        }
        return true;
    }


    //// end decrypt, start encrypt ////


    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     * No new session key
     * This is the one called from GarlicMessageBuilder and is the primary entry point.
     *
     * @param target public key to which the data should be encrypted. 
     * @param to ignored if priv is null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     * @param keyManager ignored if priv is null
     * @param callback may be null, if non-null an ack will be requested (except NS/NSR),
     *                 ignored if priv is null
     * @return encrypted data or null on failure
     *
     */
    public byte[] encrypt(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                          RatchetSKM keyManager,
                          ReplyCallback callback) {
        try {
            return x_encrypt(cloves, target, to, priv, keyManager, callback);
        } catch (Exception e) {
            _log.error("ECIES encrypt error", e);
            return null;
        }
    }

    /**
     * @param to ignored if priv is null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     * @param keyManager ignored if priv is null
     * @param callback may be null, ignored if priv is null
     * @return encrypted data or null on failure
     */
    private byte[] x_encrypt(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                             RatchetSKM keyManager,
                             ReplyCallback callback) {
        checkType(target.getType());
        if (Arrays.equals(target.getData(), NULLPK)) {
            if (_log.shouldWarn())
                _log.warn("Zero static key target");
            return null;
        }
        if (priv == null) {
            if (_log.shouldDebug())
                _log.debug("Encrypting as N to " + target);
            // N-in-IK, unused
            //return encryptNewSession(cloves, target, null, null, null, null);
            return encryptNewSession(cloves, target);
        }
        RatchetEntry re = keyManager.consumeNextAvailableTag(target);
        if (re == null) {
            if (_log.shouldDebug())
                _log.debug("Encrypting as NS to " + target);
            return encryptNewSession(cloves, target, to, priv, keyManager, callback);
        }

        HandshakeState state = re.key.getHandshakeState();
        if (state != null) {
            try {
                state = state.clone();
            } catch (CloneNotSupportedException e) {
                if (_log.shouldWarn())
                    _log.warn("ECIES encrypt fail: clone()", e);
                return null;
            }
            if (_log.shouldDebug())
                _log.debug("Encrypting as NSR to " + target + " with tag " + re.tag.toBase64());
// trash old state if this throws IAE???
            return encryptNewSessionReply(cloves, target, state, re.tag, keyManager, callback);
        }
        byte rv[] = encryptExistingSession(cloves, target, re, callback, keyManager);
        if (rv == null) {
            if (_log.shouldWarn())
                _log.warn("ECIES ES encrypt fail");
        } else if (_log.shouldDebug())
            _log.debug("Encrypting as ES to " + target + " with key " + re.key + " and tag " + re.tag.toBase64() +
                       " fwd key: " + re.nextForwardKey +
                       " rev key: " + re.nextReverseKey +
                       "; " + rv.length + " bytes");
        return rv;
    }

    /**
     * scenario 1: New Session Message
     *
     * Begin with 80 bytes, ECIES encrypted, containing:
     * <pre>
     *  - 32 byte Elligator2 key
     *  - 32 byte static key
     *  - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @param to ignored if priv is null
     * @param priv local private key to encrypt with, from the leaseset
     *             may be null for anonymous (N-in-IK)
     * @param keyManager ignored if priv is null
     * @param callback may be null, ignored if priv is null
     * @return encrypted data or null on failure
     */
    private byte[] encryptNewSession(CloveSet cloves, PublicKey target, Destination to, PrivateKey priv,
                                     RatchetSKM keyManager,
                                     ReplyCallback callback) {
        EncType type = target.getType();
        if (type != priv.getType())
            throw new IllegalArgumentException("Key mismatch " + target + ' ' + priv);
        HandshakeState state;
        try {
            String pattern = getNoisePattern(target.getType());
            state = new HandshakeState(pattern, HandshakeState.INITIATOR, _edhThread, getHybridKeyFactory(type));
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getRemotePublicKey().setPublicKey(target.getData(), 0);
        if (priv != null) {
            state.getLocalKeyPair().setKeys(priv.getData(), 0,
                                            priv.toPublic().getData(), 0);
        } else {
            state.getLocalKeyPair().setKeys(NULLPK, 0, NULLPK, 0);
        }
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session: " + state);

        byte[] payload = createPayload(cloves, cloves.getExpiration(), NS_OVERHEAD);

        int enclen = KEYLEN + KEYLEN + MACLEN + payload.length + MACLEN;
        DHState hyb = state.getLocalHybridKeyPair();
        if (hyb != null) {
            enclen += hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] enc = new byte[enclen];
        try {
            state.writeMessage(enc, 0, payload, 0, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NS", gse);
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("Encrypted NS: " + enc.length + " bytes, state: " + state);

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NS state");
            state.destroy();
            return null;
        }
        eph.getEncodedPublicKey(enc, 0);
        if (_log.shouldDebug())
            _log.debug("Elligator2 encoded eph. key: " + Base64.encode(enc, 0, 32));

        if (priv != null) {
            // tell the SKM
            keyManager.createSession(target, to, state, callback);
        } else {
            state.destroy();
        }
        return enc;
    }


    /**
     * scenario 1A: New Session Message from an anonymous source.
     *
     * <pre>
     *  - 32 byte ephemeral key (NOT Elligator2)
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @return encrypted data or null on failure
     * @since 0.9.49
     */
    private byte[] encryptNewSession(CloveSet cloves, PublicKey target) {
        HandshakeState state;
        try {
            state = new HandshakeState(HandshakeState.PATTERN_ID_N_NO_RESPONSE, HandshakeState.INITIATOR, _context.commSystem().getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getRemotePublicKey().setPublicKey(target.getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session: " + state);

        byte[] payload = createPayload(cloves, cloves.getExpiration(), NS_N_OVERHEAD);

        byte[] enc = new byte[KEYLEN + payload.length + MACLEN];
        try {
            state.writeMessage(enc, 0, payload, 0, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail N", gse);
            state.destroy();
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("Encrypted N: " + enc.length + " bytes, state: " + state);

        state.destroy();
        return enc;
    }


    /**
     * scenario 2: New Session Reply Message
     *
     * Begin with 56 bytes, containing:
     * <pre>
     *  - 8 byte SessionTag
     *  - 32 byte Elligator2 key
     *  - 16 byte MAC
     * </pre>
     * And then the data:
     * <pre>
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @param state must have already been cloned
     * @param callback may be null
     * @return encrypted data or null on failure
     */
    private byte[] encryptNewSessionReply(CloveSet cloves, PublicKey target, HandshakeState state,
                                          RatchetSessionTag currentTag, RatchetSKM keyManager,
                                          ReplyCallback callback) {
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session reply: " + state);
        byte[] tag = currentTag.getData();
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after mixhash tag before encrypt new session reply: " + state);

        byte[] payload = createPayload(cloves, 0, NSR_OVERHEAD);

        // part 1 - tag and empty payload
        int enclen = TAGLEN + KEYLEN + MACLEN + payload.length + MACLEN;
        DHState hyb = state.getLocalHybridKeyPair();
        if (hyb != null) {
            enclen += hyb.getPublicKeyLength() + MACLEN;
        }
        byte[] enc = new byte[enclen];
        System.arraycopy(tag, 0, enc, 0, TAGLEN);
        try {
            state.writeMessage(enc, TAGLEN, ZEROLEN, 0, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NSR part 1", gse);
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("Encrypted NSR: " + enc.length + " bytes, state: " + state);

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NSR state");
            return null;
        }
        eph.getEncodedPublicKey(enc, TAGLEN);

        // split()
        // Noise does it too but it trashes the keys
        SplitKeys split = new SplitKeys(state, _hkdf);
        CipherStatePair ckp = state.split();
        CipherState sender = ckp.getSender();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(split.k_ba.getData(), ZEROLEN, INFO_6, encpayloadkey);
        sender.initializeKey(encpayloadkey, 0);
        int off = TAGLEN + KEYLEN + MACLEN;
        if (hyb != null) {
            off += hyb.getPublicKeyLength() + MACLEN;
        }
        try {
            sender.encryptWithAd(hash, payload, 0, enc, off, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NSR part 2", gse);
            return null;
        }
        // tell the SKM
        keyManager.updateSession(target, null, state, callback, split);

        return enc;
    }

    /**
     * scenario 3: Existing Session Message
     *
     * <pre>
     *  - 8 byte SessionTag
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @param target only used if callback is non-null to register it
     * @return encrypted data or null on failure
     */
    private byte[] encryptExistingSession(CloveSet cloves, PublicKey target, RatchetEntry re,
                                          ReplyCallback callback,
                                          RatchetSKM keyManager) {
        boolean ackreq = callback != null || ACKREQ_IN_ES;
        byte rawTag[] = re.tag.getData();
        byte[] payload = createPayload(cloves, 0, ackreq, re.nextForwardKey, re.nextReverseKey, re.acksToSend, ES_OVERHEAD);
        SessionKeyAndNonce key = re.key;
        int nonce = key.getNonce();
        byte encr[] = encryptAEADBlock(rawTag, payload, key, nonce);
        if (encr != null) {
            System.arraycopy(rawTag, 0, encr, 0, TAGLEN);
            if (callback != null)
                keyManager.registerCallback(target, re.keyID, nonce, callback);
        }
        return encr;
    }

    /**
     * Create an Existing Session Message to an anonymous target
     * using the given session key and tag, for netdb DSM/DSRM replies.
     * Called from MessageWrapper.
     *
     * No datetime, no next key, no acks, no ack requests.
     * n=0, ad=null.
     *
     * <pre>
     *  - 8 byte SessionTag
     *  - payload
     *  - 16 byte MAC
     * </pre>
     *
     * @return encrypted data or null on failure
     * @since 0.9.46
     */
    public byte[] encrypt(CloveSet cloves, SessionKey key, RatchetSessionTag tag) {
        byte rawTag[] = tag.getData();
        byte[] payload = createPayload(cloves, 0, ES_OVERHEAD);
        byte encr[] = encryptAEADBlock(rawTag, payload, key, 0);
        if (encr != null) {
            System.arraycopy(rawTag, 0, encr, 0, TAGLEN);
        } else if (_log.shouldWarn()) {
            _log.warn("ECIES ES encrypt fail");
        }
        return encr;
    }

    /**
     * Encrypt the data to the target using the given key from an anonymous source,
     * for netdb lookups.
     * Called from MessageWrapper.
     *
     * @param target public key to which the data should be encrypted. 
     * @return encrypted data or null on failure
     * @since 0.9.48
     */
    public byte[] encrypt(CloveSet cloves, PublicKey target) {
        return encrypt(cloves, target, null, null, null, null);
    }

    /**
     * No ad
     */
/*
    private final byte[] encryptAEADBlock(byte data[], SessionKey key, long n) {
        return encryptAEADBlock(null, data, key, n);
    }
*/

    /**
     *
     * @param ad may be null
     * @return space will be left at beginning for ad (tag), null on error
     */
    private final byte[] encryptAEADBlock(byte[] ad, byte data[], SessionKey key, long n) {
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(n);
        int adsz = ad != null ? ad.length : 0;
        byte enc[] = new byte[adsz + data.length + MACLEN];
        try {
            chacha.encryptWithAd(ad, data, 0, enc, adsz, data.length);
        } catch (GeneralSecurityException e) {
            if (_log.shouldWarn())
                _log.warn("Unable to encrypt AEAD block", e);
            return null;
        } finally {
            chacha.destroy();
        }
        return enc;
    }

    static final PrivateKey doDH(PrivateKey privkey, PublicKey pubkey) {
        byte[] dh = new byte[KEYLEN];
        Curve25519.eval(dh, 0, privkey.getData(), pubkey.getData());
        return new PrivateKey(EncType.ECIES_X25519, dh);
    }

    /////////////////////////////////////////////////////////
    // payload stuff
    /////////////////////////////////////////////////////////

    private class PLCallback implements RatchetPayload.PayloadCallback {
        /** non null, may be empty */
        public final List<GarlicClove> cloveSet = new ArrayList<GarlicClove>(3);
        private final RatchetSKM skm;
        private final PublicKey remote;
        public long datetime;
        /** null or non-empty */
        public List<NextSessionKey> nextKeys;
        public boolean ackRequested;

        /**
         * NS/NSR
         */
        public PLCallback() {
            this(null, null);
        }
 
        /**
         * ES
         * @param keyManager only for ES, otherwise null
         * @param remoteKey only for ES, otherwise null
         * @since 0.9.46
         */
        public PLCallback(RatchetSKM keyManager, PublicKey remoteKey) {
            skm = keyManager;
            remote = remoteKey;
        }

        public void gotDateTime(long time) throws DataFormatException {
            if (_log.shouldDebug())
                _log.debug("Got DATE block: " + DataHelper.formatTime(time));
            if (datetime != 0)
                throw new DataFormatException("Multiple DATETIME blocks");
            datetime = time;
            long now = _context.clock().now();
            if (time < now - MAX_NS_AGE ||
                time > now + MAX_NS_FUTURE) {
                throw new DataFormatException("Excess clock skew in IB NS: " + DataHelper.formatTime(time));
            }
        }

        public void gotOptions(byte[] options, boolean isHandshake) {
            if (_log.shouldDebug())
                _log.debug("Got OPTIONS block length " + options.length);
            // TODO
        }

        public void gotGarlic(GarlicClove clove) {
            if (_log.shouldDebug())
                _log.debug("Got GARLIC block: " + clove);
            cloveSet.add(clove);
        }

        public void gotNextKey(NextSessionKey next) {
            if (_log.shouldDebug())
                _log.debug("Got NEXTKEY block: " + next);
            // could have both a forward and reverse.
            // shouldn't have two forwards or two reverses
            if (nextKeys == null)
                nextKeys = new ArrayList<NextSessionKey>(2);
            nextKeys.add(next);
        }

        public void gotAck(int id, int n) {
            if (_log.shouldDebug())
                _log.debug("Got ACK block: " + id + " / " + n);
            if (skm != null)
                skm.receivedACK(remote, id, n);
            else if (_log.shouldWarn())
                _log.warn("ACK in NS/NSR?");
        }

        public void gotAckRequest() {
            if (_log.shouldDebug())
                _log.debug("Got ACK REQUEST block");
            ackRequested = true;
        }

        public void gotTermination(int reason) {
            if (_log.shouldDebug())
                _log.debug("Got TERMINATION block, reason: " + reason);
            // TODO
        }

        public void gotPN(int pn) {
            if (_log.shouldDebug())
                _log.debug("Got PN block, pn: " + pn);
            // TODO
        }

        public void gotUnknown(int type, int len) {
            if (_log.shouldDebug())
                _log.debug("Got UNKNOWN block, type: " + type + " len: " + len);
        }

        public void gotPadding(int paddingLength, int frameLength) {
            if (_log.shouldDebug())
                _log.debug("Got PADDING block, len: " + paddingLength + " in frame len: " + frameLength);
        }
    }

    /**
     *  @param expiration if greater than zero, add a DateTime block
     *  @param overhead bytes to be added later, to assist in padding calculation
     *  @since 0.9.46
     */
    private byte[] createPayload(CloveSet cloves, long expiration, int overhead) {
        return createPayload(cloves, expiration, false, null, null, null, overhead);
    }

    // see below
    private static final int B1 = 944;
    private static final int B2 = 1936;
    private static final int B3 = 2932;

    /**
     *  @param expiration if greater than zero, add a DateTime block
     *  @param ackreq to request an ack, must be false for NS/NSR
     *  @param nextKey1 may be null
     *  @param nextKey2 may be null
     *  @param acksTOSend may be null
     *  @param overhead bytes to be added later, to assist in padding calculation
     */
    private byte[] createPayload(CloveSet cloves, long expiration,
                                 boolean ackreq, NextSessionKey nextKey1,
                                 NextSessionKey nextKey2, List<Integer> acksToSend,
                                 int overhead) {
        int count = cloves.getCloveCount();
        int numblocks = count + 1;
        if (expiration > 0)
            numblocks++;
        if (ackreq)
            numblocks++;
        if (nextKey1 != null)
            numblocks++;
        if (nextKey2 != null)
            numblocks++;
        if (acksToSend != null)
            numblocks++;
        int len = 0;
        List<Block> blocks = new ArrayList<Block>(numblocks);
        if (expiration > 0) {
            Block block = new DateTimeBlock(expiration);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (nextKey1 != null) {
            Block block = new NextKeyBlock(nextKey1);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (nextKey2 != null) {
            Block block = new NextKeyBlock(nextKey2);
            blocks.add(block);
            len += block.getTotalLength();
        }
        for (int i = 0; i < count; i++) {
            GarlicClove clove = cloves.getClove(i);
            Block block = new GarlicBlock(clove);
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (ackreq) {
            // put after the cloves so recipient has any LS garlic
            Block block = new AckRequestBlock();
            blocks.add(block);
            len += block.getTotalLength();
        }
        if (acksToSend != null) {
            Block block = new AckBlock(acksToSend);
            blocks.add(block);
            len += block.getTotalLength();
        }

        // Padding
        // Key lengths we're trying to not exceed:
        // 944 for one tunnel message
        // 1936 for two tunnel messages
        // 2932 for three tunnel messages
        // See streaming ConnectionOptions for the math
        int fixedpad;
        int randompad;
        int totlen = len + overhead;
        if ((totlen > B1 - BHLEN && totlen <= B1) ||
            (totlen > B2 - BHLEN && totlen <= B2) ||
            (totlen > B3 - BHLEN && totlen <= B3)) {
            // no room for block
            fixedpad = 0;
            randompad = 0;
        } else if (totlen > B1 - BHLEN - MAXPAD && totlen <= B1 - BHLEN) {
            // fill it up
            fixedpad = B1 - BHLEN - totlen;
            randompad = 0;
        } else if (totlen > B2 - BHLEN - MAXPAD && totlen <= B2 - BHLEN) {
            // fill it up
            fixedpad = B2 - BHLEN - totlen;
            randompad = 0;
        } else if (totlen > B3 - BHLEN - MAXPAD && totlen <= B3 - BHLEN) {
            // fill it up
            fixedpad = B3 - BHLEN - totlen;
            randompad = 0;
        } else {
            // we're not close to a boundary, just do random
            fixedpad = 0;
            randompad = MAXPAD;
        }
        if (fixedpad > 0 || randompad > 0) {
            int padlen;
            if (fixedpad > 0) {
                padlen = fixedpad;
            } else {
                padlen = _context.random().nextInt(randompad);
                if (overhead == NS_OVERHEAD &&
                    ((totlen + BHLEN + padlen) & 0x0f) == 2) {
                    // do a favor for muxed decrypt
                    if (padlen > 0)
                        padlen--;
                    else
                        padlen++;
                }
            }
            // zeros
            Block block = new PaddingBlock(padlen);
            blocks.add(block);
            len += block.getTotalLength();
        }
        byte[] payload = new byte[len];
        int payloadlen = RatchetPayload.writePayload(payload, 0, blocks);
        if (payloadlen != len)
            throw new IllegalStateException("payload size mismatch");
        return payload;
    }

    /*
     * Set a timer for a ratchet-layer reply if the application does not respond.
     * NS only. CloveSet must include a LS for validation.
     *
     * @param skm must have non-null destination
     * @since 0.9.46
     */
    private void setResponseTimerNS(PublicKey from, List<GarlicClove> cloveSet, RatchetSKM skm) {
        Destination us = skm.getDestination();
        // temp for router SKM
        if (us == null)
            return;
        for (GarlicClove clove : cloveSet) {
            I2NPMessage msg = clove.getData();
            if (msg.getType() != DatabaseStoreMessage.MESSAGE_TYPE)
                continue;
            DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
            DatabaseEntry entry = dsm.getEntry();
            if (entry.getType() != DatabaseEntry.KEY_TYPE_LS2)
                continue;
            LeaseSet2 ls2 = (LeaseSet2) entry;
            // i2pd bug?
            if (ls2.getLeaseCount() == 0)
                return;
            if (!ls2.isCurrent(Router.CLOCK_FUDGE_FACTOR))
                continue;
            PublicKey pk = ls2.getEncryptionKey(getEncTypeSet(from.getType()));
            if (!from.equals(pk))
                continue;
            if (!ls2.verifySignature())
                continue;
            // OK, we have a valid place to send the reply
            Destination d = ls2.getDestination();
            if (_log.shouldInfo())
                _log.info("Validated NS sender: " + d.toBase32());
            ACKTimer ack = new ACKTimer(_context, us, d);
            if (skm.registerTimer(from, d, ack)) {
                ack.schedule(1000);
            }
            return;
        }
        if (_log.shouldInfo())
            _log.info("Unvalidated NS sender: " + from);
    }

    /*
     * Set a timer for a ratchet-layer reply if the application does not respond.
     * NSR/ES only.
     *
     * @param skm must have non-null destination
     * @since 0.9.47
     */
    private void setResponseTimer(PublicKey from, List<GarlicClove> cloveSet, RatchetSKM skm) {
        Destination d = skm.getDestination(from);
        if (d != null) {
            Destination us = skm.getDestination();
            // temp for router SKM
            if (us == null)
                return;
            ACKTimer ack = new ACKTimer(_context, us, d);
            if (skm.registerTimer(from, null, ack)) {
                ack.schedule(1000);
            }
        } else {
            // we didn't get a LS in the original NS, but maybe we have one now
            if (_log.shouldInfo())
                _log.info("No full dest to ack to, looking for LS from: " + from);
            setResponseTimerNS(from, cloveSet, skm);
        }
    }


/****
    public static void main(String args[]) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("i2p.dummyClientFacade", "true");
        props.setProperty("i2p.dummyNetDb", "true");
        props.setProperty("i2p.vmCommSystem", "true");
        props.setProperty("i2p.dummyPeerManager", "true");
        props.setProperty("i2p.dummyTunnelManager", "true");
        RouterContext ctx = new RouterContext(null, props);
        ctx.initAll();
        ECIESAEADEngine e = new ECIESAEADEngine(ctx);
        RatchetSKM rskm = new RatchetSKM(ctx, new Destination());
        net.i2p.crypto.KeyPair kp = ctx.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
        PublicKey pubKey = kp.getPublic();
        PrivateKey privKey = kp.getPrivate();
        kp = ctx.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
        PublicKey pubKey2 = kp.getPublic();
        PrivateKey privKey2 = kp.getPrivate();

        Destination dest = new Destination();
        GarlicClove clove = new GarlicClove(ctx);
        net.i2p.data.i2np.DataMessage msg = new net.i2p.data.i2np.DataMessage(ctx);
        byte[] orig = DataHelper.getUTF8("blahblahblah");
        msg.setData(orig);
        clove.setData(msg);
        clove.setCertificate(Certificate.NULL_CERT);
        clove.setCloveId(0);
        clove.setExpiration(System.currentTimeMillis() + 10000);
        clove.setInstructions(net.i2p.data.i2np.DeliveryInstructions.LOCAL);
        GarlicClove[] arr = new GarlicClove[1];
        arr[0] = clove;
        CloveSet cs = new CloveSet(arr, Certificate.NULL_CERT, clove.getCloveId(), clove.getExpiration());

        // IK test
        byte[] encrypted = e.encrypt(cs, pubKey, dest, privKey2, rskm, null);
        System.out.println("IK Encrypted:\n" + net.i2p.util.HexDump.dump(encrypted));

        CloveSet cs2 = e.decrypt(encrypted, privKey, rskm);
        if (cs2 == null) {
            System.out.println("IK DECRYPT FAIL");
            return;
        }
        System.out.println("IK Decrypted: " + cs);
        GarlicClove clove2 = cs2.getClove(0);
        net.i2p.data.i2np.DataMessage msg2 = (net.i2p.data.i2np.DataMessage) clove2.getData();
        byte[] decrypted = msg2.getData();
        if (Arrays.equals(orig, decrypted)) {
            System.out.println("IK Test passed");
        } else {
            System.out.println("IK Test FAILED: " + new String(decrypted));
        }

        // N test
        rskm = new RatchetSKM(ctx);
        encrypted = e.encrypt(cs, pubKey);
        System.out.println("N Encrypted:\n" + net.i2p.util.HexDump.dump(encrypted));

        cs2 = e.decrypt(encrypted, privKey, rskm);
        if (cs2 == null) {
            System.out.println("N DECRYPT FAIL");
            return;
        }
        System.out.println("N Decrypted: " + cs);
        clove2 = cs2.getClove(0);
        msg2 = (net.i2p.data.i2np.DataMessage) clove2.getData();
        decrypted = msg2.getData();
        if (Arrays.equals(orig, decrypted)) {
            System.out.println("N Test passed");
        } else {
            System.out.println("N Test FAILED: " + new String(decrypted));
        }
    }
****/
}
