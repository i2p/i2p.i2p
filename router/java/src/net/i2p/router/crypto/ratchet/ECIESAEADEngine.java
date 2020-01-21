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
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.GarlicClove;
import static net.i2p.router.crypto.ratchet.RatchetPayload.*;
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
    private final HKDF _hkdf;
    private final Elg2KeyFactory _edhThread;
    private boolean _isRunning;

    private static final byte[] ZEROLEN = new byte[0];
    private static final int TAGLEN = 8;
    private static final int MACLEN = 16;
    private static final int KEYLEN = 32;
    private static final int BHLEN = RatchetPayload.BLOCK_HEADER_SIZE;  // 3
    private static final int DATETIME_SIZE = BHLEN + 4; // 7
    private static final int MIN_NS_SIZE = KEYLEN + KEYLEN + MACLEN + DATETIME_SIZE + MACLEN; // 103
    private static final int MIN_NSR_SIZE = TAGLEN + KEYLEN + MACLEN; // 56
    private static final int MIN_ES_SIZE = TAGLEN + MACLEN; // 24
    private static final int MIN_ENCRYPTED_SIZE = MIN_ES_SIZE;
    private static final byte[] NULLPK = new byte[KEYLEN];
    private static final int MAXPAD = 16;

    private static final String INFO_0 = "SessionReplyTags";
    private static final String INFO_6 = "AttachPayloadKDF";

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
            throw dfe;
        } catch (Exception e) {
            _log.error("ECIES decrypt error", e);
            return null;
        }
    }

    private CloveSet x_decrypt(byte data[], PrivateKey targetPrivateKey,
                               RatchetSKM keyManager) throws DataFormatException {
        if (targetPrivateKey.getType() != EncType.ECIES_X25519)
            throw new IllegalArgumentException();
        if (data == null) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Null data being decrypted?");
            return null;
        }
        if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte tag[] = new byte[TAGLEN];
        System.arraycopy(data, 0, tag, 0, TAGLEN);
        RatchetSessionTag st = new RatchetSessionTag(tag);
        SessionKeyAndNonce key = keyManager.consumeTag(st);
        CloveSet decrypted;
        final boolean shouldDebug = _log.shouldDebug();
        if (key != null) {
            HandshakeState state = key.getHandshakeState();
            if (state == null) {
                if (shouldDebug)
                    _log.debug("Decrypting ES with tag: " + st.toBase64() + ": key: " + key.toBase64() + ": " + data.length + " bytes");
                decrypted = decryptExistingSession(tag, data, key, targetPrivateKey);
            } else if (data.length >= MIN_NSR_SIZE) {
                if (shouldDebug)
                    _log.debug("Decrypting NSR with tag: " + st.toBase64() + ": key: " + key.toBase64() + ": " + data.length + " bytes");
                decrypted = decryptNewSessionReply(tag, data, state, keyManager);
            } else {
                decrypted = null;
                if (_log.shouldWarn())
                    _log.warn("ECIES decrypt fail, tag found but no state and too small for NSR: " + data.length + " bytes");
            }
            if (decrypted != null) {
///
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptExistingSession");
            } else {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
                if (_log.shouldWarn()) {
                    _log.warn("ECIES decrypt fail: known tag [" + st + "], failed decrypt");
                }
            }
        } else if (data.length >= MIN_NS_SIZE) {
            if (shouldDebug) _log.debug("IB Tag " + st + " not found, trying NS decrypt");
            decrypted = decryptNewSession(data, targetPrivateKey, keyManager);
            if (decrypted != null) {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptNewSession");
            } else {
                _context.statManager().updateFrequency("crypto.eciesAEAD.decryptFailed");
                if (_log.shouldWarn())
                    _log.warn("ECIES decrypt fail as new session");
            }
        } else {
            decrypted = null;
            if (_log.shouldWarn())
                _log.warn("ECIES decrypt fail, tag not found and too small for NS: " + data.length + " bytes");
        }

        return decrypted;
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
        HandshakeState state;
        try {
            state = new HandshakeState(HandshakeState.PATTERN_ID_IK, HandshakeState.RESPONDER, _edhThread);
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getLocalKeyPair().setPublicKey(targetPrivateKey.toPublic().getData(), 0);
        state.getLocalKeyPair().setPrivateKey(targetPrivateKey.getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before decrypt new session: " + state);

        // Elg2
        byte[] tmp = new byte[KEYLEN];
        System.arraycopy(data, 0, tmp, 0, KEYLEN);
        PublicKey pk = Elligator2.decode(tmp);
        if (pk == null) {
            if (_log.shouldWarn())
                _log.warn("Elg2 decode fail NS");
            return null;
        }
        System.arraycopy(pk.getData(), 0, data, 0, KEYLEN);

        int payloadlen = data.length - (KEYLEN + KEYLEN + MACLEN + MACLEN);
        byte[] payload = new byte[payloadlen];
        try {
            state.readMessage(data, 0, data.length, payload, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Decrypt fail NS", gse);
                if (_log.shouldDebug())
                    _log.debug("State at failure: " + state);
            }
            return null;
        }

        byte[] bobPK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(bobPK, 0);
        if (_log.shouldDebug()) {
            _log.debug("NS decrypt success from PK " + Base64.encode(bobPK));
            _log.debug("State after decrypt new session: " + state);
        }
        if (Arrays.equals(bobPK, NULLPK)) {
            // TODO
            if (_log.shouldWarn())
                _log.warn("Zero static key in IB NS");
            return null;
        }

        // payload
        if (payloadlen == 0) {
            if (_log.shouldWarn())
                _log.warn("Zero length payload in NS");
            return null;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, true);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB NS");
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("Msg 1 payload error", e);
        }

        // tell the SKM
        PublicKey bob = new PublicKey(EncType.ECIES_X25519, bobPK);
        keyManager.createSession(bob, state);

        if (pc.cloveSet.isEmpty()) {
            if (_log.shouldWarn())
                _log.warn("No garlic block in NS payload");
        }
        int num = pc.cloveSet.size();
        // return non-null even if zero cloves
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
        PublicKey k = Elligator2.decode(yy);
        if (k == null) {
            if (_log.shouldWarn())
                _log.warn("Elg2 decode fail NSR");
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State before decrypt new session reply: " + state);
        System.arraycopy(k.getData(), 0, data, TAGLEN, KEYLEN);
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after mixhash tag before decrypt new session reply: " + state);
        try {
            state.readMessage(data, 8, 48, ZEROLEN, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Decrypt fail NSR part 1", gse);
                if (_log.shouldDebug())
                    _log.debug("State at failure: " + state);
            }
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State after decrypt new session reply: " + state);

        // split()
        byte[] ck = state.getChainingKey();
        byte[] k_ab = new byte[32];
        byte[] k_ba = new byte[32];
        _hkdf.calculate(ck, ZEROLEN, k_ab, k_ba, 0);
        CipherStatePair ckp = state.split();
        CipherState rcvr = ckp.getReceiver();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(k_ba, ZEROLEN, INFO_6, encpayloadkey);
        rcvr.initializeKey(encpayloadkey, 0);
        byte[] payload = new byte[data.length - (TAGLEN + KEYLEN + MACLEN + MACLEN)];
        try {
            rcvr.decryptWithAd(hash, data, TAGLEN + KEYLEN + MACLEN, payload, 0, payload.length + MACLEN);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn()) {
                _log.warn("Decrypt fail NSR part 2", gse);
                if (_log.shouldDebug())
                    _log.debug("State at failure: " + state);
            }
            return null;
        }
        if (payload.length == 0) {
            if (_log.shouldWarn())
                _log.warn("Zero length payload in NSR");
            return null;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, payload, 0, payload.length, false);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB NSR");
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("NSR payload error", e);
        }

        byte[] bobPK = new byte[KEYLEN];
        state.getRemotePublicKey().getPublicKey(bobPK, 0);
        if (_log.shouldDebug())
            _log.debug("NSR decrypt success from PK " + Base64.encode(bobPK));
        if (Arrays.equals(bobPK, NULLPK)) {
            // TODO
            if (_log.shouldWarn())
                _log.warn("NSR reply to zero static key NS");
            return null;
        }

        // tell the SKM
        PublicKey bob = new PublicKey(EncType.ECIES_X25519, bobPK);
        keyManager.updateSession(bob, oldState, state);

        if (pc.cloveSet.isEmpty()) {
            if (_log.shouldWarn())
                _log.warn("No garlic block in NSR payload");
        }
        int num = pc.cloveSet.size();
        // return non-null even if zero cloves
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
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
     *
     * @return decrypted data or null on failure
     *
     */
    private CloveSet decryptExistingSession(byte[] tag, byte[] data, SessionKeyAndNonce key, PrivateKey targetPrivateKey)
                                          throws DataFormatException {
// TODO decrypt in place?
        byte decrypted[] = decryptAEADBlock(tag, data, TAGLEN, data.length - TAGLEN, key, key.getNonce());
        if (decrypted == null) {
            if (_log.shouldWarn())
                _log.warn("Decrypt of ES failed");
            return null;
        }
        if (decrypted.length == 0) {
            if (_log.shouldWarn())
                _log.warn("Zero length payload in ES");
            return null;
        }
        PLCallback pc = new PLCallback();
        try {
            int blocks = RatchetPayload.processPayload(_context, pc, decrypted, 0, decrypted.length, false);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks in IB ES");
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("ES payload error", e);
        }
        if (pc.cloveSet.isEmpty()) {
            if (_log.shouldWarn())
                _log.warn("No garlic block in ES payload");
        }
        int num = pc.cloveSet.size();
        // return non-null even if zero cloves
        GarlicClove[] arr = new GarlicClove[num];
        // msg id and expiration not checked in GarlicMessageReceiver
        CloveSet rv = new CloveSet(pc.cloveSet.toArray(arr), Certificate.NULL_CERT, 0, pc.datetime);
        return rv;
    }

    /**
     * No AD
     *
     * @return decrypted data or null on failure
     */
    private byte[] decryptAEADBlock(byte encrypted[], int offset, int len, SessionKey key,
                                    long n) throws DataFormatException {
// TODO decrypt in place?
        return decryptAEADBlock(null, encrypted, offset, len, key, n);
    }

    /*
     * With optional AD
     *
     * @param ad may be null
     * @return decrypted data or null on failure
     */
    private byte[] decryptAEADBlock(byte[] ad, byte encrypted[], int offset, int encryptedLen, SessionKey key,
                                    long n) throws DataFormatException {
// TODO decrypt in place?
        byte decrypted[] = new byte[encryptedLen - MACLEN];
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(n);
        try {
            chacha.decryptWithAd(ad, encrypted, offset, decrypted, 0, encryptedLen);
        } catch (GeneralSecurityException e) {
            if (_log.shouldWarn())
                _log.warn("Unable to decrypt AEAD block", e);
            return null;
        }
////
        return decrypted;
    }

    //// end decrypt, start encrypt ////


    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     * No new session key
     * This is the one called from GarlicMessageBuilder and is the primary entry point.
     *
     * Re: padded size: The AEAD block adds at least 39 bytes of overhead to the data, and
     * that is included in the minimum size calculation.
     *
     * In the router, we always use garlic messages. A garlic message with a single
     * clove and zero data is about 84 bytes, so that's 123 bytes minimum. So any paddingSize
     * &lt;= 128 is a no-op as every message will be at least 128 bytes
     * (Streaming, if used, adds more overhead).
     *
     * Outside the router, with a client using its own message format, the minimum size
     * is 48, so any paddingSize &lt;= 48 is a no-op.
     *
     * Not included in the minimum is a 32-byte session tag for an existing session,
     * or a 514-byte ECIES block and several 32-byte session tags for a new session.
     * So the returned encrypted data will be at least 32 bytes larger than paddedSize.
     *
     * @param target public key to which the data should be encrypted. 
     * @param priv local private key to encrypt with, from the leaseset
     * @return encrypted data or null on failure
     *
     */
    public byte[] encrypt(CloveSet cloves, PublicKey target, PrivateKey priv,
                          RatchetSKM keyManager) {
        try {
            return x_encrypt(cloves, target, priv, keyManager);
        } catch (Exception e) {
            _log.error("ECIES encrypt error", e);
            return null;
        }
    }

    private byte[] x_encrypt(CloveSet cloves, PublicKey target, PrivateKey priv,
                             RatchetSKM keyManager) {
        if (target.getType() != EncType.ECIES_X25519)
            throw new IllegalArgumentException();
        if (Arrays.equals(target.getData(), NULLPK)) {
            // TODO
            if (_log.shouldWarn())
                _log.warn("Zero static key target");
            return null;
        }
        RatchetEntry re = keyManager.consumeNextAvailableTag(target);
        if (re == null) {
            if (_log.shouldDebug())
                _log.debug("Encrypting as NS to " + target);
            return encryptNewSession(cloves, target, priv, keyManager);
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
            return encryptNewSessionReply(cloves, target, state, re.tag, keyManager);
        }
        if (_log.shouldDebug())
            _log.debug("Encrypting as ES to " + target + " with key " + re.key + " and tag " + re.tag.toBase64());
        byte rv[] = encryptExistingSession(cloves, target, re.key, re.tag);
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
     * @return encrypted data or null on failure
     */
    private byte[] encryptNewSession(CloveSet cloves, PublicKey target, PrivateKey priv,
                                     RatchetSKM keyManager) {
        HandshakeState state;
        try {
            state = new HandshakeState(HandshakeState.PATTERN_ID_IK, HandshakeState.INITIATOR, _edhThread);
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        state.getRemotePublicKey().setPublicKey(target.getData(), 0);
        state.getLocalKeyPair().setPublicKey(priv.toPublic().getData(), 0);
        state.getLocalKeyPair().setPrivateKey(priv.getData(), 0);
        state.start();
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session: " + state);

        byte[] payload = createPayload(cloves, cloves.getExpiration());

        byte[] enc = new byte[KEYLEN + KEYLEN + MACLEN + payload.length + MACLEN];
        try {
            state.writeMessage(enc, 0, payload, 0, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NS", gse);
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State after encrypt new session: " + state);

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NS state");
            return null;
        }
        eph.getEncodedPublicKey(enc, 0);
        if (_log.shouldDebug())
            _log.debug("Elligator2 encoded eph. key: " + Base64.encode(enc, 0, 32));

        // tell the SKM
        keyManager.createSession(target, state);
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
     * @return encrypted data or null on failure
     */
    private byte[] encryptNewSessionReply(CloveSet cloves, PublicKey target, HandshakeState state,
                                          RatchetSessionTag currentTag, RatchetSKM keyManager) {
        if (_log.shouldDebug())
            _log.debug("State before encrypt new session reply: " + state);
        byte[] tag = currentTag.getData();
        state.mixHash(tag, 0, TAGLEN);
        if (_log.shouldDebug())
            _log.debug("State after mixhash tag before encrypt new session reply: " + state);

        byte[] payload = createPayload(cloves, 0);

        // part 1 - tag and empty payload
        byte[] enc = new byte[TAGLEN + KEYLEN + MACLEN + payload.length + MACLEN];
        System.arraycopy(tag, 0, enc, 0, TAGLEN);
        try {
            state.writeMessage(enc, TAGLEN, ZEROLEN, 0, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NSR part 1", gse);
            return null;
        }
        if (_log.shouldDebug())
            _log.debug("State after encrypt new session reply: " + state);

        // overwrite eph. key with encoded key
        DHState eph = state.getLocalEphemeralKeyPair();
        if (eph == null || !eph.hasEncodedPublicKey()) {
            if (_log.shouldWarn())
                _log.warn("Bad NSR state");
            return null;
        }
        eph.getEncodedPublicKey(enc, TAGLEN);

        // split()
        byte[] ck = state.getChainingKey();
        byte[] k_ab = new byte[32];
        byte[] k_ba = new byte[32];
        _hkdf.calculate(ck, ZEROLEN, k_ab, k_ba, 0);
        CipherStatePair ckp = state.split();
        CipherState sender = ckp.getSender();
        byte[] hash = state.getHandshakeHash();

        // part 2 - payload
        byte[] encpayloadkey = new byte[32];
        _hkdf.calculate(k_ba, ZEROLEN, INFO_6, encpayloadkey);
        sender.initializeKey(encpayloadkey, 0);
        try {
            sender.encryptWithAd(hash, payload, 0, enc, TAGLEN + KEYLEN + MACLEN, payload.length);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Encrypt fail NSR part 2", gse);
            return null;
        }
        // tell the SKM
        keyManager.updateSession(target, null, state);

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
     * @param target unused, this is AEAD encrypt only using the session key and tag
     * @return encrypted data or null on failure
     */
    private byte[] encryptExistingSession(CloveSet cloves, PublicKey target, SessionKeyAndNonce key,
                                          RatchetSessionTag currentTag) {
        byte rawTag[] = currentTag.getData();
        byte[] payload = createPayload(cloves, 0);
        byte encr[] = encryptAEADBlock(rawTag, payload, key, key.getNonce());
        System.arraycopy(rawTag, 0, encr, 0, TAGLEN);
        return encr;
    }

    /**
     * No ad
     */
    private final byte[] encryptAEADBlock(byte data[], SessionKey key, long n) {
        return encryptAEADBlock(null, data, key, n);
    }

    /**
     *
     * @param ad may be null
     * @return space will be left at beginning for ad (tag)
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
        }
        return enc;
    }

    private static final PrivateKey doDH(PrivateKey privkey, PublicKey pubkey) {
        byte[] dh = new byte[KEYLEN];
        Curve25519.eval(dh, 0, privkey.getData(), pubkey.getData());
        return new PrivateKey(EncType.ECIES_X25519, dh);
    }

    /////////////////////////////////////////////////////////
    // payload stuff
    /////////////////////////////////////////////////////////

    private class PLCallback implements RatchetPayload.PayloadCallback {
        public final List<GarlicClove> cloveSet = new ArrayList<GarlicClove>(3);
        public long datetime;
        public NextSessionKey nextKey;

        public void gotDateTime(long time) {
            if (_log.shouldDebug())
                _log.debug("Got DATE block: " + DataHelper.formatTime(time));
            if (datetime != 0)
                throw new IllegalArgumentException("Multiple DATETIME blocks");
            datetime = time;
        }

        public void gotOptions(byte[] options, boolean isHandshake) {
            if (_log.shouldDebug())
                _log.debug("Got OPTIONS block length " + options.length);
        }

        public void gotGarlic(GarlicClove clove) {
            if (_log.shouldDebug())
                _log.debug("Got GARLIC block: " + clove);
            cloveSet.add(clove);
        }

        public void gotNextKey(NextSessionKey next) {
            if (_log.shouldDebug())
                _log.debug("Got NEXTKEY block: " + next);
            nextKey = next;
        }

        public void gotTermination(int reason, long count) {
            if (_log.shouldDebug())
                _log.debug("Got TERMINATION block, reason: " + reason + " count: " + count);
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
     */
    private byte[] createPayload(CloveSet cloves, long expiration) {
        int count = cloves.getCloveCount();
        int numblocks = count + 1;
        if (expiration > 0)
            numblocks++;
        int len = 0;
        List<Block> blocks = new ArrayList<Block>(numblocks);
        if (expiration > 0) {
            Block block = new DateTimeBlock(expiration);
            blocks.add(block);
            len += block.getTotalLength();
        }
        for (int i = 0; i < count; i++) {
            GarlicClove clove = cloves.getClove(i);
            Block block = new GarlicBlock(clove);
            blocks.add(block);
            len += block.getTotalLength();
        }
        int padlen = 1 + _context.random().nextInt(MAXPAD);
        // random data
        //Block block = new PaddingBlock(_context, padlen);
        // zeros
        Block block = new PaddingBlock(padlen);
        blocks.add(block);
        len += block.getTotalLength();
        byte[] payload = new byte[len];
        int payloadlen = createPayload(payload, 0, blocks);
        if (payloadlen != len)
            throw new IllegalStateException("payload size mismatch");
        return payload;
    }

    /**
     *  @return the new offset
     */
    private int createPayload(byte[] payload, int off, List<Block> blocks) {
        return RatchetPayload.writePayload(payload, off, blocks);
    }

    private byte[] doHMAC(SessionKey key, byte data[]) {
        byte[] rv = new byte[32];
        _context.hmac256().calculate(key, data, 0, data.length, rv, 0);
        return rv;
    }


/****
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        ECIESAEADEngine e = new ECIESAEADEngine(ctx);
        Object kp[] = ctx.keyGenerator().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)kp[0];
        PrivateKey privKey = (PrivateKey)kp[1];
        SessionKey sessionKey = ctx.keyGenerator().generateSessionKey();
        for (int i = 0; i < 10; i++) {
            try {
                Set tags = new HashSet(5);
                if (i == 0) {
                    for (int j = 0; j < 5; j++)
                        tags.add(new SessionTag(true));
                }
                byte encrypted[] = e.encrypt("blah".getBytes(), pubKey, sessionKey, tags, 1024);
                byte decrypted[] = e.decrypt(encrypted, privKey);
                if ("blah".equals(new String(decrypted))) {
                    System.out.println("equal on " + i);
                } else {
                    System.out.println("NOT equal on " + i + ": " + new String(decrypted));
                    break;
                }
                ctx.sessionKeyManager().tagsDelivered(pubKey, sessionKey, tags);
            } catch (Exception ee) {
                ee.printStackTrace();
                break;
            }
        }
    }
****/
}
