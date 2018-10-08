package net.i2p.router.peermanager;

import java.io.Serializable;
import java.util.Comparator;

import net.i2p.data.DataHelper;

/**
 * Order profiles by their capacity, but backwards (highest capacity / value first).
 *
 */
class InverseCapacityComparator implements Comparator<PeerProfile>, Serializable {
    /**
     * Compare the two objects backwards.  The standard comparator returns
     * -1 if lhs is less than rhs, 1 if lhs is greater than rhs, or 0 if they're
     * equal.  To keep a strict ordering, we measure peers with equal capacity
     * values according to their speed
     *
     * @return -1 if the right hand side is smaller, 1 if the left hand side is
     *         smaller, or 0 if they are the same peer (Comparator.compare() inverted)
     */
    public int compare(PeerProfile left, PeerProfile right) {

        double rval = right.getCapacityValue();
        double lval = left.getCapacityValue();
        int rv = Double.compare(rval, lval);

        if (rv == 0) {
            rval = right.getSpeedValue();
            lval = left.getSpeedValue();
            rv = Double.compare(rval, lval);
            if (rv == 0) {
                // note the following call inverts right and left (see: classname)
                return DataHelper.compareTo(right.getPeer().getData(), left.getPeer().getData());
            } else {
                // ok, fall through and compare based on speed, since the capacity is equal
            }
        }

        return rv;
    }
}
