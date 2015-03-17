package net.i2p.router.peermanager;

import java.util.Comparator;

import net.i2p.data.DataHelper;

/**
 * Order profiles by their speed (lowest first).
 * @since 0.7.10
 */
class SpeedComparator implements Comparator<PeerProfile> {

    public int compare(PeerProfile left, PeerProfile right) {

        double lval = left.getSpeedValue();
        double rval = right.getSpeedValue();

        if (lval > rval)
            return 1;
        if (lval < rval)
            return -1;

        // we don't wan't to return 0 so profiles don't vanish in the TreeSet
        lval = left.getCapacityValue();
        rval = right.getCapacityValue();
        if (lval > rval)
            return 1;
        if (lval < rval)
            return -1;
        return DataHelper.compareTo(right.getPeer().getData(), left.getPeer().getData());
    }
}
