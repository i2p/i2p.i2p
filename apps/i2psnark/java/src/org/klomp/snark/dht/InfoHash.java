package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;

/**
 *  A 20-byte SHA1 info hash
 *
 * @since 0.8.4
 * @author zzz
 */
class InfoHash extends SHA1Hash {

    public InfoHash(byte[] data) {
        super(data);
    }
}
