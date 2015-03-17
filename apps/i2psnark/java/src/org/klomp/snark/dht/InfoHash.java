package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;
import org.klomp.snark.I2PSnarkUtil;

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

    @Override
    public String toString() {
        if (_data == null) {
            return super.toString();
        } else {
            return "[InfoHash: " + I2PSnarkUtil.toHex(_data) + ']';
        }
    }
}
