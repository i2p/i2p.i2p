package net.i2p.router.networkdb.kademlia;

import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Help sort Hashes in relation to a base key using the XOR metric
 *
 */
class XORComparator implements Comparator {
    private Hash _base;
    /**
     * @param target key to compare distances with
     */
    public XORComparator(Hash target) {
        _base = target;
    }
    public int compare(Object lhs, Object rhs) {
        if (lhs == null) throw new NullPointerException("LHS is null");
        if (rhs == null) throw new NullPointerException("RHS is null");
        if ( (lhs instanceof Hash) && (rhs instanceof Hash) ) {
            byte lhsDelta[] = DataHelper.xor(((Hash)lhs).getData(), _base.getData());
            byte rhsDelta[] = DataHelper.xor(((Hash)rhs).getData(), _base.getData());
            return DataHelper.compareTo(lhsDelta, rhsDelta);
        } else {
            throw new ClassCastException(lhs.getClass().getName() + " / " + rhs.getClass().getName());
        }
    }
}
