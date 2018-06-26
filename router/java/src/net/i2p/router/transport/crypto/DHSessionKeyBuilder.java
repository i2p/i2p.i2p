package net.i2p.router.transport.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

//import java.io.InputStream;
//import java.io.OutputStream;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.crypto.CryptoConstants;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigUtil;
import net.i2p.data.ByteArray;
//import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Generate a new session key through a diffie hellman exchange.  This uses the
 * constants defined in CryptoConstants, which causes the exchange to create a 
 * 256 bit session key.
 *
 * This class precalcs a set of values on its own thread.
 * Whenever the pool has
 * less than the minimum, it fills it up again to the max.  There is a delay after 
 * each precalculation so that the CPU isn't hosed during startup.  
 * These three parameters are controlled by java environmental variables and 
 * can be adjusted via:
 *  -Dcrypto.dh.precalc.min=40 -Dcrypto.dh.precalc.max=100 -Dcrypto.dh.precalc.delay=60000
 *
 * (delay is milliseconds)
 *
 * To disable precalculation, set min to 0
 *
 * @since 0.9 moved from net.i2p.crypto
 *
 * @author jrandom
 */
public class DHSessionKeyBuilder {

    // the data of importance
    private final BigInteger _myPrivateValue;
    private final BigInteger _myPublicValue;
    private BigInteger _peerValue;
    private SessionKey _sessionKey;
    private final ByteArray _extraExchangedBytes; // bytes after the session key from the DH exchange

    private final static String PROP_DH_PRECALC_MIN = "crypto.dh.precalc.min";
    private final static String PROP_DH_PRECALC_MAX = "crypto.dh.precalc.max";
    private final static String PROP_DH_PRECALC_DELAY = "crypto.dh.precalc.delay";
    private final static int DEFAULT_DH_PRECALC_MIN = 20;
    private final static int DEFAULT_DH_PRECALC_MAX = 60;
    private final static int DEFAULT_DH_PRECALC_DELAY = 25;

    /**
     * Create a new public/private value pair for the DH exchange.
     * Only for internal use and unit tests.
     * Others should get instances from PrecalcRunner.getBuilder()
     */
    DHSessionKeyBuilder() {
        this(I2PAppContext.getGlobalContext());
    }

    /**
     * Create a new public/private value pair for the DH exchange.
     * Only for internal use and unit tests.
     * Others should get instances from PrecalcRunner.getBuilder()
     */
    DHSessionKeyBuilder(I2PAppContext ctx) {
        _myPrivateValue = new NativeBigInteger(ctx.keyGenerator().getElGamalExponentSize(), ctx.random());
        _myPublicValue = CryptoConstants.elgg.modPow(_myPrivateValue, CryptoConstants.elgp);
        _extraExchangedBytes = new ByteArray();
    }
    
    /**
     * Conduct a DH exchange over the streams, returning the resulting data.
     *
     * unused
     * @return exchanged data
     * @throws IOException if there is an error (but does not close the streams
     */
/****
    public static DHSessionKeyBuilder exchangeKeys(InputStream in, OutputStream out) throws IOException {
        DHSessionKeyBuilder builder = new DHSessionKeyBuilder();
        
        // send: X
        writeBigI(out, builder.getMyPublicValue());
        
        // read: Y
        BigInteger Y = readBigI(in);
        if (Y == null) return null;
        try {
            builder.setPeerPublicValue(Y);
            return builder;
        } catch (InvalidPublicParameterException ippe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Key exchange failed (hostile peer?)", ippe);
            return null;
        }
    }
****/
    
    /**
     * unused
     */
/****
    private static BigInteger readBigI(InputStream in) throws IOException {
        byte Y[] = new byte[256];
        int read = DataHelper.read(in, Y);
        if (read != 256) {
            return null;
        }
        return new NativeBigInteger(1, Y);
    }
****/
    
