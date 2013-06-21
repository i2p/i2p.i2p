package net.i2p.router.networkdb.kademlia;

import java.util.Comparator;

import net.i2p.data.Hash;

/**
 * Help sort Hashes in relation to a base key using the XOR metric.
 */
class XORComparator implements Comparator<Hash> {
    private final byte[] _base;

    /**
     * @param target key to compare distances with
     */
    public XORComparator(Hash target) {
        _base = target.getData();
    }

    /**
     * getData() of args must be non-null
     */
    public int compare(Hash lhs, Hash rhs) {
        byte lhsb[] = lhs.getData();
        byte rhsb[] = rhs.getData();
        for (int i = 0; i < _base.length; i++) {
            int ld = (lhsb[i] ^ _base[i]) & 0xff;
            int rd = (rhsb[i] ^ _base[i]) & 0xff;
            if (ld < rd)
                return -1;
            if (ld > rd)
                return 1;
        }
        return 0;
    }
}
