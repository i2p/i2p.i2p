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
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.ProviderException;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;


// main()
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.util.Clock;
import net.i2p.util.RandomSource;

/** Define a way of generating asymmetrical key pairs as well as symmetrical keys
 * @author jrandom
 */
public class KeyGenerator {
    //private final Log _log;
    private final I2PAppContext _context;

    public KeyGenerator(I2PAppContext context) {
        //_log = context.logManager().getLog(KeyGenerator.class);
        _context = context;
    }

    public static KeyGenerator getInstance() {
        return I2PAppContext.getGlobalContext().keyGenerator();
    }

    /** Generate a private 256 bit session key
     * @return session key
     */
    public SessionKey generateSessionKey() {
        // 256bit random # as a session key
        SessionKey key = new SessionKey();
        byte data[] = new byte[SessionKey.KEYSIZE_BYTES];
        _context.random().nextBytes(data);
        key.setData(data);
        return key;
    }
    
    private static final int PBE_ROUNDS = 1000;

    /**
     *  PBE the passphrase with the salt.
     *  Warning - SLOW
     *  Deprecated - Used by Syndie only.
     */
    public SessionKey generateSessionKey(byte salt[], byte passphrase[]) {
        byte salted[] = new byte[16+passphrase.length];
        System.arraycopy(salt, 0, salted, 0, Math.min(salt.length, 16));
        System.arraycopy(passphrase, 0, salted, 16, passphrase.length);
        byte h[] = _context.sha().calculateHash(salted).getData();
        for (int i = 1; i < PBE_ROUNDS; i++)
            _context.sha().calculateHash(h, 0, Hash.HASH_LENGTH, h, 0);
        return new SessionKey(h);
    }
    
    /** standard exponent size */
    private static final int PUBKEY_EXPONENT_SIZE_FULL = 2048;

    /** 
     * short exponent size, which should be safe for use with the Oakley primes,
     * per "On Diffie-Hellman Key Agreement with Short Exponents" - van Oorschot, Weiner
     * at EuroCrypt 96, and crypto++'s benchmarks at http://www.eskimo.com/~weidai/benchmarks.html
     * Also, "Koshiba & Kurosawa: Short Exponent Diffie-Hellman Problems" (PKC 2004, LNCS 2947, pp. 173-186) 
     * aparently supports this, according to
     * http://groups.google.com/group/sci.crypt/browse_thread/thread/1855a5efa7416677/339fa2f945cc9ba0#339fa2f945cc9ba0
     * (damn commercial access to http://www.springerlink.com/(xrkdvv45w0cmnur4aimsxx55)/app/home/contribution.asp?referrer=parent&backto=issue,13,31;journal,893,3280;linkingpublicationresults,1:105633,1 )
     */
    private static final int PUBKEY_EXPONENT_SIZE_SHORT = 226;

    /** @since 0.9.8 */
    private static final boolean DEFAULT_USE_LONG_EXPONENT =
                                                   NativeBigInteger.isNative() &&
                                                   SystemVersion.is64Bit() &&
                                                   !SystemVersion.isGNU() &&
                                                   !SystemVersion.isApache() &&
                                                   !SystemVersion.isARM();

    /**
     *  @deprecated use getElGamalExponentSize() which allows override in the properties
     */
    public static final int PUBKEY_EXPONENT_SIZE = DEFAULT_USE_LONG_EXPONENT ?
                                                   PUBKEY_EXPONENT_SIZE_FULL :
                                                   PUBKEY_EXPONENT_SIZE_SHORT;

    private static final String PROP_LONG_EXPONENT = "crypto.elGamal.useLongKey";

    /** @since 0.9.8 */
    public boolean useLongElGamalExponent() {
        return _context.getProperty(PROP_LONG_EXPONENT, DEFAULT_USE_LONG_EXPONENT);
    }

    /** @since 0.9.8 */
    public int getElGamalExponentSize() {
        return useLongElGamalExponent() ?
               PUBKEY_EXPONENT_SIZE_FULL :
               PUBKEY_EXPONENT_SIZE_SHORT;
    }

    /** Generate a pair of keys, where index 0 is a PublicKey, and
     * index 1 is a PrivateKey
     * @return pair of keys
     */
    public Object[] generatePKIKeypair() {
        return generatePKIKeys();
    }

    /**
     *  Same as above but different return type
     *  @since 0.8.7
     */
    public SimpleDataStructure[] generatePKIKeys() {
        BigInteger a = new NativeBigInteger(getElGamalExponentSize(), _context.random());
        BigInteger aalpha = CryptoConstants.elgg.modPow(a, CryptoConstants.elgp);

        SimpleDataStructure[] keys = new SimpleDataStructure[2];
        keys[0] = new PublicKey();
        keys[1] = new PrivateKey();

        // bigInteger.toByteArray returns SIGNED integers, but since they'return positive,
        // signed two's complement is the same as unsigned

        try {
            keys[0].setData(SigUtil.rectify(aalpha, PublicKey.KEYSIZE_BYTES));
            keys[1].setData(SigUtil.rectify(a, PrivateKey.KEYSIZE_BYTES));
        } catch (InvalidKeyException ike) {
            throw new IllegalArgumentException(ike);
        }

        return keys;
    }

