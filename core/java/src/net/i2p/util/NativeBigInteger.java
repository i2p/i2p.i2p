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
import java.security.SecureRandom;

import java.net.URL;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.File;

/**
 * <p>BigInteger that takes advantage of the jbigi library for the modPow operation,
 * which accounts for a massive segment of the processing cost of asymmetric 
 * crypto.  The jbigi library itself is basically just a JNI wrapper around the 
 * GMP library - a collection of insanely efficient routines for dealing with 
 * big numbers.</p>
 *
 * There are two environmental properties for configuring this component: <ul>
 * <li><b>jbigi.enable</b>: whether to use the native library (defaults to "true")</li>
 * <li><b>jbigi.impl</b>: select which resource to use as the native implementation</li>
 * </ul>
 *
 * <p>If jbigi.enable is set to false, this class won't even attempt to use the 
 * native library, but if it is set to true (or is not specified), it will first 
 * check the platform specific library path for the "jbigi" library, as defined by 
 * {@link Runtime#loadLibrary} - e.g. C:\windows\jbigi.dll or /lib/libjbigi.so.  
 * If that fails, it reviews the jbigi.impl environment property - if that is set,
 * it checks all of the components in the CLASSPATH for the file specified and
 * attempts to load it as the native library.  If jbigi.impl is not set, if there
 * is no matching resource, or if that resource is not a valid OS/architecture
 * specific library, the NativeBigInteger will revert to using the pure java 
 * implementation.</p>
 * 
 * <p>That means <b>NativeBigInteger will not attempt to guess the correct 
 * platform/OS/whatever</b> - applications using this class should define that 
 * property prior to <i>referencing</i> the NativeBigInteger (or before loading
 * the JVM, of course).  Alternately, people with custom built jbigi implementations
 * in their OS's standard search path (LD_LIBRARY_PATH, etc) needn't bother.</p>
 *
 * <p>One way to deploy the native library is to create a jbigi.jar file containing 
 * all of the native implementations with filenames such as "win-athlon", "linux-p2",
 * "freebsd-sparcv4", where those files are the OS specific libraries (the contents of
 * the DLL or .so file built for those OSes / architectures).  The user would then
 * simply specify -Djbigi.impl=win-athlon and this component would pick up that 
 * library.</p>
 *
 * <p>Another way is to create a seperate jbigi.jar file for each platform containing
 * one file - "native", where that file is the OS / architecture specific library 
 * implementation, as above.  This way the user would download the correct jbigi.jar
 * (and not all of the libraries for platforms/OSes they don't need) and would specify
 * -Djbigi.impl=native.</p>
 *
 * <p>Running this class by itself does a basic unit test and benchmarks the
 * NativeBigInteger.modPow vs. the BigInteger.modPow by running a 2Kbit op 100
 * times.  At the end, if the native implementation is loaded this will output 
 * something like:</p>
 * <pre>
 *  native run time:        6090ms (60ms each)
 *  java run time:          68067ms (673ms each)
 *  native = 8.947066860593239% of pure java time
 * </pre>
 * 
 * <p>If the native implementation is not loaded, it will start by saying:</p>
 * <pre>
 *  WARN: Native BigInteger library jbigi not loaded - using pure java
 * </pre>
 * <p>Then go on to run the test, finally outputting:</p>
 * <pre>
 *  java run time:  64653ms (640ms each)
 *  However, we couldn't load the native library, so this doesn't test much
 * </pre>
 *
 */
public class NativeBigInteger extends BigInteger {
    /** did we load the native lib correctly? */
    private static boolean _nativeOk = false;
    /** 
     * do we want to dump some basic success/failure info to stderr during 
     * initialization?  this would otherwise use the Log component, but this makes
     * it easier for other systems to reuse this class
     */
    private static final boolean _doLog = true;
    
    static {
        loadNative();
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
    
    /**
     * <p>Compare the BigInteger.modPow vs the NativeBigInteger.modPow of some 
     * really big (2Kbit) numbers 100 different times and benchmark the 
     * performance (or shit a brick if they don't match).  </p>
     *
     */
    public static void main(String args[]) {
        runTest(100);
    }

    /* the sample numbers are elG generator/prime so we can test with reasonable numbers */
    private final static byte[] _sampleGenerator = new BigInteger("2").toByteArray();
    private final static byte[] _samplePrime = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
                                                              + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
                                                              + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
                                                              + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                                                              + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
                                                              + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
                                                              + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
                                                              + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                                                              + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
                                                              + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
                                                              + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16).toByteArray();

