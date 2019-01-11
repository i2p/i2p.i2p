package net.i2p.router.transport.crypto;

import java.util.concurrent.LinkedBlockingQueue;

import com.southernstorm.noise.crypto.x25519.Curve25519;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 *  Try to keep DH pairs at the ready.
 *  It's important to do this in a separate thread, because if we run out,
 *  the pairs are generated in the NTCP Pumper thread,
 *  and it can fall behind.
 *
 *  @since 0.9.36 from DHSessionKeyFactory.PrecalcRunner
 */
public class X25519KeyFactory extends I2PThread {

    private final I2PAppContext _context;
    private final Log _log;
    private final int _minSize;
    private final int _maxSize;
    private final int _calcDelay;
    private final LinkedBlockingQueue<KeyPair> _keys;
    private volatile boolean _isRunning;
    private long _checkDelay = 10 * 1000;

    private final static String PROP_DH_PRECALC_MIN = "crypto.xdh.precalc.min";
    private final static String PROP_DH_PRECALC_MAX = "crypto.xdh.precalc.max";
    private final static String PROP_DH_PRECALC_DELAY = "crypto.xdh.precalc.delay";
    private final static int DEFAULT_DH_PRECALC_MIN = 20;
    private final static int DEFAULT_DH_PRECALC_MAX = 60;
    private final static int DEFAULT_DH_PRECALC_DELAY = 25;

    public X25519KeyFactory(I2PAppContext ctx) {
        super("XDH Precalc");
        _context = ctx;
        _log = ctx.logManager().getLog(X25519KeyFactory.class);
        ctx.statManager().createRateStat("crypto.XDHGenerateTime", "How long it takes to create x and X", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.XDHUsed", "Need a DH from the queue", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.XDHReused", "Unused DH requeued", "Encryption", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("crypto.XDHEmpty", "DH queue empty", "Encryption", new long[] { 60*60*1000 });

        // add to the defaults for every 128MB of RAM, up to 512MB
        long maxMemory = SystemVersion.getMaxMemory();
        int factor = (int) Math.max(1l, Math.min(4l, 1 + (maxMemory / (128*1024*1024l))));
        int defaultMin = DEFAULT_DH_PRECALC_MIN * factor;
        int defaultMax = DEFAULT_DH_PRECALC_MAX * factor;
        _minSize = ctx.getProperty(PROP_DH_PRECALC_MIN, defaultMin);
        _maxSize = ctx.getProperty(PROP_DH_PRECALC_MAX, defaultMax);
        _calcDelay = ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("XDH Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
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
    public KeyPair getKeys() {
        _context.statManager().addRateData("crypto.XDHUsed", 1);
        KeyPair rv = _keys.poll();
        if (rv == null) {
            _context.statManager().addRateData("crypto.XDHEmpty", 1);
            rv = precalc();
            // stop sleeping, wake up, make some more
            this.interrupt();
        }
        return rv;
    }

    private KeyPair precalc() {
        long start = System.currentTimeMillis();
        byte[] priv = new byte[32];
        do {
            _context.random().nextBytes(priv);
            // little endian, loop if too small
            // worth doing?
        } while (priv[31] == 0);
        byte[] pub = new byte[32];
        Curve25519.eval(pub, 0, priv, null);
        KeyPair rv = new KeyPair(new PublicKey(EncType.ECIES_X25519, pub), new PrivateKey(EncType.ECIES_X25519, priv));
        long end = System.currentTimeMillis();
        long diff = end - start;
        _context.statManager().addRateData("crypto.XDHGenerateTime", diff);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Took " + diff + "ms to generate local DH value");
        return rv;
    }

    /**
     * Return an unused DH key builder
     * to be put back onto the queue for reuse.
     */
    public void returnUnused(KeyPair kp) {
        _context.statManager().addRateData("crypto.XDHReused", 1);
        _keys.offer(kp);
    }

    /** @return true if successful, false if full */
    private final boolean addKeys(KeyPair kp) {
        return _keys.offer(kp);
    }

    private final int getSize() {
        return _keys.size();
    }

}
