package net.i2p.router.crypto.pqc;

import java.security.GeneralSecurityException;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Try to keep key pairs at the ready.
 *  It's important to do this in a separate thread, because if we run out,
 *  the pairs are generated other threads,
 *  and it can fall behind.
 *
 *  Started by ECIESAEADEngine. One type per-thread. Only used for 768, for now.
 *
 *  @since 0.9.68 adapted from X25519KeyFactory
 */
public class MLKEMKeyFactory extends I2PThread implements KeyFactory {

    private final I2PAppContext _context;
    private final Log _log;
    private final int _minSize;
    private final int _maxSize;
    private final int _calcDelay;
    private final LinkedBlockingQueue<KeyPair> _keys;
    private final EncType _type;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000;

    private final static String PROP_MLKEM_PRECALC_MIN = "crypto.mlkem.precalc.min";
    private final static String PROP_MLKEM_PRECALC_MAX = "crypto.mlkem.precalc.max";
    private final static String PROP_MLKEM_PRECALC_DELAY = "crypto.mlkem.precalc.delay";
    // MLKEM-768 pair is 1184 + 2400 = 3584 byte so keep the queue relatively small
    private final static int DEFAULT_MLKEM_PRECALC_MIN = 4;
    private final static int DEFAULT_MLKEM_PRECALC_MAX = 12;
    private final static int DEFAULT_MLKEM_PRECALC_DELAY = 25;

    /**
     *  Alice side only
     *  @param type must be one of the internal types MLKEM*_INT
     */
    public MLKEMKeyFactory(I2PAppContext ctx, EncType type) {
        super("MLKEM Precalc");
        _context = ctx;
        _type = type;
        _log = ctx.logManager().getLog(MLKEMKeyFactory.class);
        ctx.statManager().createRateStat("crypto.MLKEMGenerateTime", "How long it takes to create keys", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.MLKEMUsed", "Take keys from the queue", "Encryption", new long[] { 60*60*1000 });
        //ctx.statManager().createRateStat("crypto.MLKEMReused", "Unused requeued", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.MLKEMEmpty", "Queue empty", "Encryption", new long[] { 60*60*1000 });

        // add to the defaults for every 128MB of RAM, up to 512MB
        long maxMemory = SystemVersion.getMaxMemory();
        int factor = (int) Math.max(1l, Math.min(4l, 1 + (maxMemory / (128*1024*1024l))));
        if (SystemVersion.isSlow())
            factor *= 2;
        int defaultMin = DEFAULT_MLKEM_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_MLKEM_PRECALC_MAX * factor;
        _minSize = ctx.getProperty(PROP_MLKEM_PRECALC_MIN, defaultMin);
        _maxSize = ctx.getProperty(PROP_MLKEM_PRECALC_MAX, defaultMax);
        _calcDelay = ctx.getProperty(PROP_MLKEM_PRECALC_DELAY, DEFAULT_MLKEM_PRECALC_DELAY);

        if (_log.shouldDebug())
            _log.debug("MLKEM Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
                       + _calcDelay + ")");
        _keys = new LinkedBlockingQueue<KeyPair>(_maxSize);
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
        } catch (GeneralSecurityException gse) {
            if (_isRunning)
                throw new IllegalStateException(gse);
        } catch (IllegalStateException ise) {
            if (_isRunning)
                throw ise;
        }
    }

    private void run2() throws GeneralSecurityException {
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
            } catch (InterruptedException ie) {}
        }
    }

    /**
     * Pulls a prebuilt keypair from the queue,
     * or if not available, construct a new one.
     */
    public KeyPair getKeys() {
        _context.statManager().addRateData("crypto.MLKEMUsed", 1);
        KeyPair rv = _keys.poll();
        if (rv == null) {
            _context.statManager().addRateData("crypto.MLKEMEmpty", 1);
            try {
                rv = precalc();
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            // stop sleeping, wake up, make some more
            this.interrupt();
        }
        return rv;
    }

    private KeyPair precalc() throws GeneralSecurityException {
        long start = System.currentTimeMillis();
        KeyPair rv = MLKEM.getKeys(_type);
        long end = System.currentTimeMillis();
        long diff = end - start;
        _context.statManager().addRateData("crypto.MLKEMGenerateTime", diff);
        return rv;
    }

    /**
     * Return an unused key pair
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(KeyPair kp) {
        _keys.offer(kp);
        //_context.statManager().addRateData("crypto.MLKEMReused", 1);
    }

    /** @return true if successful, false if full */
    private final boolean addKeys(KeyPair kp) {
        return _keys.offer(kp);
    }

    private final int getSize() {
        return _keys.size();
    }
}