    private static void runTest(int numRuns) {
        System.out.println("DEBUG: Warming up the random number generator...");
        SecureRandom rand = new SecureRandom();
        rand.nextBoolean();
        System.out.println("DEBUG: Random number generator warmed up");

        BigInteger jg = new BigInteger(_sampleGenerator);
        BigInteger jp = new BigInteger(_samplePrime);

        long totalTime = 0;
        long javaTime = 0;

        int runsProcessed = 0;
        for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
            BigInteger bi = new BigInteger(2048, rand);
            NativeBigInteger g = new NativeBigInteger(_sampleGenerator);
            NativeBigInteger p = new NativeBigInteger(_samplePrime);
            NativeBigInteger k = new NativeBigInteger(1, bi.toByteArray());
            long beforeModPow = System.currentTimeMillis();
            BigInteger myValue = g.modPow(k, p);
            long afterModPow = System.currentTimeMillis();
            BigInteger jval = jg.modPow(bi, jp);
            long afterJavaModPow = System.currentTimeMillis();

            totalTime += (afterModPow - beforeModPow);
            javaTime += (afterJavaModPow - afterModPow);
            if (!myValue.equals(jval)) {
                System.err.println("ERROR: [" + runsProcessed + "]\tnative modPow != java modPow");
                System.err.println("ERROR: native modPow value: " + myValue.toString());
                System.err.println("ERROR: java modPow value: " + jval.toString());
                System.err.println("ERROR: run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
                break;
            } else {
                System.out.println("DEBUG: current run time: " + (afterModPow - beforeModPow) + "ms (total: " 
                                   + totalTime + "ms, " + (totalTime / (runsProcessed + 1)) + "ms each)");
            }
        }
        System.out.println("INFO: run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
        if (numRuns == runsProcessed)
            System.out.println("INFO: " + runsProcessed + " runs complete without any errors");
        else
            System.out.println("ERROR: " + runsProcessed + " runs until we got an error");

        if (_nativeOk) {
            System.out.println("native run time: \t" + totalTime + "ms (" + (totalTime / (runsProcessed + 1))
                               + "ms each)");
            System.out.println("java run time:   \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
            System.out.println("native = " + ((totalTime * 100.0d) / (double) javaTime) + "% of pure java time");
        } else {
            System.out.println("java run time: \t" + javaTime + "ms (" + (javaTime / (runsProcessed + 1)) + "ms each)");
            System.out.println("However, we couldn't load the native library, so this doesn't test much");
        }
    }

    /**
     * <p>Do whatever we can to load up the native library backing this BigInteger's modPow.
     * If it can find a custom built jbigi.dll / libjbigi.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djbigi.enable=false it'll skip all of this.</p>
     *
     */
    private static final void loadNative() {
        String wantedProp = System.getProperty("jbigi.enable", "true");
        boolean wantNative = "true".equalsIgnoreCase(wantedProp);
        if (wantNative) {
            boolean loaded = loadGeneric();
            if (loaded) {
                _nativeOk = true;
                if (_doLog)
                    System.err.println("INFO: Native BigInteger library jbigi loaded");
            } else {
                loaded = loadFromResource();
                if (loaded) {
                    _nativeOk = true;
                    if (_doLog)
                        System.err.println("INFO: Native BigInteger library jbigi loaded from resource");
                } else {
                    _nativeOk = false;
                    if (_doLog)
                        System.err.println("WARN: Native BigInteger library jbigi not loaded - using pure java");
                }
            }
        } else {
            if (_doLog)
                System.err.println("INFO: Native BigInteger library jbigi not loaded - using pure java");
        }
    }
    
    /** 
     * <p>Try loading it from an explictly build jbigi.dll / libjbigi.so first, before 
     * looking into a jbigi.jar for any other libraries.</p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadGeneric() {
        try {
            System.loadLibrary("jbigi");
            return true;
        } catch (UnsatisfiedLinkError ule) {
            return false;
        }
    }
    
    /**
     * <p>Check all of the jars in the classpath for the file specified by the 
     * environmental property "jbigi.impl" and load it as the native library 
     * implementation.  For instance, a windows user on a p4 would define
     * -Djbigi.impl=win-686 if there is a jbigi.jar in the classpath containing the 
     * files "win-686", "win-athlon", "freebsd-p4", "linux-p3", where each 
     * of those files contain the correct binary file for a native library (e.g.
     * windows DLL, or a *nix .so).  </p>
     * 
     * <p>This is a pretty ugly hack, using the general technique illustrated by the
     * onion FEC libraries.  It works by pulling the resource, writing out the 
     * byte stream to a temporary file, loading the native library from that file,
     * then deleting the file.</p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadFromResource() {
        String resourceName = System.getProperty("jbigi.impl");
        if (resourceName == null) return false;
        URL resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            if (_doLog)
                System.err.println("ERROR: Resource name [" + resourceName + "] was not found");
            return false;
        }

        File outFile = null;
        try {
            InputStream libStream = resource.openStream();
            outFile = File.createTempFile("jbigi", "lib.tmp");
            FileOutputStream fos = new FileOutputStream(outFile);
            byte buf[] = new byte[4096*1024];
            while (true) {
                int read = libStream.read(buf);
                if (read < 0) break;
                fos.write(buf, 0, read);
            }
            fos.close();
            System.load(outFile.getPath());
            return true;
        } catch (UnsatisfiedLinkError ule) {
            if (_doLog) {
                System.err.println("ERROR: The resource " + resourceName 
                                   + " was not a valid library for this platform");
                ule.printStackTrace();
            }
            return false;
        } catch (IOException ioe) {
            if (_doLog) {
                System.err.println("ERROR: Problem writing out the temporary native library data");
                ioe.printStackTrace();
            }
            return false;
        } finally {
            if (outFile != null) {
                outFile.deleteOnExit();
            }
        }
    }
}