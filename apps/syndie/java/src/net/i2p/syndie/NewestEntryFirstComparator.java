package net.i2p.syndie;

import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.syndie.data.BlogURI;

/** sort BlogURI instances with the highest entryId first */
public class NewestEntryFirstComparator implements Comparator {
    public int compare(Object lhs, Object rhs) {
        BlogURI left = (BlogURI)lhs;
        BlogURI right = (BlogURI)rhs;
        if (left.getEntryId() > right.getEntryId()) {
            return -1;
        } else if (left.getEntryId() == right.getEntryId()) {
            return DataHelper.compareTo(left.getKeyHash().getData(), right.getKeyHash().getData());
        } else {
            return 1;
        }
    }
}
