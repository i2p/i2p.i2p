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

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.NativeBigInteger;

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
    public static final int PUBKEY_EXPONENT_SIZE = PUBKEY_EXPONENT_SIZE_SHORT;

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
        BigInteger a = new NativeBigInteger(PUBKEY_EXPONENT_SIZE, _context.random());
        BigInteger aalpha = CryptoConstants.elgg.modPow(a, CryptoConstants.elgp);

        SimpleDataStructure[] keys = new SimpleDataStructure[2];
        keys[0] = new PublicKey();
        keys[1] = new PrivateKey();
        byte[] k0 = aalpha.toByteArray();
        byte[] k1 = a.toByteArray();

        // bigInteger.toByteArray returns SIGNED integers, but since they'return positive,
        // signed two's complement is the same as unsigned

        keys[0].setData(padBuffer(k0, PublicKey.KEYSIZE_BYTES));
        keys[1].setData(padBuffer(k1, PrivateKey.KEYSIZE_BYTES));

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
        byte [] pubBytes = aalpha.toByteArray();
        pub.setData(padBuffer(pubBytes, PublicKey.KEYSIZE_BYTES));
        return pub;
    }

    /** Generate a pair of DSA keys, where index 0 is a SigningPublicKey, and
     * index 1 is a SigningPrivateKey
     * @return pair of keys
     */
    public Object[] generateSigningKeypair() {
        return generateSigningKeys();
    }

    /**
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
        byte k0[] = padBuffer(y.toByteArray(), SigningPublicKey.KEYSIZE_BYTES);
        byte k1[] = padBuffer(x.toByteArray(), SigningPrivateKey.KEYSIZE_BYTES);

        keys[0].setData(k0);
        keys[1].setData(k1);
        return keys;
    }

    /** Convert a SigningPrivateKey to a SigningPublicKey
     * @param priv a SigningPrivateKey object
     * @return a SigningPublicKey object
     */
    public static SigningPublicKey getSigningPublicKey(SigningPrivateKey priv) {
        BigInteger x = new NativeBigInteger(1, priv.toByteArray());
        BigInteger y = CryptoConstants.dsag.modPow(x, CryptoConstants.dsap);
        SigningPublicKey pub = new SigningPublicKey();
        byte [] pubBytes = padBuffer(y.toByteArray(), SigningPublicKey.KEYSIZE_BYTES);
        pub.setData(pubBytes);
        return pub;
    }

    /**
     * Pad the buffer w/ leading 0s or trim off leading bits so the result is the
     * given length.  
     */
    private final static byte[] padBuffer(byte src[], int length) {
        byte buf[] = new byte[length];

        if (src.length > buf.length) // extra bits, chop leading bits
            System.arraycopy(src, src.length - buf.length, buf, 0, buf.length);
        else if (src.length < buf.length) // short bits, padd w/ 0s
            System.arraycopy(src, 0, buf, buf.length - src.length, src.length);
        else
            // eq
            System.arraycopy(src, 0, buf, 0, buf.length);

        return buf;
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
