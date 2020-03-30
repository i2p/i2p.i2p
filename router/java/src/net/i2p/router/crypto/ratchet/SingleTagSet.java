package net.i2p.router.crypto.ratchet;

import net.i2p.data.SessionKey;

/**
 * Inbound ES tagset with a single tag and key.
 * Nonce is 0.
 * For receiving DSM/DSRM replies.
 *
 * @since 0.9.46
 */
class SingleTagSet extends RatchetTagSet {

    private final RatchetSessionTag _tag;
    private boolean _isUsed;

    /**
     *  For outbound Existing Session
     */
    public SingleTagSet(SessionTagListener lsnr, SessionKey key, RatchetSessionTag tag, long date, long timeout) {
        super(lsnr, key, date, timeout);
        _tag = tag;
        lsnr.addTag(tag, this);
    }

    @Override
    public int size() {
        return _isUsed ? 0 : 1;
    }

    @Override
    public int remaining() {
        return _isUsed ? 0 : 1;
    }

    @Override
    public SessionKeyAndNonce consume(RatchetSessionTag tag) {
        if (_isUsed || !tag.equals(_tag))
            return null;
        _isUsed = true;
        return new SessionKeyAndNonce(_key.getData(), 0);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SingleTagSet: 0 ");
        buf.append(_tag.toBase64());
        buf.append(' ').append(_key.toBase64());
        buf.append(']');
        return buf.toString();
    }
}