    /**
     * Write out the integer as a 256 byte value.  This left pads with 0s so 
     * to keep in 2s complement, and if it is already 257 bytes (due to
     * the sign bit) ignore that first byte.
     *
     * unused
     */
/****
    private static void writeBigI(OutputStream out, BigInteger val) throws IOException {
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
****/
    
    /**
     * Retrieve the private value used by the local participant in the DH exchange
     * unused
     */
/*
    public BigInteger getMyPrivateValue() {
        return _myPrivateValue;
    }
*/

    /**
     * Retrieve the public value used by the local participant in the DH exchange,
     */
    public BigInteger getMyPublicValue() {
        return _myPublicValue;
    }

    /**
     * Return a 256 byte representation of our public key, with leading 0s 
     * if necessary.
     *
     */
    public byte[] getMyPublicValueBytes() {
        return toByteArray(getMyPublicValue());
    }
    
    /**
     *  @return exactly 256 bytes
     *  @throws IllegalArgumentException if requires more than 256 bytes
     */
    private static final byte[] toByteArray(BigInteger bi) {
        try {
            return SigUtil.rectify(bi, 256);
        } catch (InvalidKeyException ike) {
            throw new IllegalArgumentException(ike);
        }
    }

    /**
     * Specify the value given by the peer for use in the session key negotiation
     * @throws IllegalStateException if already set
     */
    public synchronized void setPeerPublicValue(BigInteger peerVal) throws InvalidPublicParameterException {
        if (_peerValue != null) {
            if (!_peerValue.equals(peerVal))
                throw new IllegalStateException();
            return;
        }
        validatePublic(peerVal);
        _peerValue = peerVal;
    }

    /**
     *  @param val 256 bytes
     */
    public void setPeerPublicValue(byte val[]) throws InvalidPublicParameterException {
        if (val.length != 256)
            throw new IllegalArgumentException("Peer public value must be exactly 256 bytes");
        setPeerPublicValue(new NativeBigInteger(1, val));
    }

    public synchronized BigInteger getPeerPublicValue() {
        return _peerValue;
    }

    /**
     * Return a 256 byte representation of his public key, with leading 0s 
     * if necessary.
     *
     */
    public byte[] getPeerPublicValueBytes() {
        return toByteArray(getPeerPublicValue());
    }

    /**
     * Retrieve the session key, calculating it if necessary (and if possible).
     *
     * @return session key exchanged, or null if the exchange is not complete
     */
    public synchronized SessionKey getSessionKey() {
        if (_sessionKey != null) return _sessionKey;
        if (_peerValue != null) {
            _sessionKey = calculateSessionKey(_myPrivateValue, _peerValue);
        } else {
            //System.err.println("Not ready yet.. privateValue and peerValue must be set ("
            //                   + (_myPrivateValue != null ? "set" : "null") + ","
            //                   + (_peerValue != null ? "set" : "null") + ")");
        }
        return _sessionKey;
    }

    /**
     * Retrieve the extra bytes beyond the session key resulting from the DH exchange.
     * If there aren't enough bytes (with all of them being consumed by the 32 byte key),
     * the SHA256 of the key itself is used - but that won't ever happen.
     *
     * Used only by UDP. getData() will be non-null and have at least 32 bytes after call to getSessionKey()
     *
     * @return non-null (but rv.getData() may be null)
     */
    public ByteArray getExtraBytes() {
        return _extraExchangedBytes;
    }

