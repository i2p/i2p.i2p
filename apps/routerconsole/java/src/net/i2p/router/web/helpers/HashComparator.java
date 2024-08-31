package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 *  Sorts in true binary order, not Base64 string order.
 *  A-Z a-z 0-9 -~
 *
 *  @since 0.9.64 moved from BanlistRenderer
 */
class HashComparator implements Comparator<Hash>, Serializable {
    public static final HashComparator _instance = new HashComparator();

    /**
     * Thread safe, no state
     */
    public static HashComparator getInstance() { return _instance; }

    public int compare(Hash l, Hash r) {
        return DataHelper.compareTo(l.getData(), r.getData());
    }

    public static int comp(Hash l, Hash r) {
        return DataHelper.compareTo(l.getData(), r.getData());
    }
}
