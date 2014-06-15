package net.i2p.kademlia;

import java.io.Serializable;
import java.util.Comparator;

import net.i2p.data.SimpleDataStructure;

/**
 * Help sort Hashes in relation to a base key using the XOR metric
 *
 * @since 0.9.2 in i2psnark, moved to core in 0.9.10
 */
public class XORComparator<T extends SimpleDataStructure> implements Comparator<T>, Serializable {
    private final byte[] _base;

    /**
     * @param target key to compare distances with
     */
    public XORComparator(T target) {
        _base = target.getData();
    }

    public int compare(T lhs, T rhs) {
        // same as the following but byte-by-byte for efficiency
        //byte lhsDelta[] = DataHelper.xor(lhs.getData(), _base);
        //byte rhsDelta[] = DataHelper.xor(rhs.getData(), _base);
        //return DataHelper.compareTo(lhsDelta, rhsDelta);
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
