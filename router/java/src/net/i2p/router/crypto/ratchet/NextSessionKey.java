package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.EncType;
import net.i2p.data.PublicKey;

/**
 * A X25519 key and key ID.
 *
 * @since 0.9.44
 */
class NextSessionKey extends PublicKey {
    private final int _id;
    private final boolean _isReverse, _isRequest;

    /**
     *  @param data may be null
     */
    public NextSessionKey(byte[] data, int id, boolean isReverse, boolean isRequest) {
        super(EncType.ECIES_X25519, data);
        _id = id;
        _isReverse = isReverse;
        _isRequest = isRequest;
    }

    public int getID() {
        return _id;
    }

    /** @since 0.9.46 */
    public boolean isReverse() {
        return _isReverse;
    }

    /** @since 0.9.46 */
    public boolean isRequest() {
        return _isRequest;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[NextSessionKey: ");
        buf.append(toBase64());
        buf.append(" ID: ").append(_id);
        buf.append(" reverse? ").append(_isReverse);
        buf.append(" request? ").append(_isRequest);
        buf.append(']');
        return buf.toString();
    }
}