    /** Convert a PrivateKey to its corresponding PublicKey
     * @param priv PrivateKey object
     * @return the corresponding PublicKey object
     */
    public static PublicKey getPublicKey(PrivateKey priv) {
        BigInteger a = new NativeBigInteger(1, priv.toByteArray());
        BigInteger aalpha = CryptoConstants.elgg.modPow(a, CryptoConstants.elgp);
        PublicKey pub = new PublicKey();
        try {
            pub.setData(SigUtil.rectify(aalpha, PublicKey.KEYSIZE_BYTES));
        } catch (InvalidKeyException ike) {
            throw new IllegalArgumentException(ike);
        }
        return pub;
    }

    /** Generate a pair of DSA keys, where index 0 is a SigningPublicKey, and
     * index 1 is a SigningPrivateKey.
     * DSA-SHA1 only.
     *
     * @return pair of keys
     */
    public Object[] generateSigningKeypair() {
        return generateSigningKeys();
    }

    /**
     *  DSA-SHA1 only.
     *
     *  Same as above but different return type
     *  @since 0.8.7
     */
    public SimpleDataStructure[] generateSigningKeys() {
        SimpleDataStructure[] keys = new SimpleDataStructure[2];
        BigInteger x = null;

        // make sure the random key is less than the DSA q
        do {
            x = new NativeBigInteger(160, _context.random());
        } while (x.compareTo(CryptoConstants.dsaq) >= 0);

        BigInteger y = CryptoConstants.dsag.modPow(x, CryptoConstants.dsap);
        keys[0] = new SigningPublicKey();
        keys[1] = new SigningPrivateKey();
        try {
            keys[0].setData(SigUtil.rectify(y, SigningPublicKey.KEYSIZE_BYTES));
            keys[1].setData(SigUtil.rectify(x, SigningPrivateKey.KEYSIZE_BYTES));
        } catch (InvalidKeyException ike) {
            throw new IllegalStateException(ike);
        }
        return keys;
    }

    /**
     *  Generic signature type, supports DSA and ECDSA
     *  @since 0.9.9
     */
    public SimpleDataStructure[] generateSigningKeys(SigType type) throws GeneralSecurityException {
        if (type == SigType.DSA_SHA1)
            return generateSigningKeys();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(type.getBaseAlgorithm().getName());
        KeyPair kp;
        try {
            kpg.initialize(type.getParams(), _context.random());
            kp = kpg.generateKeyPair();
        } catch (ProviderException pe) {
            // java.security.ProviderException: sun.security.pkcs11.wrapper.PKCS11Exception: CKR_DOMAIN_PARAMS_INVALID
            // This is a RuntimeException, thx Sun
            // Fails for P-192 only, on Ubuntu
            Log log = _context.logManager().getLog(KeyGenerator.class);
            String pname = kpg.getProvider().getName();
            if ("BC".equals(pname)) {
                if (log.shouldLog(Log.WARN))
                    log.warn("BC KPG failed for " + type, pe);
                throw new GeneralSecurityException("BC KPG for " + type, pe);
            }
            if (!ECConstants.isBCAvailable())
                throw new GeneralSecurityException(pname + " KPG failed for " + type, pe);
            if (log.shouldLog(Log.WARN))
                log.warn(pname + " KPG failed for " + type + ", trying BC"  /* , pe */ );
            try {
                kpg = KeyPairGenerator.getInstance(type.getBaseAlgorithm().getName(), "BC");
                kpg.initialize(type.getParams(), _context.random());
                kp = kpg.generateKeyPair();
            } catch (ProviderException pe2) {
                if (log.shouldLog(Log.WARN))
                    log.warn("BC KPG failed for " + type + " also", pe2);
                // throw original exception
                throw new GeneralSecurityException(pname + " KPG for " + type, pe);
            } catch (GeneralSecurityException gse) {
                if (log.shouldLog(Log.WARN))
                    log.warn("BC KPG failed for " + type + " also", gse);
                // throw original exception
                throw new GeneralSecurityException(pname + " KPG for " + type, pe);
            }
        }
        java.security.PublicKey pubkey = kp.getPublic();
        java.security.PrivateKey privkey = kp.getPrivate();
        SimpleDataStructure[] keys = new SimpleDataStructure[2];
        keys[0] = SigUtil.fromJavaKey(pubkey, type);
        keys[1] = SigUtil.fromJavaKey(privkey, type);
        return keys;
    }

