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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;

/**
 * Generate a new session key through a diffie hellman exchange.  This uses the
 * constants defined in CryptoConstants, which causes the exchange to create a 
 * 256 bit session key.
 *
 * This class precalcs a set of values on its own thread, using those transparently
 * when a new instance is created.  By default, the minimum threshold for creating 
 * new values for the pool is 5, and the max pool size is 10.  Whenever the pool has
 * less than the minimum, it fills it up again to the max.  There is a delay after 
 * each precalculation so that the CPU isn't hosed during startup (defaulting to 1 second).  
 * These three parameters are controlled by java environmental variables and 
 * can be adjusted via:
 *  -Dcrypto.dh.precalc.min=40 -Dcrypto.dh.precalc.max=100 -Dcrypto.dh.precalc.delay=60000
 *
 * (delay is milliseconds)
 *
 * To disable precalculation, set min to 0
 *
 * @author jrandom
 */
public class DHSessionKeyBuilder {
    private final static Log _log = new Log(DHSessionKeyBuilder.class);
    private static int MIN_NUM_BUILDERS = -1;
    private static int MAX_NUM_BUILDERS = -1;
    private static int CALC_DELAY = -1;
    private static volatile List _builders = new ArrayList(50);
    private static Thread _precalcThread = null;
    private BigInteger _myPrivateValue;
    private BigInteger _myPublicValue;
    private BigInteger _peerValue;
    private SessionKey _sessionKey;
    private ByteArray _extraExchangedBytes; // bytes after the session key from the DH exchange

    public final static String PROP_DH_PRECALC_MIN = "crypto.dh.precalc.min";
    public final static String PROP_DH_PRECALC_MAX = "crypto.dh.precalc.max";
    public final static String PROP_DH_PRECALC_DELAY = "crypto.dh.precalc.delay";
    public final static String DEFAULT_DH_PRECALC_MIN = "5";
    public final static String DEFAULT_DH_PRECALC_MAX = "10";
    public final static String DEFAULT_DH_PRECALC_DELAY = "1000";

