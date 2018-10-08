package net.i2p.router.peermanager;

import java.io.Serializable;
import java.util.Comparator;

import net.i2p.data.DataHelper;

/**
 * Order profiles by their speed (lowest first).
 * @since 0.7.10
 */
class SpeedComparator implements Comparator<PeerProfile>, Serializable {

    public int compare(PeerProfile left, PeerProfile right) {

        double lval = left.getSpeedValue();
        double rval = right.getSpeedValue();
        int rv = Double.compare(lval, rval);
        if (rv != 0)
            return rv;

        // we don't wan't to return 0 so profiles don't vanish in the TreeSet
        lval = left.getCapacityValue();
        rval = right.getCapacityValue();
        rv = Double.compare(lval, rval);
        if (rv != 0)
            return rv;
        return DataHelper.compareTo(right.getPeer().getData(), left.getPeer().getData());
    }
}
