package net.i2p.router.networkdb.kademlia;

import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Help sort Hashes in relation to a base key using the XOR metric
 *
 */
class XORComparator implements Comparator<Hash> {
    private final byte[] _base;

    /**
     * @param target key to compare distances with
     */
    public XORComparator(Hash target) {
        _base = target.getData();
    }

    public int compare(Hash lhs, Hash rhs) {
        byte lhsDelta[] = DataHelper.xor(lhs.getData(), _base);
        byte rhsDelta[] = DataHelper.xor(rhs.getData(), _base);
        return DataHelper.compareTo(lhsDelta, rhsDelta);
    }
}
