package net.i2p.router.crypto.ratchet;

import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Elligator2 for X25519 keys.
 *
 *  Try to keep DH pairs at the ready.
 *  It's important to do this in a separate thread, because if we run out,
 *  the pairs are generated in the NTCP Pumper thread,
 *  and it can fall behind.
 *
 *  @since 0.9.44 from X25519KeyFactory
 */
public class Elg2KeyFactory extends I2PThread implements KeyFactory {

    private final RouterContext _context;
    private final Log _log;
    private final Elligator2 _elg2;
    private final int _minSize;
    private final int _maxSize;
    private final int _calcDelay;
    private final LinkedBlockingQueue<Elg2KeyPair> _keys;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000;

    private final static String PROP_DH_PRECALC_MIN = "crypto.edh.precalc.min";
    private final static String PROP_DH_PRECALC_MAX = "crypto.edh.precalc.max";
    private final static String PROP_DH_PRECALC_DELAY = "crypto.edh.precalc.delay";
    private final static int DEFAULT_DH_PRECALC_MIN = 20;
    private final static int DEFAULT_DH_PRECALC_MAX = 60;
    private final static int DEFAULT_DH_PRECALC_DELAY = 25;
    private final boolean RETURN_UNUSED_TO_XDH;

    public Elg2KeyFactory(RouterContext ctx) {
        super("EDH Precalc");
        _context = ctx;
        _log = ctx.logManager().getLog(Elg2KeyFactory.class);
        _elg2 = new Elligator2(ctx);
        ctx.statManager().createRateStat("crypto.EDHGenerateTime", "How long it takes to create x and X", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.EDHUsed", "Need a DH from the queue", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.EDHReused", "Unused DH requeued", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.EDHEmpty", "DH queue empty", "Encryption", new long[] { 60*60*1000 });

        // add to the defaults for every 128MB of RAM, up to 512MB
        long maxMemory = SystemVersion.getMaxMemory();
        int factor = (int) Math.max(1l, Math.min(4l, 1 + (maxMemory / (128*1024*1024l))));
        boolean slow = SystemVersion.isSlow();
        RETURN_UNUSED_TO_XDH = slow;
        if (slow)
            factor *= 2;
        int defaultMin = DEFAULT_DH_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_DH_PRECALC_MAX * factor;
        _minSize = ctx.getProperty(PROP_DH_PRECALC_MIN, defaultMin);
        _maxSize = ctx.getProperty(PROP_DH_PRECALC_MAX, defaultMax);
        _calcDelay = ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("EDH Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
                       + _calcDelay + ")");
        _keys = new LinkedBlockingQueue<Elg2KeyPair>(_maxSize);
        if (!SystemVersion.isWindows())
            setPriority(Thread.NORM_PRIORITY - 1);
    }
        
    /**
     *  Note that this stops the singleton precalc thread.
     *  You don't want to do this if there are multiple routers in the JVM.
     *  Fix this if you care. See Router.shutdown().
     */
    public void shutdown() {
        _isRunning = false;
        this.interrupt();
        _keys.clear();
    }

    public void run() {
        try {
            run2();
        } catch (IllegalStateException ise) {
            if (_isRunning)
                throw ise;
            // else ignore, thread can be slow to shutdown on Android,
            // PRNG gets stopped first and throws ISE
        }
    }

    private void run2() {
        _isRunning = true;
        while (_isRunning) {
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
                    long curStart = System.currentTimeMillis();
                    if (!addKeys(precalc()))
                        break;
                    long curCalc = System.currentTimeMillis() - curStart;
                    // for some relief...
                    if (!interrupted()) {
                        try {
                            Thread.sleep(Math.min(200, Math.max(10, _calcDelay + (curCalc * 3))));
                        } catch (InterruptedException ie) {}
                    }
                }
            }
            if (!_isRunning)
                break;
            try {
                Thread.sleep(_checkDelay);
            } catch (InterruptedException ie) { // nop
            }
        }
    }

    /**
     * Pulls a prebuilt keypair from the queue,
     * or if not available, construct a new one.
     */
    public Elg2KeyPair getKeys() {
        _context.statManager().addRateData("crypto.EDHUsed", 1);
        Elg2KeyPair rv = _keys.poll();
        if (rv == null) {
            _context.statManager().addRateData("crypto.EDHEmpty", 1);
            rv = precalc();
            // stop sleeping, wake up, make some more
            this.interrupt();
        }
        return rv;
    }

    private Elg2KeyPair precalc() {
        long start = System.currentTimeMillis();
        KeyPair rv;
        byte[] enc;
        int i = 0;
        do {
            rv = _context.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
            enc = _elg2.encode(rv.getPublic());
            i++;
            if (enc == null && RETURN_UNUSED_TO_XDH)
                _context.commSystem().getXDHFactory().returnUnused(rv);
        } while (enc == null);
        long diff = System.currentTimeMillis() - start;
        _context.statManager().addRateData("crypto.EDHGenerateTime", diff);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Took " + i + " tries and " + diff + "ms to generate local DH value");
        return new Elg2KeyPair(rv.getPublic(), rv.getPrivate(), enc);
    }

    /**
     * Return an unused DH key builder
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(Elg2KeyPair kp) {
/*
        if (_keys.offer(kp))
            _context.statManager().addRateData("crypto.EDHReused", 1);
*/
    }

    /** @return true if successful, false if full */
    private final boolean addKeys(Elg2KeyPair kp) {
        return _keys.offer(kp);
    }

    private final int getSize() {
        return _keys.size();
    }

}
