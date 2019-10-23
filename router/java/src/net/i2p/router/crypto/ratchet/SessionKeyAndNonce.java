package net.i2p.router.crypto.ratchet;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.data.SessionKey;

/**
 * A session key is 32 bytes of data.
 * Nonce should be 65535 or less.
 *
 * @since 0.9.44
 */
class SessionKeyAndNonce extends SessionKey {
    private final int _nonce;
    private final HandshakeState _state;

    /**
     *  For Existing Session
     */
    public SessionKeyAndNonce(byte data[], int nonce) {
        super(data);
        _nonce = nonce;
        _state = null;
    }

    /**
     *  For New Session Replies
     */
    public SessionKeyAndNonce(HandshakeState state) {
        super();
        _nonce = 0;
        _state = state;
    }

    /**
     *  For ES, else 0
     */
    public int getNonce() {
        return _nonce;
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
        buf.append(toBase64());
        buf.append(" nonce: ").append(_nonce);
        buf.append(']');
        return buf.toString();
    }
}
