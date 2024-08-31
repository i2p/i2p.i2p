package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Comparator;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;

/**
 *  Sorts in true binary order, not Base64 string order.
 *  A-Z a-z 0-9 -~
 *
 *  @since 0.9.64
 */
class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
     private static final long serialVersionUID = 1;
     public static final RouterInfoComparator _instance = new RouterInfoComparator();

     /**
      * Thread safe, no state
      */
     public static RouterInfoComparator getInstance() { return _instance; }

     /**
      * @param l non-null
      * @param r non-null
      */
    public int compare(RouterInfo l, RouterInfo r) {
        Hash lh = l.getIdentity().getHash();
        Hash rh = r.getIdentity().getHash();
        return HashComparator.comp(lh, rh);
    }

     /**
      * @param l non-null
      * @param r non-null
      */
    public static int comp(RouterInfo l, RouterInfo r) {
        Hash lh = l.getIdentity().getHash();
        Hash rh = r.getIdentity().getHash();
        return HashComparator.comp(lh, rh);
    }
}
