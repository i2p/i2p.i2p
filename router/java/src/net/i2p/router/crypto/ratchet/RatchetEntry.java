package net.i2p.router.crypto.ratchet;

import net.i2p.data.SessionTag;

/**
 *
 *  @since 0.9.44
 */
class RatchetEntry {
    public final RatchetSessionTag tag;
    public final SessionKeyAndNonce key;

    /** outbound - calculated key */
    public RatchetEntry(RatchetSessionTag tag, SessionKeyAndNonce key) {
        this.tag = tag;
        this.key = key;
    }
}