    /**
     * Calculate a session key based on the private value and the public peer value.
     *
     * This is the first 32 bytes of the exchanged key (nominally 256 bytes),
     * EXCEPT that the first byte will be zero if the most significant bit was a 1
     * (Java BigInteger.toByteArray() format)
     *
     * Side effect - sets extraExchangedBytes to the next 32 bytes.
     */
    private final SessionKey calculateSessionKey(BigInteger myPrivateValue, BigInteger publicPeerValue) {
        long start = System.currentTimeMillis();
        SessionKey key = new SessionKey();
        BigInteger exchangedKey = publicPeerValue.modPow(myPrivateValue, CryptoConstants.elgp);
        // surprise! leading zero byte half the time!
        // probably was a mistake, too late now...
        byte buf[] = exchangedKey.toByteArray();
        byte val[] = new byte[SessionKey.KEYSIZE_BYTES];
        if (buf.length < 2 * SessionKey.KEYSIZE_BYTES) {
            // UDP requires at least 32 bytes in _extraExchangedBytes for the mac key
            // Won't ever happen, typ buf is 256 or 257 bytes
            System.arraycopy(buf, 0, val, 0, Math.min(buf.length, SessionKey.KEYSIZE_BYTES));
            byte remaining[] = new byte[SessionKey.KEYSIZE_BYTES];  // == Hash.HASH_LENGTH
            // non-caching version
            SHA256Generator.getInstance().calculateHash(buf, 0, buf.length, remaining, 0);
            _extraExchangedBytes.setData(remaining);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Storing " + remaining.length + " bytes from the DH exchange by SHA256 the session key");
        } else {
            // Will always be here, typ buf is 256 or 257 bytes
            System.arraycopy(buf, 0, val, 0, SessionKey.KEYSIZE_BYTES);
            // feed the extra bytes into the PRNG
            RandomSource.getInstance().harvester().feedEntropy("DH", buf, val.length, buf.length-val.length); 
            byte remaining[] = new byte[buf.length - val.length];
            System.arraycopy(buf, val.length, remaining, 0, remaining.length);
            _extraExchangedBytes.setData(remaining);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Storing " + remaining.length + " bytes from the end of the DH exchange");
        }
        key.setData(val);
        long end = System.currentTimeMillis();
        long diff = end - start;
        
        I2PAppContext.getGlobalContext().statManager().addRateData("crypto.dhCalculateSessionTime", diff);
        //if (diff > 1000) {
        //    if (_log.shouldLog(Log.WARN)) _log.warn("Generating session key took too long (" + diff + " ms");
        //} else {
        //    if (_log.shouldLog(Log.DEBUG)) _log.debug("Generating session key " + diff + " ms");
        //}
        return key;
    }
    
    /**
     * rfc2631:
     *  The following algorithm MAY be used to validate a received public key y.
     *
     *  1. Verify that y lies within the interval [2,p-1]. If it does not,
     *     the key is invalid.
     *  2. Compute y^q mod p. If the result == 1, the key is valid.
     *     Otherwise the key is invalid.
     */
    private static final void validatePublic(BigInteger publicValue) throws InvalidPublicParameterException {
        int cmp = publicValue.compareTo(NativeBigInteger.ONE);
        if (cmp <= 0) 
            throw new InvalidPublicParameterException("Public value is below two: " + publicValue.toString());
        
        cmp = publicValue.compareTo(CryptoConstants.elgp);
        if (cmp >= 0) 
            throw new InvalidPublicParameterException("Public value is above p-1: " + publicValue.toString());
        
        // todo: 
        // whatever validation needs to be done to mirror the rfc's part 2 (we don't have a q, so can't do
        // if (NativeBigInteger.ONE.compareTo(publicValue.modPow(q, CryptoConstants.elgp)) != 0)
        //   throw new InvalidPublicParameterException("Invalid public value with y^q mod p != 1");
        // 
    }

