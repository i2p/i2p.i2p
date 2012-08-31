package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;
import net.i2p.util.Clock;

/**
 *  A 20-byte peer ID, used as a Map key in lots of places.
 *  Must be public for constructor in KBucketSet.generateRandomKey()
 *
 * @since 0.9.2
 * @author zzz
 */
public class NID extends SHA1Hash {

    private long lastSeen;
    private int fails;

    private static final int MAX_FAILS = 3;

    public NID() {
        super(null);
    }

    public NID(byte[] data) {
        super(data);
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void setLastSeen() {
        lastSeen = Clock.getInstance().now();
        fails = 0;
    }

    /**
     *  @return if more than max timeouts
     */
    public boolean timeout() {
        return fails++ > MAX_FAILS;
    }
}
