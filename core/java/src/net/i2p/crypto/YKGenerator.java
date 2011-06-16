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
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 * Precalculate the Y and K for ElGamal encryption operations.
 *
 * This class precalcs a set of values on its own thread, using those transparently
 * when a new instance is created.  By default, the minimum threshold for creating 
 * new values for the pool is 20, and the max pool size is 50.  Whenever the pool has
 * less than the minimum, it fills it up again to the max.  There is a delay after 
 * each precalculation so that the CPU isn't hosed during startup.
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
    //private final static Log _log = new Log(YKGenerator.class);
    private static final int MIN_NUM_BUILDERS;
    private static final int MAX_NUM_BUILDERS;
    private static final int CALC_DELAY;
    private static final LinkedBlockingQueue<BigInteger[]> _values;
    private static Thread _precalcThread;
    private static final I2PAppContext ctx;
    private static volatile boolean _isRunning;

    public final static String PROP_YK_PRECALC_MIN = "crypto.yk.precalc.min";
    public final static String PROP_YK_PRECALC_MAX = "crypto.yk.precalc.max";
    public final static String PROP_YK_PRECALC_DELAY = "crypto.yk.precalc.delay";
    public final static int DEFAULT_YK_PRECALC_MIN = 20;
    public final static int DEFAULT_YK_PRECALC_MAX = 50;
    public final static int DEFAULT_YK_PRECALC_DELAY = 200;

    /** check every 30 seconds whether we have less than the minimum */
    private static long _checkDelay = 30 * 1000;

    static {
        ctx = I2PAppContext.getGlobalContext();

        // add to the defaults for every 128MB of RAM, up to 1GB
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 127*1024*1024l;
        int factor = (int) Math.max(1l, Math.min(8l, 1 + (maxMemory / (128*1024*1024l))));
        int defaultMin = DEFAULT_YK_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_YK_PRECALC_MAX * factor;
        MIN_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MIN, defaultMin);
        MAX_NUM_BUILDERS = ctx.getProperty(PROP_YK_PRECALC_MAX, defaultMax);

        CALC_DELAY = ctx.getProperty(PROP_YK_PRECALC_DELAY, DEFAULT_YK_PRECALC_DELAY);
        _values = new LinkedBlockingQueue(MAX_NUM_BUILDERS);

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("ElGamal YK Precalc (minimum: " + MIN_NUM_BUILDERS + " max: " + MAX_NUM_BUILDERS + ", delay: "
        //               + CALC_DELAY + ")");

        ctx.statManager().createRateStat("crypto.YKUsed", "Need a YK from the queue", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.YKEmpty", "YK queue empty", "Encryption", new long[] { 60*60*1000 });
        startPrecalc();
    }

    /** @since 0.8.8 */
    private static void startPrecalc() {
        synchronized(YKGenerator.class) {
            _precalcThread = new I2PThread(new YKPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS),
                                       "YK Precalc", true);
            _precalcThread.setPriority(Thread.MIN_PRIORITY);
            _isRunning = true;
            _precalcThread.start();
        }
    }

    /**
     *  Note that this stops the singleton precalc thread.
     *  You don't want to do this if there are multiple routers in the JVM.
     *  Fix this if you care. See Router.shutdown().
     *  @since 0.8.8
     */
    public static void shutdown() {
        _isRunning = false;
        _precalcThread.interrupt();
        _values.clear();
    }

    /**
     *  Only required if shutdown() previously called.
     *  @since 0.8.8
     */
    public static void restart() {
        synchronized(YKGenerator.class) {
            if (!_isRunning)
                startPrecalc();
        }
    }

    private static final int getSize() {
        return _values.size();
    }

    /** @return true if successful, false if full */
    private static final boolean addValues(BigInteger yk[]) {
        return _values.offer(yk);
    }

    /** @return rv[0] = Y; rv[1] = K */
    public static BigInteger[] getNextYK() {
        ctx.statManager().addRateData("crypto.YKUsed", 1, 0);
        BigInteger[] rv = _values.poll();
        if (rv != null)
            return rv;
        ctx.statManager().addRateData("crypto.YKEmpty", 1, 0);
        return generateYK();
    }

    private final static BigInteger _two = new NativeBigInteger(1, new byte[] { 0x02});

    /** @return rv[0] = Y; rv[1] = K */
    private static final BigInteger[] generateYK() {
        NativeBigInteger k = null;
        BigInteger y = null;
        //long t0 = 0;
        //long t1 = 0;
        while (k == null) {
            //t0 = Clock.getInstance().now();
            k = new NativeBigInteger(KeyGenerator.PUBKEY_EXPONENT_SIZE, ctx.random());
            //t1 = Clock.getInstance().now();
            if (BigInteger.ZERO.compareTo(k) == 0) {
                k = null;
                continue;
            }
            BigInteger kPlus2 = k.add(_two);
            if (kPlus2.compareTo(CryptoConstants.elgp) > 0) k = null;
        }
        //long t2 = Clock.getInstance().now();
        y = CryptoConstants.elgg.modPow(k, CryptoConstants.elgp);

        BigInteger yk[] = new BigInteger[2];
        yk[0] = y;
        yk[1] = k;

        //long diff = t2 - t0;
        //if (diff > 1000) {
        //    if (_log.shouldLog(Log.WARN)) _log.warn("Took too long to generate YK value for ElGamal (" + diff + "ms)");
        //}

        return yk;
    }

    public static void main(String args[]) {
        System.out.println("\n\n\n\nBegin test\n");
        long negTime = 0;
        for (int i = 0; i < 5; i++) {
            long startNeg = Clock.getInstance().now();
            getNextYK();
            long endNeg = Clock.getInstance().now();
            negTime += endNeg - startNeg;
        }
        // 173ms each on a 2008 netbook
        System.out.println("YK fetch time for 5 runs: " + negTime + " @ " + negTime / 5l + "ms each");
    }

    private static class YKPrecalcRunner implements Runnable {
        private final int _minSize;
        private final int _maxSize;

        private YKPrecalcRunner(int minSize, int maxSize) {
            _minSize = minSize;
            _maxSize = maxSize;
        }

        public void run() {
            while (_isRunning) {
                int curSize = 0;
                //long start = Clock.getInstance().now();
                int startSize = getSize();
                // Adjust delay
                if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000)
                    _checkDelay -= 1000;
                else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60*1000)
                    _checkDelay += 1000;
                curSize = startSize;
                if (curSize < _minSize) {
                    for (int i = curSize; i < _maxSize && _isRunning; i++) {
                        //long begin = Clock.getInstance().now();
                        if (!addValues(generateYK()))
                            break;
                        //long end = Clock.getInstance().now();
                        //if (_log.shouldLog(Log.DEBUG)) _log.debug("Precalculated YK value in " + (end - begin) + "ms");
                        // for some relief...
                        try {
                            Thread.sleep(CALC_DELAY);
                        } catch (InterruptedException ie) { // nop
                        }
                    }
                }
                //long end = Clock.getInstance().now();
                //int numCalc = curSize - startSize;
                //if (numCalc > 0) {
                //    if (_log.shouldLog(Log.DEBUG))
                //        _log.debug("Precalced " + numCalc + " to " + curSize + " in "
                //                   + (end - start - CALC_DELAY * numCalc) + "ms (not counting "
                //                   + (CALC_DELAY * numCalc) + "ms relief).  now sleeping");
                //}
                try {
                    Thread.sleep(_checkDelay);
                } catch (InterruptedException ie) { // nop
                }
            }
        }
    }
}
