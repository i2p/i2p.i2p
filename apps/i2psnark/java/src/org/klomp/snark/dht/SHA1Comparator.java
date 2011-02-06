package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.Comparator;

import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;

/**
 *  Closest to a InfoHash or NID key.
 *  Use for InfoHashes and NIDs.
 *
 * @since 0.8.4
 * @author zzz
 */
class SHA1Comparator implements Comparator<SHA1Hash> {
    private final byte[] _base;

    public SHA1Comparator(SHA1Hash h) {
        _base = h.getData();
    }

    public int compare(SHA1Hash lhs, SHA1Hash rhs) {
        byte lhsDelta[] = DataHelper.xor(lhs.getData(), _base);
        byte rhsDelta[] = DataHelper.xor(rhs.getData(), _base);
        return DataHelper.compareTo(lhsDelta, rhsDelta);
    }

}
