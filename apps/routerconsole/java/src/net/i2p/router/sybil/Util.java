package net.i2p.router.sybil;

import java.math.BigInteger;

/**
 * For NetDbRenderer and Sybil
 * http://forums.sun.com/thread.jspa?threadID=597652
 * @since 0.9.38 moved from NetDbRenderer
 */
public class Util {

    /**
     * For debugging
     * http://forums.sun.com/thread.jspa?threadID=597652
     * @since 0.7.14
     */
    public static double biLog2(BigInteger a) {
        int b = a.bitLength() - 1;
        double c = 0;
        double d = 0.5;
        for (int i = b; i >= 0; --i) {
             if (a.testBit(i))
                 c += d;
             d /= 2;
        }
        return b + c;
    }
}
