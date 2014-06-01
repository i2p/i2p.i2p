package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.data.Hash;

/**
 *  A single peer for a single torrent.
 *  This is what the DHT tracker remembers.
 *
 * @since 0.9.2
 * @author zzz
 */
class Peer extends Hash {

    private volatile long lastSeen;
    // todo we could pack this into the upper bit of lastSeen
    private volatile boolean isSeed;

    public Peer(byte[] data) {
        super(data);
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long now) {
        lastSeen = now;
    }

    /** @since 0.9.14 */
    public boolean isSeed() {
        return isSeed;
    }

    /** @since 0.9.14 */
    public void setSeed(boolean isSeed) {
        this.isSeed = isSeed;
    }
}
