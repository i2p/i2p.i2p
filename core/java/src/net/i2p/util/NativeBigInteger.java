package net.i2p.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Random;

import freenet.support.CPUInformation.AMDCPUInfo;
import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import freenet.support.CPUInformation.IntelCPUInfo;
import freenet.support.CPUInformation.UnknownCPUException;

import net.i2p.I2PAppContext;

/**
 * <p>BigInteger that takes advantage of the jbigi library for the modPow operation,
 * which accounts for a massive segment of the processing cost of asymmetric 
 * crypto. It also takes advantage of the jbigi library for converting a BigInteger
 * value to a double. Sun's implementation of the 'doubleValue()' method is _very_ lousy.
 * 
 * The jbigi library itself is basically just a JNI wrapper around the 
 * GMP library - a collection of insanely efficient routines for dealing with 
 * big numbers.</p>
 *
 * There are three environmental properties for configuring this component: <ul>
 * <li><b>jbigi.enable</b>: whether to use the native library (defaults to "true")</li>
 * <li><b>jbigi.impl</b>: select which resource to use as the native implementation</li>
 * <li><b>jbigi.ref</b>: the file specified in this parameter may contain a resource
 *                       name to override jbigi.impl (defaults to "jbigi.cfg")</li>
 * </ul>
 *
 * <p>If jbigi.enable is set to false, this class won't even attempt to use the 
 * native library, but if it is set to true (or is not specified), it will first 
 * check the platform specific library path for the "jbigi" library, as defined by 
 * {@link Runtime#loadLibrary} - e.g. C:\windows\jbigi.dll or /lib/libjbigi.so, as
 * well as the CLASSPATH for a resource named 'jbigi'.  If that fails, it reviews 
 * the jbigi.impl environment property - if that is set, it checks all of the 
 * components in the CLASSPATH for the file specified and attempts to load it as 
 * the native library.  If jbigi.impl is not set, it uses the jcpuid library 
 * described below.  If there is still no matching resource, or if that resource 
 * is not a valid OS/architecture specific library, the NativeBigInteger will 
 * revert to using the pure java implementation.</p>
 * 
 * <p>When attempting to load the native implementation as a resource from the CLASSPATH,
 * the NativeBigInteger will make use of the jcpuid component which runs some assembly 
 * code to determine the current CPU implementation, such as "pentium4" or "k623".
 * We then use that, combined with the OS, to build an optimized resource name - e.g. 
 * "net/i2p/util/libjbigi-freebsd-pentium4.so" or "net/i2p/util/jbigi-windows-k623.dll".
 * If that resource exists, we use it.  If it doesn't (or the jcpuid component fails), 
 * we try a generic native implementation using "none" for the CPU (ala 
 * "net/i2p/util/jbigi-windows-none.dll").</p>
 *
 * <p>Running this class by itself does a basic unit test and benchmarks the
 * NativeBigInteger.modPow/doubleValue vs. the BigInteger.modPow/doubleValue by running a 2Kbit op 100
 * times.  At the end of each test, if the native implementation is loaded this will output 
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
    private static String _loadStatus = "uninitialized";
    private static String _cpuModel = "uninitialized";
    /** 
     * do we want to dump some basic success/failure info to stderr during 
     * initialization?  this would otherwise use the Log component, but this makes
     * it easier for other systems to reuse this class
     *
     * Well, we really want to use Log so if you are one of those "other systems"
     * then comment out the I2PAppContext usage below.
     *
     * Set to false if not in router context, so scripts using TrustedUpdate
     * don't spew log messages. main() below overrides to true.
     */
    private static boolean _doLog = System.getProperty("jbigi.dontLog") == null &&
                                    I2PAppContext.getGlobalContext().isRouterContext();
    
    private final static String JBIGI_OPTIMIZATION_K6         = "k6";
    private final static String JBIGI_OPTIMIZATION_K6_2       = "k62";
    private final static String JBIGI_OPTIMIZATION_K6_3       = "k63";
    private final static String JBIGI_OPTIMIZATION_ATHLON     = "athlon";
    private final static String JBIGI_OPTIMIZATION_ATHLON64   = "athlon64";
    private final static String JBIGI_OPTIMIZATION_PENTIUM    = "pentium";
    private final static String JBIGI_OPTIMIZATION_PENTIUMMMX = "pentiummmx";
    private final static String JBIGI_OPTIMIZATION_PENTIUM2   = "pentium2";
    private final static String JBIGI_OPTIMIZATION_PENTIUM3   = "pentium3";
    private final static String JBIGI_OPTIMIZATION_PENTIUM4   = "pentium4";
    private final static String JBIGI_OPTIMIZATION_VIAC3      = "viac3";

    private static final boolean _isWin = System.getProperty("os.name").startsWith("Win");
    private static final boolean _isOS2 = System.getProperty("os.name").startsWith("OS/2");
    private static final boolean _isMac = System.getProperty("os.name").startsWith("Mac");
    private static final boolean _isLinux = System.getProperty("os.name").toLowerCase().indexOf("linux") != -1;
    private static final boolean _isFreebsd = System.getProperty("os.name").toLowerCase().indexOf("freebsd") != -1;
    private static final boolean _isNix = !(_isWin || _isMac || _isOS2);
    /* libjbigi.so vs jbigi.dll */
    private static final String _libPrefix = (_isWin || _isOS2 ? "" : "lib");
    private static final String _libSuffix = (_isWin || _isOS2 ? ".dll" : _isMac ? ".jnilib" : ".so");

    private final static String sCPUType; //The CPU Type to optimize for (one of the above strings)
    
    static {
        if (_isMac) // replace with osx/mac friendly jni cpu type detection when we have one
            sCPUType = null;
        else
            sCPUType = resolveCPUType();
        loadNative();
    }
    
     /** Tries to resolve the best type of CPU that we have an optimized jbigi-dll/so for.
      * @return A string containing the CPU-type or null if CPU type is unknown
      */
    private static String resolveCPUType() {
        boolean is64 = (-1 != System.getProperty("os.arch").indexOf("64"));
        if (is64)
            return JBIGI_OPTIMIZATION_ATHLON64;
        
        try {
            CPUInfo c = CPUID.getInfo();
            try {
                _cpuModel = c.getCPUModelString();
            } catch (UnknownCPUException e) {}
            if (c.IsC3Compatible())
                return JBIGI_OPTIMIZATION_VIAC3;
            if (c instanceof AMDCPUInfo) {
                AMDCPUInfo amdcpu = (AMDCPUInfo) c;
                if (amdcpu.IsAthlon64Compatible())
                    return JBIGI_OPTIMIZATION_ATHLON64;
                if (amdcpu.IsAthlonCompatible())
                    return JBIGI_OPTIMIZATION_ATHLON;
                if (amdcpu.IsK6_3_Compatible())
                    return JBIGI_OPTIMIZATION_K6_3;
                if (amdcpu.IsK6_2_Compatible())
                    return JBIGI_OPTIMIZATION_K6_2;
                if (amdcpu.IsK6Compatible())
                    return JBIGI_OPTIMIZATION_K6;
            } else if (c instanceof IntelCPUInfo) {
                IntelCPUInfo intelcpu = (IntelCPUInfo) c;
                if (intelcpu.IsPentium4Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM4;
                if (intelcpu.IsPentium3Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM3;
                if (intelcpu.IsPentium2Compatible())
                    return JBIGI_OPTIMIZATION_PENTIUM2;
                if (intelcpu.IsPentiumMMXCompatible())
                    return JBIGI_OPTIMIZATION_PENTIUMMMX;
                if (intelcpu.IsPentiumCompatible())
                    return JBIGI_OPTIMIZATION_PENTIUM;
            }
            return null;
        } catch (UnknownCPUException e) {
            return null; //TODO: Log something here maybe..
        }
    }

    /**
     * calculate (base ^ exponent) % modulus.
     * 
     * @param base
     *            big endian twos complement representation of the base (but it must be positive)
     * @param exponent
     *            big endian twos complement representation of the exponent
     * @param modulus
     *            big endian twos complement representation of the modulus
     * @return big endian twos complement representation of (base ^ exponent) % modulus
     */
    public native static byte[] nativeModPow(byte base[], byte exponent[], byte modulus[]);
 
    /**
     * Converts a BigInteger byte-array to a 'double'
     * @param ba Big endian twos complement representation of the BigInteger to convert to a double
     * @return The plain double-value represented by 'ba'
     * @deprecated unused
     */
    public native static double nativeDoubleValue(byte ba[]);

    private byte[] cachedBa;

    public NativeBigInteger(byte[] val) {
        super(val);
    }

    public NativeBigInteger(int signum, byte[] magnitude) {
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
    /**Creates a new NativeBigInteger with the same value
    *  as the supplied BigInteger. Warning!, not very efficent
    */
    public NativeBigInteger(BigInteger integer) {
        //Now, why doesn't sun provide a constructor
        //like this one in BigInteger?
        this(integer.toByteArray());
    }

    @Override
    public BigInteger modPow(BigInteger exponent, BigInteger m) {
        if (_nativeOk)
            return new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
        else
            return super.modPow(exponent, m);
    }
    @Override
    public byte[] toByteArray(){
        if(cachedBa == null) //Since we are immutable it is safe to never update the cached ba after it has initially been generated
            cachedBa = super.toByteArray();
        return cachedBa;
    }
    
    /** @deprecated unused */
    @Override
    public double doubleValue() {
        if (_nativeOk)
            return nativeDoubleValue(toByteArray());
        else
            return super.doubleValue();
    }
    /**
     * 
     * @return True iff native methods will be used by this class
     */
    public static boolean isNative(){
        return _nativeOk;
    }
 
    public static String loadStatus() {
        return _loadStatus;
    }
 
    public static String cpuType() {
        if (sCPUType != null)
            return sCPUType;
        return "unrecognized";
    }
 
    public static String cpuModel() {
        return _cpuModel;
    }
 
    /**
     * <p>Compare the BigInteger.modPow/doubleValue vs the NativeBigInteger.modPow/doubleValue of some 
     * really big (2Kbit) numbers 100 different times and benchmark the 
     * performance (or shit a brick if they don't match).  </p>
     *
     */
    public static void main(String args[]) {
        _doLog = true;
        runModPowTest(100);
        // i2p doesn't care about the double values
        //runDoubleValueTest(100);
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

    private static void runModPowTest(int numRuns) {
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
            BigInteger bi = new BigInteger(226, rand); // 2048, rand); //
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
    
/********
    private static void runDoubleValueTest(int numRuns) {
        System.out.println("DEBUG: Warming up the random number generator...");
        SecureRandom rand = new SecureRandom();
        rand.nextBoolean();
        System.out.println("DEBUG: Random number generator warmed up");

        BigInteger jg = new BigInteger(_sampleGenerator);

        long totalTime = 0;
        long javaTime = 0;

        int MULTIPLICATOR = 50000; //Run the doubleValue() calls within a loop since they are pretty fast.. 
        int runsProcessed = 0;
        for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
            NativeBigInteger g = new NativeBigInteger(_sampleGenerator);
            long beforeDoubleValue = System.currentTimeMillis();
            double dNative=0;
            for(int mult=0;mult<MULTIPLICATOR;mult++)
                dNative = g.doubleValue();
            long afterDoubleValue = System.currentTimeMillis();
            double jval=0;
            for(int mult=0;mult<MULTIPLICATOR;mult++)
                jval = jg.doubleValue();
            long afterJavaDoubleValue = System.currentTimeMillis();

            totalTime += (afterDoubleValue - beforeDoubleValue);
            javaTime += (afterJavaDoubleValue - afterDoubleValue);
            if (dNative!=jval) {
                System.err.println("ERROR: [" + runsProcessed + "]\tnative double != java double");
                System.err.println("ERROR: native double value: " + dNative);
                System.err.println("ERROR: java double value: " + jval);
                System.err.println("ERROR: run time: " + totalTime + "ms (" + (totalTime / (runsProcessed + 1)) + "ms each)");
                break;
            } else {
                System.out.println("DEBUG: current run time: " + (afterDoubleValue - beforeDoubleValue) + "ms (total: " 
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
*********/
    
 
    /**
     * <p>Do whatever we can to load up the native library backing this BigInteger's native methods.
     * If it can find a custom built jbigi.dll / libjbigi.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djbigi.enable=false it'll skip all of this.</p>
     *
     */
    private static final void loadNative() {
        try{
        String wantedProp = System.getProperty("jbigi.enable", "true");
        boolean wantNative = "true".equalsIgnoreCase(wantedProp);
        if (wantNative) {
            boolean loaded = loadGeneric("jbigi");
            if (loaded) {
                _nativeOk = true;
                info("Locally optimized native BigInteger library loaded from the library path");
            } else {
                loaded = loadFromResource("jbigi");
                if (loaded) {
                    _nativeOk = true;
                    info("Locally optimized native BigInteger library loaded from resource");
                } else {
                    loaded = loadFromResource(true);
                    if (loaded) {
                        _nativeOk = true;
                        info("Optimized native BigInteger library '"+getResourceName(true)+"' loaded from resource");
                    } else {
                        loaded = loadGeneric(true);
                        if (loaded) {
                            _nativeOk = true;
                            info("Optimized native BigInteger library '"+getMiddleName(true)+"' loaded from somewhere in the path");
                        } else {
                            loaded = loadFromResource(false);
                            if (loaded) {
                                _nativeOk = true;
                                info("Non-optimized native BigInteger library '"+getResourceName(false)+"' loaded from resource");
                            } else {
                                loaded = loadGeneric(false);
                                if (loaded) {
                                    _nativeOk = true;
                                    info("Non-optimized native BigInteger library '"+getMiddleName(false)+"' loaded from somewhere in the path");
                                } else {
                                    _nativeOk = false;          
                                }
                            }       
                        }
                    }
                }
            }
        }
        if (!_nativeOk) {
            warn("Native BigInteger library jbigi not loaded - using pure Java - " +
                 "poor performance may result - see http://www.i2p2.i2p/jbigi.html for help");
        }
        }catch(Exception e){
            warn("Native BigInteger library jbigi not loaded, reason: '"+e.getMessage()+"' - using pure java");
        }
    }
    
    private static void info(String s) {
        if(_doLog)
            System.err.println("INFO: " + s);
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).info(s);
        _loadStatus = s;
    }

    private static void warn(String s) {
        if(_doLog)
            System.err.println("WARNING: " + s);
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).warn(s);
        _loadStatus = s;
    }

    /** 
     * <p>Try loading it from an explictly build jbigi.dll / libjbigi.so first, before 
     * looking into a jbigi.jar for any other libraries.</p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadGeneric(boolean optimized) {
        return loadGeneric(getMiddleName(optimized));
    }
    private static final boolean loadGeneric(String name) {
        try {
            if(name == null)
                return false;
            System.loadLibrary(name);
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
     * byte stream to a temporary file, loading the native library from that file.
     * We then attempt to copy the file from the temporary dir to the base install dir,
     * so we don't have to do this next time - but we don't complain if it fails,
     * so we transparently support read-only base dirs.
     * </p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadFromResource(boolean optimized) {
        String resourceName = getResourceName(optimized);
        return loadFromResource(resourceName);
    }
    private static final boolean loadFromResource(String resourceName) {
        if (resourceName == null) return false;
        //URL resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
        URL resource = ClassLoader.getSystemResource(resourceName);
        if (resource == null) {
            if (_doLog)
                System.err.println("NOTICE: Resource name [" + resourceName + "] was not found");
            return false;
        }

        File outFile = null;
        FileOutputStream fos = null;
        String filename =  _libPrefix + "jbigi" + _libSuffix;
        try {
            InputStream libStream = resource.openStream();
            outFile = new File(I2PAppContext.getGlobalContext().getTempDir(), filename);
            fos = new FileOutputStream(outFile);
            // wtf this was 4096*1024 which is really excessive for a roughly 50KB file
            byte buf[] = new byte[4096];
            while (true) {
                int read = libStream.read(buf);
                if (read < 0) break;
                fos.write(buf, 0, read);
            }
            fos.close();
            fos = null;
            System.load(outFile.getAbsolutePath()); //System.load requires an absolute path to the lib
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
            if (fos != null) {
                try { fos.close(); } catch (IOException ioe) {}
            }
        }
        // copy to install dir, ignore failure
        File newFile = new File(I2PAppContext.getGlobalContext().getBaseDir(), filename);
        FileUtil.copy(outFile.getAbsolutePath(), newFile.getAbsolutePath(), false, true);
        return true;
    }
    
    private static final String getResourceName(boolean optimized) {
        String pref = _libPrefix;
        String middle = getMiddleName(optimized);
        String suff = _libSuffix;
        if(pref == null || middle == null || suff == null)
            return null;
        return pref+middle+suff;
    }
    
    private static final String getMiddleName(boolean optimized){
        
        String sAppend;
        if(optimized)
        {
            if(sCPUType == null)
                return null;
            else
                sAppend = "-"+sCPUType;        
        }else
               sAppend = "-none";

        if(_isWin)
             return "jbigi-windows"+sAppend; // The convention on Windows
        if(_isLinux)
            return "jbigi-linux"+sAppend; // The convention on linux...
        if(_isFreebsd)
            return "jbigi-freebsd"+sAppend; // The convention on freebsd...
        if(_isMac)
            return "jbigi-osx"+sAppend;
        if(_isOS2)
            return "jbigi-os2"+sAppend;
        throw new RuntimeException("Dont know jbigi library name for os type '"+System.getProperty("os.name")+"'");
    }
}