    /*
    private static void testValidation() {
        NativeBigInteger bi = new NativeBigInteger("-3416069082912684797963255430346582466254460710249795973742848334283491150671563023437888953432878859472362439146158925287289114133666004165938814597775594104058593692562989626922979416277152479694258099203456493995467386903611666213773085025718340335205240293383622352894862685806192183268523899615405287022135356656720938278415659792084974076416864813957028335830794117802560169423133816961503981757298122040391506600117301607823659479051969827845787626261515313227076880722069706394405554113103165334903531980102626092646197079218895216346725765704256096661045699444128316078549709132753443706200863682650825635513");
        try { 
            validatePublic(bi);
            System.err.println("valid?!");
        } catch (InvalidPublicParameterException ippe) {
            System.err.println("Ok, invalid.  cool");
        }
        
        byte val[] = bi.toByteArray();
        System.out.println("Len: " + val.length + " first is ok? " + ( (val[0] & 0x80) == 1) 
                           + "\n" + DataHelper.toString(val, 64));
        NativeBigInteger bi2 = new NativeBigInteger(1, val);
        try {
            validatePublic(bi2);
            System.out.println("valid");
        } catch (InvalidPublicParameterException ippe) {
            System.out.println("invalid");
        }
    }
    */
    
/******
    public static void main(String args[]) {
        //if (true) { testValidation(); return; }
        
        RandomSource.getInstance().nextBoolean(); // warm it up
        try {
            Thread.sleep(20 * 1000);
        } catch (InterruptedException ie) { // nop
        }
        I2PAppContext ctx = new I2PAppContext();
        _log.debug("\n\n\n\nBegin test\n");
        long negTime = 0;
        try {
            for (int i = 0; i < 5; i++) {
                long startNeg = System.currentTimeMillis();
                DHSessionKeyBuilder builder1 = new DHSessionKeyBuilder();
                DHSessionKeyBuilder builder2 = new DHSessionKeyBuilder();
                BigInteger pub1 = builder1.getMyPublicValue();
                builder2.setPeerPublicValue(pub1);
                BigInteger pub2 = builder2.getMyPublicValue();
                builder1.setPeerPublicValue(pub2);
                SessionKey key1 = builder1.getSessionKey();
                SessionKey key2 = builder2.getSessionKey();
                long endNeg = System.currentTimeMillis();
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
        } catch (InvalidPublicParameterException ippe) {
            _log.error("Invalid dh", ippe);
        }
        _log.debug("Negotiation time for 5 runs: " + negTime + " @ " + negTime / 5l + "ms each");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) { // nop
        }
    }
******/

    /**
     * @since 0.9
     */
    public interface Factory {
        /**
         * Construct a new DH key builder
         * or pulls a prebuilt one from the queue.
         */
        public DHSessionKeyBuilder getBuilder();

        /**
         * Return an unused DH key builder
         * to be put back onto the queue for reuse.
         *
         * @param builder must not have a peerPublicValue set
         * @since 0.9.16
         */
        public void returnUnused(DHSessionKeyBuilder builder);
    }

    /**
     *  Try to keep DH pairs at the ready.
     *  It's important to do this in a separate thread, because if we run out,
     *  the pairs are generated in the NTCP Pumper thread,
     *  and it can fall behind.
     */
    public static class PrecalcRunner extends I2PThread implements Factory {
        private final I2PAppContext _context;
        private final Log _log;
        private final int _minSize;
        private final int _maxSize;
        private final int _calcDelay;
        private final LinkedBlockingQueue<DHSessionKeyBuilder> _builders;
        private volatile boolean _isRunning;

        /** check periodically whether we have less than the minimum */
        private long _checkDelay = 10 * 1000;

