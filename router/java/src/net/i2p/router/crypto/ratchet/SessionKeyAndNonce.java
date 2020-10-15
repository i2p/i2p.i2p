package net.i2p.router.crypto.ratchet;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

/**
 * A session key is 32 bytes of data.
 * Nonce should be 65535 or less.
 *
 * This is what is returned from RatchetTagSet.consume().
 * RatchetSKM puts it in a RatchetEntry and returns it to ECIESAEADEngine.
 *
 * @since 0.9.44
 */
class SessionKeyAndNonce extends SessionKey {
    private final int _id, _nonce;
    private final HandshakeState _state;
    private final PublicKey _remoteKey;

    /**
     *  For outbound Existing Session
     */
    public SessionKeyAndNonce(byte data[], int nonce) {
        this(data, 0, nonce, null);
    }

    /**
     *  For inbound Existing Session
     *  @since 0.9.46
     */
    public SessionKeyAndNonce(byte data[], int id, int nonce, PublicKey remoteKey) {
        super(data);
        _id = id;
        _nonce = nonce;
        _remoteKey = remoteKey;
        _state = null;
    }

    /**
     *  For New Session Replies
     */
    public SessionKeyAndNonce(HandshakeState state) {
        super();
        _id = 0;
        _nonce = 0;
        _remoteKey = null;
        _state = state;
    }

    /**
     *  For ES, else 0
     */
    public int getNonce() {
        return _nonce;
    }

    /**
     *  For inbound ES, else 0
     *  @since 0.9.46
     */
    public int getID() {
        return _id;
    }

    /**
     *  For inbound ES, else null.
     *  For NSR, use getHansdhakeState().getRemotePublicKey().getPublicKey().
     *  @since 0.9.46
     */
    public PublicKey getRemoteKey() {
        return _remoteKey;
    }

    /**
     *  For inbound NSR only, else null.
     *  MUST be cloned before processing NSR.
     */
    public HandshakeState getHandshakeState() {
        return _state;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SessionKeyAndNonce: ");
        if (_data != null)
            buf.append(toBase64());
        buf.append(_state != null ? " NSR" : " ES");
        buf.append(" nonce: ").append(_nonce);
        buf.append(']');
        return buf.toString();
    }
}