    static {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_DH_PRECALC_MIN, DEFAULT_DH_PRECALC_MIN));
            MIN_NUM_BUILDERS = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_DH_PRECALC_MIN);
            MIN_NUM_BUILDERS = val;
        }
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_DH_PRECALC_MAX, DEFAULT_DH_PRECALC_MAX));
            MAX_NUM_BUILDERS = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_DH_PRECALC_MAX);
            MAX_NUM_BUILDERS = val;
        }
        try {
            int val = Integer.parseInt(ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY));
            CALC_DELAY = val;
        } catch (Throwable t) {
            int val = Integer.parseInt(DEFAULT_DH_PRECALC_DELAY);
            CALC_DELAY = val;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("DH Precalc (minimum: " + MIN_NUM_BUILDERS + " max: " + MAX_NUM_BUILDERS + ", delay: "
                       + CALC_DELAY + ")");

        _precalcThread = new I2PThread(new DHSessionKeyBuilderPrecalcRunner(MIN_NUM_BUILDERS, MAX_NUM_BUILDERS));
        _precalcThread.setName("DH Precalc");
        _precalcThread.setDaemon(true);
        _precalcThread.setPriority(Thread.MIN_PRIORITY);
        _precalcThread.start();
    }

    /**
     * Construct a new DH key builder
     *
     */
    public DHSessionKeyBuilder() {
        this(false);
        DHSessionKeyBuilder builder = null;
        synchronized (_builders) {
            if (_builders.size() > 0) {
                builder = (DHSessionKeyBuilder) _builders.remove(0);
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Removing a builder.  # left = " + _builders.size());
            } else {
                if (_log.shouldLog(Log.WARN)) _log.warn("NO MORE BUILDERS!  creating one now");
            }
        }
        if (builder != null) {
            _myPrivateValue = builder._myPrivateValue;
            _myPublicValue = builder._myPublicValue;
            _peerValue = builder._peerValue;
            _sessionKey = builder._sessionKey;
            _extraExchangedBytes = builder._extraExchangedBytes;
        } else {
            _myPrivateValue = null;
            _myPublicValue = null;
            _peerValue = null;
            _sessionKey = null;
            _myPublicValue = generateMyValue();
            _extraExchangedBytes = new ByteArray();
        }
    }

    public DHSessionKeyBuilder(boolean usePool) {
        _myPrivateValue = null;
        _myPublicValue = null;
        _peerValue = null;
        _sessionKey = null;
        _extraExchangedBytes = new ByteArray();
    }
    
    /**
     * Conduct a DH exchange over the streams, returning the resulting data.
     *
     * @return exchanged data
     * @throws IOException if there is an error (but does not close the streams
     */
    public static DHSessionKeyBuilder exchangeKeys(InputStream in, OutputStream out) throws IOException {
        DHSessionKeyBuilder builder = new DHSessionKeyBuilder();
        
        // send: X
        writeBigI(out, builder.getMyPublicValue());
        
        // read: Y
        BigInteger Y = readBigI(in);
        if (Y == null) return null;
        builder.setPeerPublicValue(Y);
        return builder;
    }
    
    static BigInteger readBigI(InputStream in) throws IOException {
        byte Y[] = new byte[256];
        int read = DataHelper.read(in, Y);
        if (read != 256) {
            return null;
        }
        if (1 == (Y[0] & 0x80)) {
            // high bit set, need to inject an additional byte to keep 2s complement
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("High bit set");
            byte Y2[] = new byte[257];
            System.arraycopy(Y, 0, Y2, 1, 256);
            Y = Y2;
        }
        return new NativeBigInteger(Y);
    }
    
    /**
     * Write out the integer as a 256 byte value.  This left pads with 0s so 
     * to keep in 2s complement, and if it is already 257 bytes (due to
     * the sign bit) ignore that first byte.
     */
    static void writeBigI(OutputStream out, BigInteger val) throws IOException {
        byte x[] = val.toByteArray();
        for (int i = x.length; i < 256; i++) 
            out.write(0);
        if (x.length == 257)
            out.write(x, 1, 256);
        else if (x.length == 256)
            out.write(x);
        else if (x.length > 257)
            throw new IllegalArgumentException("Value is too large!  length="+x.length);
        
        out.flush();
    }
    
    private static final int getSize() {
        synchronized (_builders) {
            return _builders.size();
        }
    }

    private static final int addBuilder(DHSessionKeyBuilder builder) {
        int sz = 0;
        synchronized (_builders) {
            _builders.add(builder);
            sz = _builders.size();
        }
        return sz;
    }

    /**
     * Create a new private value for the DH exchange, and return the number to
     * be exchanged, leaving the actual private value accessible through getMyPrivateValue()
     *
     */
    public BigInteger generateMyValue() {
        long start = Clock.getInstance().now();
        _myPrivateValue = new NativeBigInteger(2048, RandomSource.getInstance());
        BigInteger myValue = CryptoConstants.elgg.modPow(_myPrivateValue, CryptoConstants.elgp);
        long end = Clock.getInstance().now();
        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Took more than a second (" + diff + "ms) to generate local DH value");
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Took " + diff + "ms to generate local DH value");
        }
        return myValue;
    }

    /**
     * Retrieve the private value used by the local participant in the DH exchange
     */
    public BigInteger getMyPrivateValue() {
        return _myPrivateValue;
    }

    /**
     * Retrieve the public value used by the local participant in the DH exchange,
     * generating it if necessary
     */
    public BigInteger getMyPublicValue() {
        if (_myPublicValue == null) _myPublicValue = generateMyValue();
        return _myPublicValue;
    }

    /**
     * Specify the value given by the peer for use in the session key negotiation
     *
     */
    public void setPeerPublicValue(BigInteger peerVal) {
        _peerValue = peerVal;
    }

    public BigInteger getPeerPublicValue() {
        return _peerValue;
    }

    /**
     * Retrieve the session key, calculating it if necessary (and if possible).
     *
     * @return session key exchanged, or null if the exchange is not complete
     */
    public SessionKey getSessionKey() {
        if (_sessionKey != null) return _sessionKey;
        if (_peerValue != null) {
            if (_myPrivateValue == null) generateMyValue();
            _sessionKey = calculateSessionKey(_myPrivateValue, _peerValue);
        } else {
            System.err.println("Not ready yet.. privateValue and peerValue must be set ("
                               + (_myPrivateValue != null ? "set" : "null") + ","
                               + (_peerValue != null ? "set" : "null") + ")");
        }
        return _sessionKey;
    }

    /**
     * Retrieve the extra bytes beyond the session key resulting from the DH exchange.
     * If there aren't enough bytes (with all of them being consumed by the 32 byte key),
     * the SHA256 of the key itself is used.
     *
     */
    public ByteArray getExtraBytes() {
        return _extraExchangedBytes;
    }

    /**
     * Calculate a session key based on the private value and the public peer value
     *
     */
    private final SessionKey calculateSessionKey(BigInteger myPrivateValue, BigInteger publicPeerValue) {
        long start = Clock.getInstance().now();
        SessionKey key = new SessionKey();
        BigInteger exchangedKey = publicPeerValue.modPow(myPrivateValue, CryptoConstants.elgp);
        byte buf[] = exchangedKey.toByteArray();
        byte val[] = new byte[32];
        if (buf.length < val.length) {
            System.arraycopy(buf, 0, val, 0, buf.length);
            byte remaining[] = SHA256Generator.getInstance().calculateHash(val).getData();
            _extraExchangedBytes.setData(remaining);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Storing " + remaining.length + " bytes from the DH exchange by SHA256 the session key");
        } else { // (buf.length >= val.length) 
            System.arraycopy(buf, 0, val, 0, val.length);
            byte remaining[] = new byte[buf.length - val.length];
            System.arraycopy(buf, val.length, remaining, 0, remaining.length);
            _extraExchangedBytes.setData(remaining);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Storing " + remaining.length + " bytes from the end of the DH exchange");
        }
        key.setData(val);
        long end = Clock.getInstance().now();
        long diff = end - start;
        if (diff > 1000) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Generating session key took too long (" + diff + " ms");
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Generating session key " + diff + " ms");
        }
        return key;
    }

    public static void main(String args[]) {
        RandomSource.getInstance().nextBoolean(); // warm it up
        try {
            Thread.sleep(20 * 1000);
        } catch (InterruptedException ie) { // nop
        }
        I2PAppContext ctx = new I2PAppContext();
        _log.debug("\n\n\n\nBegin test\n");
        long negTime = 0;
        for (int i = 0; i < 5; i++) {
            long startNeg = Clock.getInstance().now();
            DHSessionKeyBuilder builder1 = new DHSessionKeyBuilder();
            DHSessionKeyBuilder builder2 = new DHSessionKeyBuilder();
            BigInteger pub1 = builder1.getMyPublicValue();
            builder2.setPeerPublicValue(pub1);
            BigInteger pub2 = builder2.getMyPublicValue();
            builder1.setPeerPublicValue(pub2);
            SessionKey key1 = builder1.getSessionKey();
            SessionKey key2 = builder2.getSessionKey();
            long endNeg = Clock.getInstance().now();
            negTime += endNeg - startNeg;

            if (!key1.equals(key2))
                _log.error("**ERROR: Keys do not match");
            else
                _log.debug("**Success: Keys match");

            byte iv[] = new byte[16];
            RandomSource.getInstance().nextBytes(iv);
            String origVal = "1234567890123456"; // 16 bytes max using AESEngine
            byte enc[] = new byte[16];
            byte dec[] = new byte[16];
            ctx.aes().encrypt(origVal.getBytes(), 0, enc, 0, key1, iv, 16);
            ctx.aes().decrypt(enc, 0, dec, 0, key2, iv, 16);
            String tranVal = new String(dec);
            if (origVal.equals(tranVal))
                _log.debug("**Success: D(E(val)) == val");
            else
                _log.error("**ERROR: D(E(val)) != val [val=(" + tranVal + "), origVal=(" + origVal + ")");
        }
        _log.debug("Negotiation time for 5 runs: " + negTime + " @ " + negTime / 5l + "ms each");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) { // nop
        }
    }

    private static class DHSessionKeyBuilderPrecalcRunner implements Runnable {
        private int _minSize;
        private int _maxSize;

        private DHSessionKeyBuilderPrecalcRunner(int minSize, int maxSize) {
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
                        curSize = addBuilder(precalc(curSize));
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
                    Thread.sleep(30 * 1000);
                } catch (InterruptedException ie) { // nop
                }
            }
        }

        private DHSessionKeyBuilder precalc(int i) {
            DHSessionKeyBuilder builder = new DHSessionKeyBuilder(false);
            builder.getMyPublicValue();
            //_log.debug("Precalc " + i + " complete");
            return builder;
        }
    }
}