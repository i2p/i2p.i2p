package net.i2p.syndie;

import java.util.Comparator;

import net.i2p.data.DataHelper;

/** sort ThreadNodeImpl instances with the highest entryId first */
public class NewestNodeFirstComparator implements Comparator {
    public int compare(Object lhs, Object rhs) {
        ThreadNodeImpl left = (ThreadNodeImpl)lhs;
        ThreadNodeImpl right = (ThreadNodeImpl)rhs;
        long l = left.getMostRecentPostDate();
        long r = right.getMostRecentPostDate();
        if (l > r) { 
            return -1;
        } else if (l == r) {
            // ok, the newest responses match, so lets fall back and compare the roots themselves
            l = left.getEntry().getEntryId();
            r = right.getEntry().getEntryId();
            if (l > r) {
                return -1;
            } else if (l == r) {
                return DataHelper.compareTo(left.getEntry().getKeyHash().getData(), right.getEntry().getKeyHash().getData());
            } else {
                return 1;
            }
        } else {
            return 1;
        }
    }
}
