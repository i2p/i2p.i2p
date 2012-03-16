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
    private final int MIN_NUM_BUILDERS;
    private final int MAX_NUM_BUILDERS;
    private final int CALC_DELAY;
    private final LinkedBlockingQueue<BigInteger[]> _values;
    private final Thread _precalcThread;
    private final I2PAppContext ctx;
    private volatile boolean _isRunning;

    public final static String PROP_YK_PRECALC_MIN = "crypto.yk.precalc.min";
    public final static String PROP_YK_PRECALC_MAX = "crypto.yk.precalc.max";
    public final static String PROP_YK_PRECALC_DELAY = "crypto.yk.precalc.delay";
    public final static int DEFAULT_YK_PRECALC_MIN = 20;
    public final static int DEFAULT_YK_PRECALC_MAX = 50;
    public final static int DEFAULT_YK_PRECALC_DELAY = 200;

    public YKGenerator(I2PAppContext context) {
        ctx = context;

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
        _precalcThread = new I2PThread(new YKPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS),
                                       "YK Precalc", true);
        _precalcThread.setPriority(Thread.MIN_PRIORITY);
        _isRunning = true;
        _precalcThread.start();
    }

    /**
     *  Note that this stops the precalc thread
     *  and it cannot be restarted.
     *  @since 0.8.8
     */
    public void shutdown() {
        _isRunning = false;
        _precalcThread.interrupt();
        _values.clear();
    }

    private final int getSize() {
        return _values.size();
    }

    /** @return true if successful, false if full */
    private final boolean addValues(BigInteger yk[]) {
        return _values.offer(yk);
    }

    /** @return rv[0] = Y; rv[1] = K */
    public BigInteger[] getNextYK() {
        ctx.statManager().addRateData("crypto.YKUsed", 1, 0);
        BigInteger[] rv = _values.poll();
        if (rv != null)
            return rv;
        ctx.statManager().addRateData("crypto.YKEmpty", 1, 0);
        return generateYK();
    }

    private final static BigInteger _two = new NativeBigInteger(1, new byte[] { 0x02});

    /** @return rv[0] = Y; rv[1] = K */
    private final BigInteger[] generateYK() {
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

/****
    private static final int RUNS = 500;

    public static void main(String args[]) {
        // warmup crypto
        ctx.random().nextInt();
        System.out.println("Begin YK generator speed test");
        long startNeg = Clock.getInstance().now();
        for (int i = 0; i < RUNS; i++) {
            getNextYK();
        }
        long endNeg = Clock.getInstance().now();
        long  negTime = endNeg - startNeg;
        // 14 ms each on a 2008 netbook (with jbigi)
        System.out.println("YK fetch time for " + RUNS + " runs: " + negTime + " @ " + (negTime / RUNS) + "ms each");
    }
****/

    /** the thread */
    private class YKPrecalcRunner implements Runnable {
        private final int _minSize;
        private final int _maxSize;

        /** check every 30 seconds whether we have less than the minimum */
        private long _checkDelay = 30 * 1000;

        private YKPrecalcRunner(int minSize, int maxSize) {
            _minSize = minSize;
            _maxSize = maxSize;
        }

        public void run() {
            while (_isRunning) {
                //long start = Clock.getInstance().now();
                int startSize = getSize();
                // Adjust delay
                if (startSize <= (_minSize * 2 / 3) && _checkDelay > 1000)
                    _checkDelay -= 1000;
                else if (startSize > (_minSize * 3 / 2) && _checkDelay < 60*1000)
                    _checkDelay += 1000;
                if (startSize < _minSize) {
                    // fill all the way up, do the check here so we don't
                    // throw away one when full in addValues()
                    while (getSize() < _maxSize && _isRunning) {
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
                if (!_isRunning)
                    break;
                try {
                    Thread.sleep(_checkDelay);
                } catch (InterruptedException ie) { // nop
                }
            }
        }
    }
}
