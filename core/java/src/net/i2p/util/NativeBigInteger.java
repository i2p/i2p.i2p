package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.Random;

import net.i2p.crypto.CryptoConstants;

public class NativeBigInteger extends BigInteger {
    private final static Log _log = new Log(NativeBigInteger.class);
    private static boolean _nativeOk = false;
    static {
        try {
            System.loadLibrary("jbigi");
            _nativeOk = true;
            _log.info("Native BigInteger library jbigi loaded");
        } catch (UnsatisfiedLinkError ule) {
            _nativeOk = false;
            _log.warn("Native BigInteger library jbigi not loaded - using pure java", ule);
        }
    }

    /**
     * calculate (base ^ exponent) % modulus.  
     * @param base big endian twos complement representation of the base (but it must be positive)
     * @param exponent big endian twos complement representation of the exponent
     * @param modulus big endian twos complement representation of the modulus
     * @return big endian twos complement representation of (base ^ exponent) % modulus
     */
    public native static byte[] nativeModPow(byte base[], byte exponent[], byte modulus[]);

    public NativeBigInteger(byte val[]) {
        super(val);
    }

    public NativeBigInteger(int signum, byte magnitude[]) {
        super(signum, magnitude);
    }

    public NativeBigInteger(int bitlen, int certainty, Random rnd) {
        super(bitlen, certainty, rnd);
    }

    public NativeBigInteger(int numbits, Random rnd) {
        super(numbits, rnd);
    }

    public NativeBigInteger(String val) {
        super(val);
    }

    public NativeBigInteger(String val, int radix) {
        super(val, radix);
    }

    public BigInteger modPow(BigInteger exponent, BigInteger m) {
        if (_nativeOk)
            return new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
        else
            return super.modPow(exponent, m);
    }

    public static void main(String args[]) {
        if (_nativeOk)
            System.out.println("Native library loaded");
        else
            System.out.println("Native library NOT loaded");
        System.out.println("Warming up the random number generator...");
        RandomSource.getInstance().nextBoolean();
        System.out.println("Random number generator warmed up");

        int numRuns = 100;
        if (args.length == 1) {
            try {
                numRuns = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
            }
        }
        BigInteger jg = new BigInteger(CryptoConstants.elgg.toByteArray());
        BigInteger jp = new BigInteger(CryptoConstants.elgp.toByteArray());

        long totalTime = 0;
        long javaTime = 0;

        int runsProcessed = 0;
        for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
            BigInteger bi = new BigInteger(2048, RandomSource.getInstance());
            NativeBigInteger g = new NativeBigInteger(CryptoConstants.elgg.toByteArray());
            NativeBigInteger p = new NativeBigInteger(CryptoConstants.elgp.toByteArray());
            NativeBigInteger k = new NativeBigInteger(1, bi.toByteArray());
            long beforeModPow = System.currentTimeMillis();
            BigInteger myValue = g.modPow(k, p);
            long afterModPow = System.currentTimeMillis();
            BigInteger jval = jg.modPow(bi, jp);
            long afterJavaModPow = System.currentTimeMillis();

            totalTime += (afterModPow - beforeModPow);
            javaTime += (afterJavaModPow - afterModPow);
            if (!myValue.equals(jval)) {
                _log.error("[" + runsProcessed + "]\tnative modPow != java modPow");
                _log.error("native modPow value: " + myValue.toString());
                _log.error("java modPow value: " + jval.toString());
                _log.error("run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
                System.err.println("[" + runsProcessed + "]\tnative modPow != java modPow");
                break;
            } else {
                _log.debug("current run time: " + (afterModPow - beforeModPow) + "ms (total: " + totalTime + "ms, "
                           + (totalTime / (runsProcessed + 1)) + "ms each)");
            }
        }
        _log.info(numRuns + " runs complete without any errors");
        _log.info("run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
        if (numRuns == runsProcessed)
            System.out.println(runsProcessed + " runs complete without any errors");
        else
            System.out.println(runsProcessed + " runs until we got an error");

        if (_nativeOk) {
            System.out.println("native run time: \t" + totalTime + "ms (" + (totalTime / (runsProcessed + 1))
                               + "ms each)");
            System.out.println("java run time: \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
            System.out.println("native = " + ((totalTime * 100.0d) / (double) javaTime) + "% of pure java time");
        } else {
            System.out.println("java run time: \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
        }
    }
}