package net.i2p.router.crypto.ratchet;

import java.util.List;

import net.i2p.data.SessionKey;

/**
 *  Simple object with outbound tag, key, and nonce,
 *  and an optional next key.
 *  The object returned from SKM.consumeNextAvailableTag() to the engine encrypt.
 *
 *  @since 0.9.44
 */
class RatchetEntry {
    public final RatchetSessionTag tag;
    public final SessionKeyAndNonce key;
    public final int keyID;
    public final int pn;
    public final NextSessionKey nextKey;
    public final List<Integer> acksToSend;

    /** outbound - calculated key */
    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key, int keyID, int pn) {
        this(tag, key, keyID, pn, null, null);
    }

    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key, int keyID, int pn,
                        NextSessionKey nextKey, List<Integer> acksToSend) {
        this.tag = tag;
        this.key = key;
        this.keyID = keyID;
        this.pn = pn;
        this.nextKey = nextKey;
        this.acksToSend = acksToSend;
    }

    @Override
    public String toString() {
        return "RatchetEntry[" + tag.toBase64() + ' ' + key + ']';
    }
}
