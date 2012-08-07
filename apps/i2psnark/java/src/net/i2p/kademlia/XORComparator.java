package net.i2p.kademlia;

import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.SimpleDataStructure;

/**
 * Help sort Hashes in relation to a base key using the XOR metric
 *
 */
class XORComparator<T extends SimpleDataStructure> implements Comparator<T> {
    private final byte[] _base;

    /**
     * @param target key to compare distances with
     */
    public XORComparator(T target) {
        _base = target.getData();
    }

    public int compare(T lhs, T rhs) {
        byte lhsDelta[] = DataHelper.xor(lhs.getData(), _base);
        byte rhsDelta[] = DataHelper.xor(rhs.getData(), _base);
        return DataHelper.compareTo(lhsDelta, rhsDelta);
    }
}
