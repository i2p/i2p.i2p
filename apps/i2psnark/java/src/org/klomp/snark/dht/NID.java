package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;

/**
 *  A 20-byte peer ID, used as a Map key in lots of places.
 *
 * @since 0.8.4
 * @author zzz
 */
public class NID extends SHA1Hash {

    public NID(byte[] data) {
        super(data);
    }
}