        public PrecalcRunner(I2PAppContext ctx) {
            super("DH Precalc");
            _context = ctx;
            _log = ctx.logManager().getLog(DHSessionKeyBuilder.class);
            ctx.statManager().createRateStat("crypto.dhGeneratePublicTime", "How long it takes to create x and X", "Encryption", new long[] { 60*60*1000 });
            ctx.statManager().createRateStat("crypto.dhCalculateSessionTime", "How long it takes to create the session key", "Encryption", new long[] { 60*60*1000 });        
            ctx.statManager().createRateStat("crypto.DHUsed", "Need a DH from the queue", "Encryption", new long[] { 60*60*1000 });
            ctx.statManager().createRateStat("crypto.DHReused", "Unused DH requeued", "Encryption", new long[] { 60*60*1000 });
            ctx.statManager().createRateStat("crypto.DHEmpty", "DH queue empty", "Encryption", new long[] { 60*60*1000 });

            // add to the defaults for every 128MB of RAM, up to 512MB
            long maxMemory = SystemVersion.getMaxMemory();
            int factor = (int) Math.max(1l, Math.min(4l, 1 + (maxMemory / (128*1024*1024l))));
            int defaultMin = DEFAULT_DH_PRECALC_MIN * factor;
            int defaultMax = DEFAULT_DH_PRECALC_MAX * factor;
            _minSize = ctx.getProperty(PROP_DH_PRECALC_MIN, defaultMin);
            _maxSize = ctx.getProperty(PROP_DH_PRECALC_MAX, defaultMax);
            _calcDelay = ctx.getProperty(PROP_DH_PRECALC_DELAY, DEFAULT_DH_PRECALC_DELAY);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DH Precalc (minimum: " + _minSize + " max: " + _maxSize + ", delay: "
                           + _calcDelay + ")");
            _builders = new LinkedBlockingQueue<DHSessionKeyBuilder>(_maxSize);
            if (!SystemVersion.isWindows())
                setPriority(Thread.NORM_PRIORITY - 1);
        }
        
        /**
         *  Note that this stops the singleton precalc thread.
         *  You don't want to do this if there are multiple routers in the JVM.
         *  Fix this if you care. See Router.shutdown().
         *  @since 0.8.8
         */
        public void shutdown() {
            _isRunning = false;
            this.interrupt();
            _builders.clear();
        }

        public void run() {
            _isRunning = true;
            while (_isRunning) {
                //long start = System.currentTimeMillis();
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
                        if (!addBuilder(precalc()))
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
                //long end = System.currentTimeMillis();
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

        /**
         * Construct a new DH key builder
         * or pulls a prebuilt one from the queue.
         *
         * @since 0.9 moved from DHSKB
         */
        public DHSessionKeyBuilder getBuilder() {
            _context.statManager().addRateData("crypto.DHUsed", 1);
            DHSessionKeyBuilder builder = _builders.poll();
            if (builder == null) {
                if (_log.shouldLog(Log.INFO)) _log.info("No more builders, creating one now");
                _context.statManager().addRateData("crypto.DHEmpty", 1);
                builder = precalc();
                // stop sleeping, wake up, make some more
                this.interrupt();
            }
            return builder;
        }

        private DHSessionKeyBuilder precalc() {
            long start = System.currentTimeMillis();
            DHSessionKeyBuilder builder = new DHSessionKeyBuilder(_context);
            long end = System.currentTimeMillis();
            long diff = end - start;
            _context.statManager().addRateData("crypto.dhGeneratePublicTime", diff);
            if (diff > 1000) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Took more than a second (" + diff + "ms) to generate local DH value");
            } else {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Took " + diff + "ms to generate local DH value");
            }
            return builder;
        }

        /**
         * Return an unused DH key builder
         * to be put back onto the queue for reuse.
         *
         * @param builder must not have a peerPublicValue set
         * @since 0.9.16
         */
        public void returnUnused(DHSessionKeyBuilder builder) {
            if (builder.getPeerPublicValue() != null) {
                _log.error("builder returned used", new Exception());
                return;
            }
            _context.statManager().addRateData("crypto.DHReused", 1);
            _builders.offer(builder);
        }

        /** @return true if successful, false if full */
        private final boolean addBuilder(DHSessionKeyBuilder builder) {
            return _builders.offer(builder);
        }

        private final int getSize() {
            return _builders.size();
        }

    }
    
    public static class InvalidPublicParameterException extends I2PException {
        public InvalidPublicParameterException() {
            super();
        }
        public InvalidPublicParameterException(String msg) {
            super(msg);
        }
        /** @since 0.9.35 */
        public InvalidPublicParameterException(String msg, Throwable t) {
            super(msg, t);
        }
    }
}
