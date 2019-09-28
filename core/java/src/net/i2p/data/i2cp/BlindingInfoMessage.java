package net.i2p.data.i2cp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * Advise the router that the endpoint is blinded.
 * Client to router. There is no reply.
 * Preliminary - subject to change -
 * See proposal 123.
 *
 * @see BlindData
 * @see net.i2p.crypto.Blinding
 *
 * @since 0.9.43; do not send to routers older than 0.9.43.
 */
public class BlindingInfoMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 42;

    private SessionId _sessionId;
    private int _endpointType;
    private int _authType;
    private SigType _blindType;
    private long _expiration;
    private Hash _hash;
    private String _host;
    private Destination _dest;
    private SigningPublicKey _pubkey;
    private PrivateKey _privkey;
    private String _secret;
    private BlindData _blindData;

    private static final int FLAG_AUTH = 0x0f;
    private static final int FLAG_SECRET = 0x10;

    public static final int TYPE_HASH = 0;
    public static final int TYPE_HOST = 1;
    public static final int TYPE_DEST = 2;
    public static final int TYPE_KEY = 3;

    public BlindingInfoMessage() {}

    /**
     *  This is the constructor used by I2CP client-side.
     *  Will create a DEST or KEY message type, depending on whether
     *  BlindData has the full destination.
     *
     */
    public BlindingInfoMessage(BlindData bd, SessionId id) {
        this(id, bd.getExpiration(), bd.getAuthType(), bd.getBlindedSigType(), bd.getAuthPrivKey(), bd.getSecret());
        Destination dest = bd.getDestination();
        if (dest != null) {
            _dest = dest;
            _hash = dest.calculateHash();
            _pubkey = dest.getSigningPublicKey();
            _endpointType = TYPE_DEST;
        } else if (bd.getUnblindedPubKey() != null) {
            _pubkey = bd.getUnblindedPubKey();
            _endpointType = TYPE_KEY;
        } else {
            throw new IllegalArgumentException();
        }
        _blindData = bd;
    }

    /**
     *  HASH not supported by router and may not be useful
     *
     *  @param authType 0 (none), 1 (DH), 3 (PSK)
     *  @param expiration ms from now or 0 for forever
     *  @param privKey null for auth none, non-null for DH/PSK
     *  @param secret may be null, 255 UTF-8 bytes max
     *  @deprecated unimplemented on router side
     */
    @Deprecated
    public BlindingInfoMessage(Hash h,
                               SessionId id, int expiration,
                               int authType, SigType blindType,
                               PrivateKey privKey, String secret) {
        this(id, expiration, authType, blindType, privKey, secret);
        if (h == null || h.getData() == null)
            throw new IllegalArgumentException();
        _hash = h;
        _endpointType = TYPE_HASH;
    }

    /**
     *  HOST not supported by router and may not be useful
     *
     *  @param h hostname
     *  @param authType 0 (none), 1 (DH), 3 (PSK)
     *  @param expiration ms from now or 0 for forever
     *  @param privKey null for auth none, non-null for DH/PSK
     *  @param secret may be null, 255 UTF-8 bytes max
     *  @deprecated unimplemented on router side
     */
    @Deprecated
    public BlindingInfoMessage(String h,
                               SessionId id, int expiration,
                               int authType, SigType blindType,
                               PrivateKey privKey, String secret) {
        this(id, expiration, authType, blindType, privKey, secret);
        if (h == null)
            throw new IllegalArgumentException();
        _host = h;
        _endpointType = TYPE_HOST;
    }

    /**
     *  @param authType 0 (none), 1 (DH), 3 (PSK)
     *  @param expiration ms from now or 0 for forever
     *  @param privKey null for auth none, non-null for DH/PSK
     *  @param secret may be null, 255 UTF-8 bytes max
     */
    public BlindingInfoMessage(Destination d,
                               SessionId id, int expiration,
                               int authType, SigType blindType,
                               PrivateKey privKey, String secret) {
        this(id, expiration, authType, blindType, privKey, secret);
        if (d == null || d.getSigningPublicKey() == null)
            throw new IllegalArgumentException();
        _dest = d;
        _hash = d.calculateHash();
        _pubkey = d.getSigningPublicKey();
        _endpointType = TYPE_DEST;
    }

    /**
     *  @param authType 0 (none), 1 (DH), 3 (PSK)
     *  @param expiration ms from now or 0 for forever
     *  @param privKey null for auth none, non-null for DH/PSK
     *  @param secret may be null, 255 UTF-8 bytes max
     */
    public BlindingInfoMessage(SigningPublicKey s,
                               SessionId id, int expiration,
                               int authType, SigType blindType,
                               PrivateKey privKey, String secret) {
        this(id, expiration, authType, blindType, privKey, secret);
        if (s == null || s.getData() == null)
            throw new IllegalArgumentException();
        _pubkey = s;
        _endpointType = TYPE_KEY;
    }

    private BlindingInfoMessage(SessionId id, long expiration, int authType, SigType blindType,
                                PrivateKey privKey, String secret) {
        if (id == null || blindType == null)
            throw new IllegalArgumentException();
        if (authType != BlindData.AUTH_NONE && authType != BlindData.AUTH_DH &&
            authType != BlindData.AUTH_PSK)
            throw new IllegalArgumentException("Bad auth type");
        if (authType == BlindData.AUTH_NONE && privKey != null)
            throw new IllegalArgumentException("no key required");
        if (authType != BlindData.AUTH_NONE && privKey == null)
            throw new IllegalArgumentException("key required");
        if (privKey != null && privKey.getType() != EncType.ECIES_X25519)
            throw new IllegalArgumentException("Bad privkey type");
        _sessionId = id;
        _authType = authType;
        _blindType = blindType;
        _expiration = expiration;
        if (expiration > 0 && expiration < Integer.MAX_VALUE)
            _expiration += I2PAppContext.getGlobalContext().clock().now();
        _privkey = privKey;
        _secret = secret;
    }

    public SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     */
    @Override
    public SessionId sessionId() {
        return _sessionId;
    }

    /**
     *  @return ms 1 to 2**32 - 1
     */
    public long getTimeout() {
        return _expiration;
    }

    /**
     *  @return 0 (none), 1 (DH), 3 (PSK)
     */
    public int getAuthType() {
        return _authType;
    }

    /**
     *  @return 0 (hash) or 1 (host) or 2 (dest) or 3 (key)
     */
    public int getEndpointType() {
        return _endpointType;
    }

    /**
     *  @return only valid if endpoint type == 0 or 2
     */
    public Hash getHash() {
        return _hash;
    }

    /**
     *  @return only valid if endpoint type == 1
     */
    public String getHostname() {
        return _host;
    }

    /**
     *  @return only valid if endpoint type == 2
     */
    public String getDestination() {
        return _host;
    }

    /**
     *  @return only valid if endpoint type == 2 or 3
     */
    public SigningPublicKey getSigningPublicKey() {
        return _pubkey;
    }

    /**
     *  @return private key or null
     */
    public PrivateKey getPrivateKey() {
        return _privkey;
    }

    /**
     *  @return secret or null
     */
    public String getSecret() {
        return _secret;
    }

    /**
     *  @return blind data or null if not enough info
     */
    public BlindData getBlindData() {
        if (_blindData != null)
            return _blindData;
        if (_endpointType == TYPE_DEST)
            _blindData = new BlindData(I2PAppContext.getGlobalContext(), _dest, _blindType, _secret, _authType, _privkey);
        else if (_endpointType == TYPE_KEY)
            _blindData = new BlindData(I2PAppContext.getGlobalContext(), _pubkey, _blindType, _secret, _authType, _privkey);
        if (_blindData != null) {
            _blindData.setDate(I2PAppContext.getGlobalContext().clock().now());
            _blindData.setExpiration(_expiration);
        }
        // HASH and HOST not supported by router yet
        return _blindData;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            int flags = in.read();
            _authType = flags & FLAG_AUTH;
            boolean hasSecret = (flags & FLAG_SECRET) != 0;
            _endpointType = in.read();
            int bt = (int) DataHelper.readLong(in, 2);
            _blindType = SigType.getByCode(bt);
            if (_blindType == null)
                throw new I2CPMessageException("unsupported sig type " + bt);
            _expiration = DataHelper.readLong(in, 4) * 1000;
            if (_endpointType == TYPE_HASH) {
                _hash = Hash.create(in);
            } else if (_endpointType == TYPE_HOST) {
                _host = DataHelper.readString(in);
                if (_host.length() == 0)
                    throw new I2CPMessageException("bad host");
            } else if (_endpointType == TYPE_DEST) {
                _dest = Destination.create(in);
            } else if (_endpointType == TYPE_KEY) {
                int st = (int) DataHelper.readLong(in, 2);
                SigType sigt = SigType.getByCode(st);
                if (sigt == null)
                    throw new I2CPMessageException("unsupported sig type " + st);
                int len = sigt.getPubkeyLen();
                byte[] key = new byte[len];
                DataHelper.read(in, key);
                _pubkey = new SigningPublicKey(sigt, key);
            } else {
                throw new I2CPMessageException("bad type");
            }
            if (_authType == BlindData.AUTH_DH || _authType == BlindData.AUTH_PSK) {
                byte[] key = new byte[32];
                DataHelper.read(in, key);
                _privkey = new PrivateKey(EncType.ECIES_X25519, key);
            } else if (_authType != BlindData.AUTH_NONE) {
                throw new I2CPMessageException("bad auth type " + _authType);
            }
            if (hasSecret)
                _secret = DataHelper.readString(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_endpointType == TYPE_HASH) {
            if (_hash == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        } else if (_endpointType == TYPE_HOST) {
            if (_host == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        } else if (_endpointType == TYPE_DEST) {
            if (_dest == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        } else if (_endpointType == TYPE_KEY) {
            if (_pubkey == null)
                throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        } else {
            throw new I2CPMessageException("bad type");
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream(512);
        try {
            _sessionId.writeBytes(os);
            byte flags = (byte) (_authType & FLAG_AUTH);
            if (_secret != null)
                flags |= FLAG_SECRET;
            os.write(flags);
            os.write((byte) _endpointType);
            DataHelper.writeLong(os, 2, _blindType.getCode());
            DataHelper.writeLong(os, 4, _expiration / 1000);
            if (_endpointType == TYPE_HASH) {
                _hash.writeBytes(os);
            } else if (_endpointType == TYPE_HOST) {
                DataHelper.writeString(os, _host);
            } else if (_endpointType == TYPE_DEST) {
                _dest.writeBytes(os);
            } else {  // TYPE_KEY
                DataHelper.writeLong(os, 2, _pubkey.getType().getCode());
                os.write(_pubkey.getData());
            }
            if (_privkey != null) {
                os.write(_privkey.getData());
            }
            if (_secret != null)
                DataHelper.writeString(os, _secret);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("bad data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[BlindingInfoMessage: ");
        buf.append("\n\tSession: ").append(_sessionId);
        buf.append("\n\tTimeout: ").append(_expiration);
        buf.append("\n\tAuthTyp: ").append(_authType);
        if (_endpointType == TYPE_HASH)
            buf.append("\n\tHash: ").append(_hash.toBase32());
        else if (_endpointType == TYPE_HOST)
            buf.append("\n\tHost: ").append(_host);
        else if (_endpointType == TYPE_DEST)
            buf.append("\n\tDest: ").append(_dest);
        else if (_endpointType == TYPE_KEY)
            buf.append("\n\tKey: ").append(_pubkey);
        if (_privkey != null)
            buf.append("\n\tPriv Key: ").append(_privkey);
        if (_secret != null)
            buf.append("\n\tSecret: ").append(_secret);
        buf.append("]");
        return buf.toString();
    }
}
