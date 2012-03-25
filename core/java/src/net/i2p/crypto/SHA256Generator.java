package net.i2p.crypto;

import gnu.crypto.hash.Sha256Standalone;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

/** 
 * Defines a wrapper for SHA-256 operation.
 * 
 * As of release 0.8.7, uses java.security.MessageDigest by default.
 * If that is unavailable, it uses
 * GNU-Crypto {@link gnu.crypto.hash.Sha256Standalone}
 */
public final class SHA256Generator {
    private final LinkedBlockingQueue<MessageDigest> _digests;

    private static final boolean _useGnu;
    static {
        boolean useGnu = false;
        try {
            MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            useGnu = true;
            System.out.println("INFO: Using GNU SHA-256");
        }
        _useGnu = useGnu;
    }

    /**
     *  @param context unused
     */
    public SHA256Generator(I2PAppContext context) {
        _digests = new LinkedBlockingQueue(32);
    }
    
    public static final SHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().sha();
    }
    
    /**
     * Calculate the SHA-256 hash of the source and cache the result.
     * @param source what to hash
     * @return hash of the source
     */
    public final Hash calculateHash(byte[] source) {
        return calculateHash(source, 0, source.length);
    }

    /**
     * Calculate the hash and cache the result.
     */
    public final Hash calculateHash(byte[] source, int start, int len) {
        MessageDigest digest = acquire();
        digest.update(source, start, len);
        byte rv[] = digest.digest();
        release(digest);
        //return new Hash(rv);
        return Hash.create(rv);
    }
    
    /**
     * Use this if you only need the data, not a Hash object.
     * Does not cache.
     * @param out needs 32 bytes starting at outOffset
     */
    public final void calculateHash(byte[] source, int start, int len, byte out[], int outOffset) {
        MessageDigest digest = acquire();
        digest.update(source, start, len);
        byte rv[] = digest.digest();
        release(digest);
        System.arraycopy(rv, 0, out, outOffset, rv.length);
    }
    
    private MessageDigest acquire() {
        MessageDigest rv = _digests.poll();
        if (rv != null)
            rv.reset();
        else
            rv = getDigestInstance();
        return rv;
    }
    
    private void release(MessageDigest digest) {
        _digests.offer(digest);
    }
    
    /**
     *  Return a new MessageDigest from the system libs unless unavailable
     *  in this JVM, in that case return a wrapped GNU Sha256Standalone
     *  @since 0.8.7, public since 0.8.8 for FortunaStandalone
     */
    public static MessageDigest getDigestInstance() {
        if (!_useGnu) {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {}
        }
        return new GnuMessageDigest();
    }

    /**
     *  Wrapper to make Sha256Standalone a MessageDigest
     *  @since 0.8.7
     */
    private static class GnuMessageDigest extends MessageDigest {
        private final Sha256Standalone _gnu;

        protected GnuMessageDigest() {
            super("SHA-256");
            _gnu = new Sha256Standalone();
        }

        protected byte[] engineDigest() {
            return _gnu.digest();
        }

        protected void engineReset() {
            _gnu.reset();
        }

        protected void engineUpdate(byte input) {
            _gnu.update(input);
        }

        protected void engineUpdate(byte[] input, int offset, int len) {
            _gnu.update(input, offset, len);
        }
    }

    private static final int RUNS = 100000;

    /**
     *  Test the GNU and the JVM's implementations for speed
     *
     *  Results: 2011-05 eeepc Atom
     *  <pre>
     *  JVM	strlen	GNU ms	JVM  ms 
     *	Oracle	387	  3861	 3565
     *	Oracle	 40	   825	  635
     *	Harmony	387	  8082	 5158
     *	Harmony	 40	  4137	 1753
     *	JamVM	387	 36301	34100
     *	JamVM	 40	  7022	 6016
     *	gij	387	125833	 4342
     *	gij	 40	 22417    988
     *  </pre>
     */
/****
    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: SHA256Generator string");
            return;
        }

        byte[] data = args[0].getBytes();
        Sha256Standalone gnu = new Sha256Standalone();
        long start = System.currentTimeMillis();
        for (int i = 0; i < RUNS; i++) {
            gnu.update(data, 0, data.length);
            byte[] sha = gnu.digest();
            if (i == 0)
                System.out.println("SHA256 [" + args[0] + "] = [" + Base64.encode(sha) + "]");
            gnu.reset();
        }
        long time = System.currentTimeMillis() - start;
        System.out.println("Time for " + RUNS + " SHA-256 computations:");
        System.out.println("GNU time (ms): " + time);

        start = System.currentTimeMillis();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Fatal: " + e);
            return;
        }
        for (int i = 0; i < RUNS; i++) {
            md.reset();
            byte[] sha = md.digest(data);
            if (i == 0)
                System.out.println("SHA256 [" + args[0] + "] = [" + Base64.encode(sha) + "]");
        }
        time = System.currentTimeMillis() - start;

        System.out.println("JVM time (ms): " + time);
    }
****/
}
