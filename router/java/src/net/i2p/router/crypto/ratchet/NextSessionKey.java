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
     *  @param data non-null
     */
    public NextSessionKey(byte[] data, int id, boolean isReverse, boolean isRequest) {
        super(EncType.ECIES_X25519, data);
        _id = id;
        _isReverse = isReverse;
        _isRequest = isRequest;
    }

    /**
     *  Null data, for acks/requests only.
     *  Type will be ElG but doesn't matter.
     *  Don't call setData().
     *  @since 0.9.46
     */
    public NextSessionKey(int id, boolean isReverse, boolean isRequest) {
        super();
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

    /**
     *  @since 0.9.46
     */
    @Override
    public int hashCode() {
        int rv = super.hashCode() ^ _id;
        if (_isReverse)
            rv ^= 1 << 31;
        if (_isRequest)
            rv ^= 1 << 30;
        return rv;
    }

    /**
     *  @since 0.9.46
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (!(obj instanceof NextSessionKey))
            return false;
        NextSessionKey o = (NextSessionKey) obj;
        return _id == o._id &&
               _isReverse == o._isReverse &&
               _isRequest == o._isRequest &&
               super.equals(o);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[NextSessionKey: ");
        buf.append(super.toString());
        buf.append(" ID: ").append(_id);
        buf.append(" reverse? ").append(_isReverse);
        buf.append(" request? ").append(_isRequest);
        buf.append(']');
        return buf.toString();
    }
}
