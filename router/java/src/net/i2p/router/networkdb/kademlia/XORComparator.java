package net.i2p.router.networkdb.kademlia;

import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Help sort Hashes in relation to a base key using the XOR metric.
 * Warning - not thread safe.
 */
class XORComparator implements Comparator<Hash> {
    private final byte[] _base;
    private final byte[] _lx, _rx;

    /**
     * @param target key to compare distances with
     */
    public XORComparator(Hash target) {
        _base = target.getData();
        _lx = new byte[Hash.HASH_LENGTH];
        _rx = new byte[Hash.HASH_LENGTH];
    }

    /**
     * getData() of args must be non-null
     */
    public int compare(Hash lhs, Hash rhs) {
        DataHelper.xor(lhs.getData(), 0, _base, 0, _lx, 0, Hash.HASH_LENGTH);
        DataHelper.xor(rhs.getData(), 0, _base, 0, _rx, 0, Hash.HASH_LENGTH);
        return DataHelper.compareTo(_lx, _rx);
    }
}