    /** Convert a SigningPrivateKey to a SigningPublicKey.
     * DSA-SHA1 only.
     *
     * @param priv a SigningPrivateKey object
     * @return a SigningPublicKey object
     */
    public static SigningPublicKey getSigningPublicKey(SigningPrivateKey priv) {
        BigInteger x = new NativeBigInteger(1, priv.toByteArray());
        BigInteger y = CryptoConstants.dsag.modPow(x, CryptoConstants.dsap);
        SigningPublicKey pub = new SigningPublicKey();
        try {
            pub.setData(SigUtil.rectify(y, SigningPublicKey.KEYSIZE_BYTES));
        } catch (InvalidKeyException ike) {
            throw new IllegalArgumentException(ike);
        }
        return pub;
    }

    public static void main(String args[]) {
        try {
             main2(args);
        } catch (Exception e) {
             e.printStackTrace();
        }
    }

    public static void main2(String args[]) {
        RandomSource.getInstance().nextBoolean();
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        int runs = 200; // warmup
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i <= 100; i++) {
                SigType type = SigType.getByCode(i);
                if (type == null)
                    break;
                try {
                    System.out.println("Testing " + type);
                    testSig(type, runs);
                } catch (Exception e) {
                    System.out.println("error testing " + type);
                    e.printStackTrace();
                }
            }
            runs = 1000;
        }
    }

    private static void testSig(SigType type, int runs) throws GeneralSecurityException {
        byte src[] = new byte[512];
        long stime = 0;
        long vtime = 0;
        SimpleDataStructure keys[] = KeyGenerator.getInstance().generateSigningKeys(type);
        //System.out.println("pubkey " + keys[0]);
        //System.out.println("privkey " + keys[1]);
        for (int i = 0; i < runs; i++) {
            RandomSource.getInstance().nextBytes(src);
            long start = System.nanoTime();
            Signature sig = DSAEngine.getInstance().sign(src, (SigningPrivateKey) keys[1]);
            long mid = System.nanoTime();
            boolean ok = DSAEngine.getInstance().verifySignature(sig, src, (SigningPublicKey) keys[0]);
            long end = System.nanoTime();
            stime += mid - start;
            vtime += end - mid;
            if (!ok)
                throw new GeneralSecurityException(type + " V(S(data)) fail");
        }
        stime /= 1000*1000;
        vtime /= 1000*1000;
        System.out.println(type + " sign/verify " + runs + " times: " + (vtime+stime) + " ms = " +
                           (((double) stime) / runs) + " each sign, " +
                           (((double) vtime) / runs) + " each verify, " +
                           (((double) (stime + vtime)) / runs) + " s+v");
    }

/******
    public static void main(String args[]) {
        Log log = new Log("keygenTest");
        RandomSource.getInstance().nextBoolean();
        byte src[] = new byte[200];
        RandomSource.getInstance().nextBytes(src);

        I2PAppContext ctx = new I2PAppContext();
        long time = 0;
        for (int i = 0; i < 10; i++) {
            long start = Clock.getInstance().now();
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            long end = Clock.getInstance().now();
            byte ctext[] = ctx.elGamalEngine().encrypt(src, (PublicKey) keys[0]);
            byte ptext[] = ctx.elGamalEngine().decrypt(ctext, (PrivateKey) keys[1]);
            time += end - start;
            if (DataHelper.eq(ptext, src))
                log.debug("D(E(data)) == data");
            else
                log.error("D(E(data)) != data!!!!!!");
        }
        log.info("Keygen 10 times: " + time + "ms");

        Object obj[] = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey fake = (SigningPublicKey) obj[0];
        time = 0;
        for (int i = 0; i < 10; i++) {
            long start = Clock.getInstance().now();
            Object keys[] = KeyGenerator.getInstance().generateSigningKeypair();
            long end = Clock.getInstance().now();
            Signature sig = DSAEngine.getInstance().sign(src, (SigningPrivateKey) keys[1]);
            boolean ok = DSAEngine.getInstance().verifySignature(sig, src, (SigningPublicKey) keys[0]);
            boolean fakeOk = DSAEngine.getInstance().verifySignature(sig, src, fake);
            time += end - start;
            log.debug("V(S(data)) == " + ok + " fake verify correctly failed? " + (fakeOk == false));
        }
        log.info("Signing Keygen 10 times: " + time + "ms");

        time = 0;
        for (int i = 0; i < 1000; i++) {
            long start = Clock.getInstance().now();
            KeyGenerator.getInstance().generateSessionKey();
            long end = Clock.getInstance().now();
            time += end - start;
        }
        log.info("Session keygen 1000 times: " + time + "ms");

        try {
            Thread.sleep(5000);
        } catch (InterruptedException ie) { // nop
        }
    }
******/
}
