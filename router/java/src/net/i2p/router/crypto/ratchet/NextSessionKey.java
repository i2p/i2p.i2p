package net.i2p.router.crypto.ratchet;

import net.i2p.data.SessionKey;

/**
 * A session key and key ID.
 *
 * @since 0.9.44
 */
class NextSessionKey extends SessionKey {
    private final int _id;

    public NextSessionKey(byte[] data, int id) {
        super(data);
        _id = id;
    }

    public int getID() {
        return _id;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[NextSessionKey: ");
        buf.append(toBase64());
        buf.append(" ID: ").append(_id);
        buf.append(']');
        return buf.toString();
    }
}
