package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;

/**
 * Precalculate the Y and K for ElGamal encryption operations.
 *
 * This class precalcs a set of values on its own thread, using those transparently
 * when a new instance is created.  By default, the minimum threshold for creating 
 * new values for the pool is 5, and the max pool size is 10.  Whenever the pool has
 * less than the minimum, it fills it up again to the max.  There is a delay after 
 * each precalculation so that the CPU isn't hosed during startup (defaulting to 10 seconds).  
 * These three parameters are controlled by java environmental variables and 
 * can be adjusted via:
 *  -Dcrypto.yk.precalc.min=40 -Dcrypto.yk.precalc.max=100 -Dcrypto.yk.precalc.delay=60000
 *
 * (delay is milliseconds)
 *
 * To disable precalculation, set min to 0
 *
 * @author jrandom
 */
class YKGenerator {
    private final static Log _log = new Log(YKGenerator.class);
    private static int MIN_NUM_BUILDERS = -1;
    private static int MAX_NUM_BUILDERS = -1;
    private static int CALC_DELAY = -1;
    private static volatile List _values = new ArrayList(50); // list of BigInteger[] values (y and k)
    private static Thread _precalcThread = null;

    public final static String PROP_YK_PRECALC_MIN = "crypto.yk.precalc.min";
    public final static String PROP_YK_PRECALC_MAX = "crypto.yk.precalc.max";
    public final static String PROP_YK_PRECALC_DELAY = "crypto.yk.precalc.delay";
    public final static String DEFAULT_YK_PRECALC_MIN = "10";
    public final static String DEFAULT_YK_PRECALC_MAX = "30";
    public final static String DEFAULT_YK_PRECALC_DELAY = "10000";

    /** check every 30 seconds whether we have less than the minimum */
    private final static long CHECK_DELAY = 30 * 1000;

    static {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_YK_PRECALC_MIN, DEFAULT_YK_PRECALC_MIN));
            MIN_NUM_BUILDERS = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_YK_PRECALC_MIN);
            MIN_NUM_BUILDERS = val;
        }
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_YK_PRECALC_MAX, DEFAULT_YK_PRECALC_MAX));
            MAX_NUM_BUILDERS = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_YK_PRECALC_MAX);
            MAX_NUM_BUILDERS = val;
        }
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_YK_PRECALC_DELAY, DEFAULT_YK_PRECALC_DELAY));
            CALC_DELAY = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_YK_PRECALC_DELAY);
            CALC_DELAY = val;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("ElGamal YK Precalc (minimum: " + MIN_NUM_BUILDERS + " max: " + MAX_NUM_BUILDERS + ", delay: "
                       + CALC_DELAY + ")");

        _precalcThread = new I2PThread(new YKPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS));
        _precalcThread.setName("YK Precalc");
        _precalcThread.setDaemon(true);
        _precalcThread.setPriority(Thread.MIN_PRIORITY);
        _precalcThread.start();
    }

    private static final int getSize() {
        synchronized (_values) {
            return _values.size();
        }
    }

    private static final int addValues(BigInteger yk[]) {
        int sz = 0;
        synchronized (_values) {
            _values.add(yk);
            sz = _values.size();
        }
        return sz;
    }

    public static BigInteger[] getNextYK() {
        if (true) {
            synchronized (_values) {
                if (_values.size() > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Sufficient precalculated YK values - fetch the existing");
                    return (BigInteger[]) _values.remove(0);
                }
            }
        }
        if (_log.shouldLog(Log.INFO)) _log.info("Insufficient precalculated YK values - create a new one");
        return generateYK();
    }

    private final static BigInteger _two = new NativeBigInteger(1, new byte[] { 0x02});

    private static final BigInteger[] generateYK() {
        NativeBigInteger k = null;
        BigInteger y = null;
        long t0 = 0;
        long t1 = 0;
        while (k == null) {
            t0 = Clock.getInstance().now();
            k = new NativeBigInteger(KeyGenerator.PUBKEY_EXPONENT_SIZE, RandomSource.getInstance());
            t1 = Clock.getInstance().now();
            if (BigInteger.ZERO.compareTo(k) == 0) {
                k = null;
                continue;
            }
            BigInteger kPlus2 = k.add(_two);
            if (kPlus2.compareTo(CryptoConstants.elgp) > 0) k = null;
        }
        long t2 = Clock.getInstance().now();
        y = CryptoConstants.elgg.modPow(k, CryptoConstants.elgp);

        BigInteger yk[] = new BigInteger[2];
        yk[0] = y;
        yk[1] = k;

        long diff = t2 - t0;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to generate YK value for ElGamal (" + diff + "ms)");
        }

        return yk;
    }

    public static void main(String args[]) {
        RandomSource.getInstance().nextBoolean(); // warm it up
        try {
            Thread.sleep(20 * 1000);
        } catch (InterruptedException ie) { // nop
        }
        _log.debug("\n\n\n\nBegin test\n");
        long negTime = 0;
        for (int i = 0; i < 5; i++) {
            long startNeg = Clock.getInstance().now();
            getNextYK();
            long endNeg = Clock.getInstance().now();
        }
        _log.debug("YK fetch time for 5 runs: " + negTime + " @ " + negTime / 5l + "ms each");
        try {
            Thread.sleep(30 * 1000);
        } catch (InterruptedException ie) { // nop
        }
    }

    private static class YKPrecalcRunner implements Runnable {
        private int _minSize;
        private int _maxSize;

        private YKPrecalcRunner(int minSize, int maxSize) {
            _minSize = minSize;
            _maxSize = maxSize;
        }

        public void run() {
            while (true) {
                int curSize = 0;
                long start = Clock.getInstance().now();
                int startSize = getSize();
                curSize = startSize;
                while (curSize < _minSize) {
                    while (curSize < _maxSize) {
                        long begin = Clock.getInstance().now();
                        curSize = addValues(generateYK());
                        long end = Clock.getInstance().now();
                        if (_log.shouldLog(Log.DEBUG)) _log.debug("Precalculated YK value in " + (end - begin) + "ms");
                        // for some relief...
                        try {
                            Thread.sleep(CALC_DELAY);
                        } catch (InterruptedException ie) { // nop
                        }
                    }
                }
                long end = Clock.getInstance().now();
                int numCalc = curSize - startSize;
                if (numCalc > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Precalced " + numCalc + " to " + curSize + " in "
                                   + (end - start - CALC_DELAY * numCalc) + "ms (not counting "
                                   + (CALC_DELAY * numCalc) + "ms relief).  now sleeping");
                }
                try {
                    Thread.sleep(CHECK_DELAY);
                } catch (InterruptedException ie) { // nop
                }
            }
        }
    }
}