package net.i2p.router.peermanager;

import java.util.Comparator;

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
        return 0;
    }
}
