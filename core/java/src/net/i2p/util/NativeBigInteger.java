package net.i2p.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import freenet.support.CPUInformation.AMDCPUInfo;
import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import freenet.support.CPUInformation.IntelCPUInfo;
import freenet.support.CPUInformation.VIACPUInfo;
import freenet.support.CPUInformation.UnknownCPUException;
import net.i2p.I2PAppContext;
import net.i2p.crypto.CryptoConstants;
import net.i2p.data.DataHelper;

/**
 * <p>BigInteger that takes advantage of the jbigi library for the modPow operation,
 * which accounts for a massive segment of the processing cost of asymmetric 
 * crypto.
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
 * NativeBigInteger.modPow vs. the BigInteger.modPow by running a 2Kbit op 100
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
    private static boolean _nativeOk;
    /** is native lib loaded and at least version 3? */
    private static boolean _nativeOk3;
    /** is native lib loaded and at least version 3, and GMP at least version 5? */
    private static boolean _nativeCTOk;
    private static int _jbigiVersion;
    private static String _libGMPVersion = "unknown";
    private static String _loadStatus = "uninitialized";
    private static String _cpuModel = "uninitialized";
    private static String _extractedResource;

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
                                    I2PAppContext.getCurrentContext() != null &&
                                    I2PAppContext.getCurrentContext().isRouterContext();
    
    /**
     *  The following libraries are be available in jbigi.jar in all I2P versions
     *  originally installed as release 0.6.1.10 or later (released 2006-01-16),
     *  for linux, freebsd, and windows, EXCEPT:
     *   - k63 was removed for linux and freebsd in 0.8.7 (identical to k62)
     *   - athlon64 not available for freebsd
     *   - viac3 not available for windows
     */
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
    /**
     * The 7 optimizations below here are since 0.8.7. Each of the 32-bit processors below
     * needs an explicit fallback in getResourceList() or getMiddleName2().
     * 64-bit processors will fallback to athlon64 and athlon in getResourceList().
     * @since 0.8.7
     */
    private final static String JBIGI_OPTIMIZATION_ATOM       = "atom";
    private final static String JBIGI_OPTIMIZATION_CORE2      = "core2";
    private final static String JBIGI_OPTIMIZATION_COREI      = "corei";
    private final static String JBIGI_OPTIMIZATION_GEODE      = "geode";
    private final static String JBIGI_OPTIMIZATION_NANO       = "nano";
    private final static String JBIGI_OPTIMIZATION_PENTIUMM   = "pentiumm";
    /** all libjbibi builds are identical to pentium3, case handled in getMiddleName2() */
    private final static String JBIGI_OPTIMIZATION_VIAC32     = "viac32";
    /**
     * The optimization levels defined here are since 0.9.26. Each of the 32-bit processors below
     * needs an explicit fallback in getResourceList() or getMiddleName2().
     * 64-bit processors will fallback to athlon64 and athlon in getResourceList().
     * @since 0.9.26
     */
    private final static String JBIGI_OPTIMIZATION_COREI_SBR   = "coreisbr";
    private final static String JBIGI_OPTIMIZATION_COREI_HWL   = "coreihwl";
    private final static String JBIGI_OPTIMIZATION_COREI_BWL   = "coreibwl";
    private final static String JBIGI_OPTIMIZATION_K10         = "k10";
    private final static String JBIGI_OPTIMIZATION_BULLDOZER   = "bulldozer";
    private final static String JBIGI_OPTIMIZATION_PILEDRIVER  = "piledriver";
    private final static String JBIGI_OPTIMIZATION_STEAMROLLER = "steamroller";
    private final static String JBIGI_OPTIMIZATION_EXCAVATOR   = "excavator";
    private final static String JBIGI_OPTIMIZATION_BOBCAT      = "bobcat";
    private final static String JBIGI_OPTIMIZATION_JAGUAR      = "jaguar";

    /**
     * Non-x86, no fallbacks to older libs or to "none"
     * @since 0.8.7
     */
    private final static String JBIGI_OPTIMIZATION_PPC        = "ppc";
    
    /**
     * ARM
     * @since 0.9.26
     */
    private final static String JBIGI_OPTIMIZATION_ARM_ARMV5           = "armv5";
    private final static String JBIGI_OPTIMIZATION_ARM_ARMV6           = "armv6";
    private final static String JBIGI_OPTIMIZATION_ARM_ARMV7           = "armv7";
    private final static String JBIGI_OPTIMIZATION_ARM_CORTEX_A5       = "armcortexa5";
    private final static String JBIGI_OPTIMIZATION_ARM_CORTEX_A7       = "armcortexa7";
    private final static String JBIGI_OPTIMIZATION_ARM_CORTEX_A8       = "armcortexa8";
    private final static String JBIGI_OPTIMIZATION_ARM_CORTEX_A9       = "armcortexa9";
    private final static String JBIGI_OPTIMIZATION_ARM_CORTEX_A15      = "armcortexa15";
    
    /**
     * None, no optimizations. The default fallback for x86.
     * @since 0.9.26
     */
    private final static String JBIGI_OPTIMIZATION_X86       = "none";
    
    /**
     * CPU architecture compatibility lists, in order of preference.
     * 
     * The list is organized by the chronological evolution of architectures.
     * Totally unrelated architectures have separate lists. Sequences of
     * architectures that aren't backwards compatible (performance wise) for
     * some reasons have also been separated out.
     * 
     * Really this could be represented by a DAG, but the benefits don't
     * outweigh the implementation time.
     */

    // none -> {"none"), since 0.9.30
    private final static String[] JBIGI_COMPAT_LIST_NONE          = {JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_PPC           = {JBIGI_OPTIMIZATION_PPC};
    private final static String[] JBIGI_COMPAT_LIST_ARM           = {JBIGI_OPTIMIZATION_ARM_CORTEX_A15, JBIGI_OPTIMIZATION_ARM_CORTEX_A9, JBIGI_OPTIMIZATION_ARM_CORTEX_A8,
                                                                     JBIGI_OPTIMIZATION_ARM_CORTEX_A7, JBIGI_OPTIMIZATION_ARM_CORTEX_A5, JBIGI_OPTIMIZATION_ARM_ARMV7,
                                                                     JBIGI_OPTIMIZATION_ARM_ARMV6, JBIGI_OPTIMIZATION_ARM_ARMV5};
    private final static String[] JBIGI_COMPAT_LIST_VIA           = {JBIGI_OPTIMIZATION_NANO, JBIGI_OPTIMIZATION_VIAC32, JBIGI_OPTIMIZATION_VIAC3,
                                                                     JBIGI_OPTIMIZATION_PENTIUM, JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_AMD_ATHLON    = {JBIGI_OPTIMIZATION_K10, JBIGI_OPTIMIZATION_ATHLON64, JBIGI_OPTIMIZATION_ATHLON,
                                                                     JBIGI_OPTIMIZATION_K6_3, JBIGI_OPTIMIZATION_K6_2, JBIGI_OPTIMIZATION_K6, JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_AMD_GEODE     = {JBIGI_OPTIMIZATION_GEODE, JBIGI_OPTIMIZATION_K6_3, JBIGI_OPTIMIZATION_K6_2, JBIGI_OPTIMIZATION_K6,
                                                                     JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_AMD_APU       = {JBIGI_OPTIMIZATION_JAGUAR, JBIGI_OPTIMIZATION_BOBCAT, JBIGI_OPTIMIZATION_ATHLON64};
    private final static String[] JBIGI_COMPAT_LIST_AMD_BULLDOZER = {JBIGI_OPTIMIZATION_EXCAVATOR, JBIGI_OPTIMIZATION_STEAMROLLER, JBIGI_OPTIMIZATION_PILEDRIVER,
                                                                     JBIGI_OPTIMIZATION_BULLDOZER, JBIGI_OPTIMIZATION_ATHLON64, JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_INTEL_ATOM    = {JBIGI_OPTIMIZATION_ATOM, JBIGI_OPTIMIZATION_PENTIUM3, JBIGI_OPTIMIZATION_PENTIUM2,
                                                                     JBIGI_OPTIMIZATION_PENTIUMMMX, JBIGI_OPTIMIZATION_PENTIUM, JBIGI_OPTIMIZATION_X86,
                                                                     JBIGI_OPTIMIZATION_PENTIUM4};
    private final static String[] JBIGI_COMPAT_LIST_INTEL_PENTIUM = {JBIGI_OPTIMIZATION_PENTIUM4, JBIGI_OPTIMIZATION_PENTIUMM, JBIGI_OPTIMIZATION_PENTIUM3,
                                                                     JBIGI_OPTIMIZATION_PENTIUM2, JBIGI_OPTIMIZATION_PENTIUMMMX, JBIGI_OPTIMIZATION_PENTIUM,
                                                                     JBIGI_OPTIMIZATION_X86};
    private final static String[] JBIGI_COMPAT_LIST_INTEL_CORE    = {JBIGI_OPTIMIZATION_COREI_BWL, JBIGI_OPTIMIZATION_COREI_HWL, JBIGI_OPTIMIZATION_COREI_SBR,
                                                                     JBIGI_OPTIMIZATION_COREI, JBIGI_OPTIMIZATION_CORE2, JBIGI_OPTIMIZATION_PENTIUMM,
                                                                     JBIGI_OPTIMIZATION_PENTIUM3, JBIGI_OPTIMIZATION_X86};

    /**
     * The mapping between CPU architecture and its compatibility list.
     */
    @SuppressWarnings("serial")
    private final static HashMap<String, String[]> JBIGI_COMPAT_MAP = new HashMap<String, String[]>() {{
        // none -> {"none"), since 0.9.30
        put(JBIGI_OPTIMIZATION_X86, JBIGI_COMPAT_LIST_NONE);
        put(JBIGI_OPTIMIZATION_PPC, JBIGI_COMPAT_LIST_PPC);

        put(JBIGI_OPTIMIZATION_ARM_ARMV5,      JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_ARMV6,      JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_ARMV7,      JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_CORTEX_A5,  JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_CORTEX_A7,  JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_CORTEX_A8,  JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_CORTEX_A9,  JBIGI_COMPAT_LIST_ARM);
        put(JBIGI_OPTIMIZATION_ARM_CORTEX_A15, JBIGI_COMPAT_LIST_ARM);

        put(JBIGI_OPTIMIZATION_VIAC3,  JBIGI_COMPAT_LIST_VIA);
        put(JBIGI_OPTIMIZATION_VIAC32, JBIGI_COMPAT_LIST_VIA);
        put(JBIGI_OPTIMIZATION_NANO,   JBIGI_COMPAT_LIST_VIA);

        put(JBIGI_OPTIMIZATION_K6,       JBIGI_COMPAT_LIST_AMD_ATHLON);
        put(JBIGI_OPTIMIZATION_K6_2,     JBIGI_COMPAT_LIST_AMD_ATHLON);
        put(JBIGI_OPTIMIZATION_K6_3,     JBIGI_COMPAT_LIST_AMD_ATHLON);
        put(JBIGI_OPTIMIZATION_ATHLON,   JBIGI_COMPAT_LIST_AMD_ATHLON);
        put(JBIGI_OPTIMIZATION_ATHLON64, JBIGI_COMPAT_LIST_AMD_ATHLON);
        put(JBIGI_OPTIMIZATION_K10, JBIGI_COMPAT_LIST_AMD_ATHLON);

        put(JBIGI_OPTIMIZATION_GEODE, JBIGI_COMPAT_LIST_AMD_GEODE);

        put(JBIGI_OPTIMIZATION_BOBCAT, JBIGI_COMPAT_LIST_AMD_APU);
        put(JBIGI_OPTIMIZATION_JAGUAR, JBIGI_COMPAT_LIST_AMD_APU);

        put(JBIGI_OPTIMIZATION_BULLDOZER,   JBIGI_COMPAT_LIST_AMD_BULLDOZER);
        put(JBIGI_OPTIMIZATION_PILEDRIVER,  JBIGI_COMPAT_LIST_AMD_BULLDOZER);
        put(JBIGI_OPTIMIZATION_STEAMROLLER, JBIGI_COMPAT_LIST_AMD_BULLDOZER);
        put(JBIGI_OPTIMIZATION_EXCAVATOR,   JBIGI_COMPAT_LIST_AMD_BULLDOZER);

        put(JBIGI_OPTIMIZATION_ATOM, JBIGI_COMPAT_LIST_INTEL_ATOM);

        put(JBIGI_OPTIMIZATION_PENTIUM,    JBIGI_COMPAT_LIST_INTEL_PENTIUM);
        put(JBIGI_OPTIMIZATION_PENTIUMMMX, JBIGI_COMPAT_LIST_INTEL_PENTIUM);
        put(JBIGI_OPTIMIZATION_PENTIUM2,   JBIGI_COMPAT_LIST_INTEL_PENTIUM);
        put(JBIGI_OPTIMIZATION_PENTIUM3,   JBIGI_COMPAT_LIST_INTEL_PENTIUM);
        put(JBIGI_OPTIMIZATION_PENTIUMM,   JBIGI_COMPAT_LIST_INTEL_PENTIUM);
        put(JBIGI_OPTIMIZATION_PENTIUM4,   JBIGI_COMPAT_LIST_INTEL_PENTIUM);

        put(JBIGI_OPTIMIZATION_PENTIUM3,  JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_PENTIUMM,  JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_CORE2,     JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_COREI,     JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_COREI_SBR, JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_COREI_HWL, JBIGI_COMPAT_LIST_INTEL_CORE);
        put(JBIGI_OPTIMIZATION_COREI_BWL, JBIGI_COMPAT_LIST_INTEL_CORE);
    }};

    /**
     * Operating systems
     */
    private static final boolean _isWin = SystemVersion.isWindows();
    private static final boolean _isOS2 = System.getProperty("os.name").startsWith("OS/2");
    private static final boolean _isMac = SystemVersion.isMac();
    private static final boolean _isLinux = System.getProperty("os.name").toLowerCase(Locale.US).contains("linux");
    private static final boolean _isKFreebsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("kfreebsd");
    private static final boolean _isFreebsd = (!_isKFreebsd) && System.getProperty("os.name").toLowerCase(Locale.US).contains("freebsd");
    private static final boolean _isNetbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("netbsd");
    private static final boolean _isOpenbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("openbsd");
    private static final boolean _isSunos = System.getProperty("os.name").toLowerCase(Locale.US).contains("sunos");
    private static final boolean _isAndroid = SystemVersion.isAndroid();

    /*
     * This isn't always correct.
     * http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     * http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     * http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     * sun.arch.data.model not on all JVMs
     * sun.arch.data.model == 64 => 64 bit processor
     * sun.arch.data.model == 32 => A 32 bit JVM but could be either 32 or 64 bit processor or libs
     * os.arch contains "64" could be 32 or 64 bit libs
     */
    private static final boolean _is64 = SystemVersion.is64Bit();

    private static final boolean _isX86 = SystemVersion.isX86();

    private static final boolean _isArm = SystemVersion.isARM();

    private static final boolean _isPPC = System.getProperty("os.arch").contains("ppc");

    /* libjbigi.so vs jbigi.dll */
    private static final String _libPrefix = (_isWin || _isOS2 ? "" : "lib");
    private static final String _libSuffix = (_isWin || _isOS2 ? ".dll" : _isMac ? ".jnilib" : ".so");

    private final static String sCPUType; //The CPU Type to optimize for (one of the above strings)
    
    static {
        sCPUType = resolveCPUType();
        loadNative();
    }
    
    /**
      * Tries to resolve the best type of CPU that we have an optimized jbigi-dll/so for.
      * This is for x86 only.
      * @return A string containing the CPU-type or null if CPU type is unknown
      */
    private static String resolveCPUType() {
        if(_isX86) {
            try {
                //System.out.println("resolveType() x86");
                CPUInfo c = CPUID.getInfo();
                try {
                    _cpuModel = c.getCPUModelString();
                    //System.out.println("CPUModel: " + _cpuModel.toString());
                } catch (UnknownCPUException e) {}
                if (c instanceof VIACPUInfo) {
                	VIACPUInfo viacpu = (VIACPUInfo) c;
                	if (viacpu.IsNanoCompatible())
                	    return JBIGI_OPTIMIZATION_NANO;
                	return JBIGI_OPTIMIZATION_VIAC3;
                } else if (c instanceof AMDCPUInfo) {
                    AMDCPUInfo amdcpu = (AMDCPUInfo) c;
                    if (amdcpu.IsExcavatorCompatible())
                        return JBIGI_OPTIMIZATION_EXCAVATOR;
                    if (amdcpu.IsSteamrollerCompatible())
                        return JBIGI_OPTIMIZATION_STEAMROLLER;
                    if (amdcpu.IsPiledriverCompatible())
                        return JBIGI_OPTIMIZATION_PILEDRIVER;
                    if (amdcpu.IsBulldozerCompatible())
                        return JBIGI_OPTIMIZATION_BULLDOZER;
                    if (amdcpu.IsJaguarCompatible())
                        return JBIGI_OPTIMIZATION_JAGUAR;
                    if (amdcpu.IsBobcatCompatible())
                        return JBIGI_OPTIMIZATION_BOBCAT;
                    if (amdcpu.IsK10Compatible())
                        return JBIGI_OPTIMIZATION_K10;
                    if (amdcpu.IsAthlon64Compatible())
                        return JBIGI_OPTIMIZATION_ATHLON64;
                    if (amdcpu.IsAthlonCompatible())
                        return JBIGI_OPTIMIZATION_ATHLON;
                    if (amdcpu.IsGeodeCompatible())
                        return JBIGI_OPTIMIZATION_GEODE;
                    if (amdcpu.IsK6_3_Compatible())
                        return JBIGI_OPTIMIZATION_K6_3;
                    if (amdcpu.IsK6_2_Compatible())
                        return JBIGI_OPTIMIZATION_K6_2;
                    if (amdcpu.IsK6Compatible())
                        return JBIGI_OPTIMIZATION_K6;
                } else if (c instanceof IntelCPUInfo) {
                    IntelCPUInfo intelcpu = (IntelCPUInfo) c;
                    if (intelcpu.IsBroadwellCompatible())
                        return JBIGI_OPTIMIZATION_COREI_BWL;
                    if (intelcpu.IsHaswellCompatible())
                        return JBIGI_OPTIMIZATION_COREI_HWL;
                    if (intelcpu.IsSandyCompatible())
                        return JBIGI_OPTIMIZATION_COREI_SBR;
                    if (intelcpu.IsCoreiCompatible())
                        return JBIGI_OPTIMIZATION_COREI;
                    if (intelcpu.IsCore2Compatible())
                        return JBIGI_OPTIMIZATION_CORE2;
                    // The isAtomCompatible check should be done before the Pentium4
                    // check since they are compatible, but Atom performs better with
                    // the JBIGI_OPTIMIZATION_ATOM compability list.
                    if (intelcpu.IsAtomCompatible())
                        return JBIGI_OPTIMIZATION_ATOM;
                    if (intelcpu.IsPentium4Compatible())
                        return JBIGI_OPTIMIZATION_PENTIUM4;
                    if (intelcpu.IsPentiumMCompatible())
                        return JBIGI_OPTIMIZATION_PENTIUMM;
                    if (intelcpu.IsPentium3Compatible())
                        return JBIGI_OPTIMIZATION_PENTIUM3;
                    if (intelcpu.IsPentium2Compatible())
                        return JBIGI_OPTIMIZATION_PENTIUM2;
                    if (intelcpu.IsPentiumMMXCompatible())
                        return JBIGI_OPTIMIZATION_PENTIUMMMX;
                    if (intelcpu.IsPentiumCompatible())
                        return JBIGI_OPTIMIZATION_PENTIUM;
                }
            } catch (UnknownCPUException e) {
            }
            // always try "none" if we don't know the x86 type,
            // in case of CPUID fail or not finding compatibility above
            return JBIGI_OPTIMIZATION_X86;
        } else if (_isArm) {
            if (_isWin)
                return null;
            Map<String, String> cpuinfo = getCPUInfo();
            String implementer = cpuinfo.get("cpu implementer");
            String part = cpuinfo.get("cpu part");
            
            // If CPU implementer is ARM
            if (implementer != null && part != null && implementer.contains("0x41")) {
                if (part.contains("0xc0f")) {
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A15;
                } else if (part.contains("0xc0e")) {
                    // Actually A17, but it's derived from A15
                    // and GMP only support A15
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A15;
                } else if (part.contains("0xc0d")) {
                    // Actually A12, but it's derived from A15
                    // and GMP only supports A15
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A15;
                } else if (part.contains("0xc09")) {
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A9;
                } else if (part.contains("0xc08")) {
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A8;
                } else if (part.contains("0xc07")) {
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A7;
                } else if (part.contains("0xc05")) {
                    return JBIGI_OPTIMIZATION_ARM_CORTEX_A5;
                }
            }

            // We couldn't identify the implementer
            // Let's try by looking at cpu arch
            String arch = cpuinfo.get("cpu architecture");
            String model = cpuinfo.get("model name");
            if (arch != null) {
                //CPU architecture: 5TEJ
                //CPU architecture: 7
                if (arch.startsWith("7")) {
                    // Raspberry Pi workaround
                    // Processor       : ARMv6-compatible processor rev 7 (v6l)
                    // CPU architecture: 7
                    if (model != null && model.contains("ARMv6"))
                        return JBIGI_OPTIMIZATION_ARM_ARMV6;
                    return JBIGI_OPTIMIZATION_ARM_ARMV7;
                }
                if (arch.startsWith("6"))
                    return JBIGI_OPTIMIZATION_ARM_ARMV6;
                if (arch.startsWith("5"))
                    return JBIGI_OPTIMIZATION_ARM_ARMV5;
            }

            // We couldn't identify the architecture
            // Let's try by looking at model name
            if (model != null) {
                if (model.contains("ARMv7"))
                    return JBIGI_OPTIMIZATION_ARM_ARMV7;
                if (model.contains("ARMv6"))
                    return JBIGI_OPTIMIZATION_ARM_ARMV6;
                if (model.contains("ARMv5"))
                    return JBIGI_OPTIMIZATION_ARM_ARMV5;
            }
                
            // If we didn't find a match, return null
            return null;
        } else if (_isPPC && !_isMac) {
            return JBIGI_OPTIMIZATION_PPC;
        }

        return null;
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
     * @throws ArithmeticException if modulus &lt;= 0 (since libjbigi version 3)
     * @return big endian twos complement representation of (base ^ exponent) % modulus
     */
    private native static byte[] nativeModPow(byte base[], byte exponent[], byte modulus[]);

    /**
     * calculate (base ^ exponent) % modulus.
     * Constant Time.
     * 
     * @param base
     *            big endian twos complement representation of the base (but it must be positive)
     * @param exponent
     *            big endian twos complement representation of the exponent
     * @param modulus
     *            big endian twos complement representation of the modulus
     * @return big endian twos complement representation of (base ^ exponent) % modulus
     * @throws ArithmeticException if modulus &lt;= 0
     * @since 0.9.26 and libjbigi version 3
     */
    private native static byte[] nativeModPowCT(byte base[], byte exponent[], byte modulus[]);

    /**
     *  @since 0.9.26 and libjbigi version 3
     *  @throws ArithmeticException
     */
    private native static byte[] nativeModInverse(byte base[], byte d[]);
 
    /**
     *  Only for testing jbigi's negative conversion functions!
     *  @since 0.9.26
     */
    //private native static byte[] nativeNeg(byte d[]);

    /**
     *  Get the jbigi version, only available since jbigi version 3
     *  Caller must catch Throwable
     *  @since 0.9.26
     */
    private native static int nativeJbigiVersion();
 
    /**
     *  Get the libmp version, only available since jbigi version 3
     *  @since 0.9.26
     */
    private native static int nativeGMPMajorVersion();
 
    /**
     *  Get the libmp version, only available since jbigi version 3
     *  @since 0.9.26
     */
    private native static int nativeGMPMinorVersion();
 
    /**
     *  Get the libmp version, only available since jbigi version 3
     *  @since 0.9.26
     */
    private native static int nativeGMPPatchVersion();

    /**
     *  Get the jbigi version
     *  @return 0 if no jbigi available, 2 if version not supported
     *  @since 0.9.26
     */
    private static int fetchJbigiVersion() {
        if (!_nativeOk)
            return 0;
        try {
            return nativeJbigiVersion();
        } catch (Throwable t) {
            return 2;
        }
    }

    /**
     *  Set the jbigi and libgmp versions. Call after loading.
     *  Sets _jbigiVersion, _nativeOk3, and _libGMPVersion.
     *  @since 0.9.26
     */
    private static void setVersions() {
        _jbigiVersion = fetchJbigiVersion();
        _nativeOk3 = _jbigiVersion > 2;
        if (_nativeOk3) {
            try {
                int maj = nativeGMPMajorVersion();
                int min = nativeGMPMinorVersion();
                int pat = nativeGMPPatchVersion();
                _libGMPVersion = maj + "." + min + "." + pat;
                _nativeCTOk = maj >= 5;
            } catch (Throwable t) {
                warn("jbigi version " + _jbigiVersion + " but GMP version not available???", t);
            }
        }
        // Don't overwrite _loadStatus
        //warn("jbigi version: " + _jbigiVersion + "; GMP version: " + _libGMPVersion);
    }

    /**
     *  Get the jbigi version
     *  @return 0 if no jbigi available, 2 if version info not supported
     *  @since 0.9.26
     */
    public static int getJbigiVersion() {
        return _jbigiVersion;
    }

    /**
     *  Get the libgmp version
     *  @return "unknown" if no jbigi available or if version not supported
     *  @since 0.9.26
     */
    public static String getLibGMPVersion() {
        return _libGMPVersion;
    }

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

    /**
     *  @param m must be postive
     *  @param exponent must be postive
     *  @throws ArithmeticException if m &lt;= 0 or exponent &lt;=0
     */
    @Override
    public BigInteger modPow(BigInteger exponent, BigInteger m) {
        // Where negative or zero values aren't legal in modPow() anyway, avoid native,
        // as the Java code will throw an exception rather than silently fail or crash the JVM
        // Negative values supported as of version 3
        if (_nativeOk3 || (_nativeOk && signum() >= 0 && exponent.signum() >= 0 && m.signum() > 0))
            return new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
        else
            return super.modPow(exponent, m);
    }

    /**
     *  @param exponent must be postive
     *  @param m must be postive and odd
     *  @throws ArithmeticException if m &lt;= 0 or m is even or exponent &lt;=0
     *  @since 0.9.26 and libjbigi version 3 and GMP version 5
     */
    public BigInteger modPowCT(BigInteger exponent, BigInteger m) {
        if (_nativeCTOk)
            return new NativeBigInteger(nativeModPowCT(toByteArray(), exponent.toByteArray(), m.toByteArray()));
        else
            return modPow(exponent, m);
    }

    /**
     *  @throws ArithmeticException if not coprime with m, or m &lt;= 0
     *  @since 0.9.26 and libjbigi version 3
     */
    @Override
    public BigInteger modInverse(BigInteger m) {
        // Where negative or zero values aren't legal in modInverse() anyway, avoid native,
        // as the Java code will throw an exception rather than silently fail or crash the JVM
        // Note that 'this' can be negative
        // If this and m are not coprime, gmp will do a divide by zero exception and crash the JVM.
        // super will throw an ArithmeticException
        if (_nativeOk3)
            return new NativeBigInteger(nativeModInverse(toByteArray(), m.toByteArray()));
        else
            return super.modInverse(m);
    }

    /** caches */
    @Override
    public byte[] toByteArray(){
        if(cachedBa == null) //Since we are immutable it is safe to never update the cached ba after it has initially been generated
            cachedBa = super.toByteArray();
        return cachedBa;
    }
    
    /**
     * 
     * @return True iff native methods will be used by this class
     */
    public static boolean isNative(){
        return _nativeOk;
    }
 
    /**
     * @return A string suitable for display to the user
     */
    public static String loadStatus() {
        return _loadStatus;
    }
 
    /**
     *  The name of the library loaded, if known.
     *  Null if unknown or not loaded.
     *  Currently non-null only if extracted from jbigi.jar.
     *
     *  @since 0.9.17
     */
    public static String getLoadedResourceName() {
        return _extractedResource;
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
     * <p>Compare the BigInteger.modPow vs the NativeBigInteger.modPow of some 
     * really big (2Kbit) numbers 100 different times and benchmark the 
     * performance.</p>
     * 
     * @param args -n Disable native test
     *
     */
    public static void main(String args[]) {
        _doLog = true;
        String path = System.getProperty("java.library.path");
        String name = _libPrefix + "jbigi" + _libSuffix;
        System.out.println("Native library search path: " + path);
        if (_nativeOk) {
            String sep = System.getProperty("path.separator");
            String[] paths = DataHelper.split(path, sep);
            for (String p : paths) {
                File f = new File(p, name);
                if (f.exists()) {
                    System.out.println("Found native library: " + f);
                    break;
                }
            }
        } else {
            System.out.println("Failed to load native library. Please verify the existence of the " +
                               name + " file in the library path, or set -Djava.library.path=. in the command line");
        }
        boolean nativeOnly = args.length > 0 && args[0].equals("-n");
        if (nativeOnly && !_nativeOk) {
            System.exit(1);
        }
        if (_nativeOk) {
            System.out.println("JBigi Version: " + _jbigiVersion + " GMP Version: " + _libGMPVersion);
            if (_extractedResource != null)
                System.out.println("Using native resource: " + _extractedResource);
        }
        System.out.println("DEBUG: Warming up the random number generator...");
        SecureRandom rand = RandomSource.getInstance();
        rand.nextBoolean();
        System.out.println("DEBUG: Random number generator warmed up");

        //if (_nativeOk3)
        //    testnegs();

        runModPowTest(100, 1, nativeOnly);
        if (_nativeOk3) {
            System.out.println("ModPowCT test:");
            runModPowTest(100, 2, nativeOnly);
            System.out.println("ModInverse test:");
            runModPowTest(10000, 3, nativeOnly);
        }
    }

    /** version >= 3 only */
/****
    private static void testnegs() {
        for (int i = -66000; i <= 66000; i++) {
            testneg(i);
        }
        test(3, 11);
        test(25, 4);
    }

    private static void testneg(long a) {
        NativeBigInteger ba = new NativeBigInteger(Long.toString(a));
        long r = ba.testNegate().longValue();
        if (r != 0 - a)
            warn("FAIL Neg test " + a + " = " + r);
    }

    private static void test(long a, long b) {
        BigInteger ba = new NativeBigInteger(Long.toString(a));
        BigInteger bb = new NativeBigInteger(Long.toString(b));
        long r1 = a * b;
        long r2 = ba.multiply(bb).longValue();
        if (r1 != r2)
            warn("FAIL Mul test " + a + ' ' + b + " = " + r2);
        r1 = a / b;
        r2 = ba.divide(bb).longValue();
        if (r1 != r2)
            warn("FAIL Div test " + a + ' ' + b + " = " + r2);
        r1 = a % b;
        r2 = ba.mod(bb).longValue();
        if (r1 != r2)
            warn("FAIL Mod test " + a + ' ' + b + " = " + r2);
    }

    private BigInteger testNegate() {
        return new NativeBigInteger(nativeNeg(toByteArray()));
    }

****/

    /**
     *  @param mode 1: modPow; 2: modPowCT 3: modInverse
     */
    private static void runModPowTest(int numRuns, int mode, boolean nativeOnly) {
        SecureRandom rand = RandomSource.getInstance();
        /* the sample numbers are elG generator/prime so we can test with reasonable numbers */
        byte[] sampleGenerator = CryptoConstants.elgg.toByteArray();
        byte[] samplePrime = CryptoConstants.elgp.toByteArray();

        BigInteger jg = new BigInteger(sampleGenerator);
        NativeBigInteger ng = CryptoConstants.elgg;
        BigInteger jp = new BigInteger(samplePrime);

        long totalTime = 0;
        long javaTime = 0;

        int runsProcessed = 0;
        for (int i = 0; i < 1000; i++) {
            // JIT warmup
            BigInteger bi;
            do {
                bi = new BigInteger(16, rand);
            } while (bi.signum() == 0);
            if (mode == 1)
                jg.modPow(bi, jp);
            else if (mode == 2)
                ng.modPowCT(bi, jp);
            else
                bi.modInverse(jp);
        }
        BigInteger myValue = null, jval;
        final NativeBigInteger g = CryptoConstants.elgg;
        final NativeBigInteger p = CryptoConstants.elgp;
        // Our ElG prime P is 1061 bits, so make K smaller so there's
        // no chance of it being equal to or a multiple of P, i.e. not coprime,
        // so the modInverse test won't fail
        final int numBits = (mode == 3) ? 1060 : 2048;
        for (runsProcessed = 0; runsProcessed < numRuns; runsProcessed++) {
            // 0 is not coprime with anything
            BigInteger bi;
            do {
                bi = new BigInteger(numBits, rand);
            } while (bi.signum() == 0);
            NativeBigInteger k = new NativeBigInteger(1, bi.toByteArray());
            //// Native
            long beforeModPow = System.nanoTime();
            if (_nativeOk) {
                if (mode == 1)
                    myValue = g.modPow(k, p);
                else if (mode == 2)
                    myValue = g.modPowCT(bi, jp);
                else
                    myValue = k.modInverse(p);
            }
            long afterModPow = System.nanoTime();
            totalTime += (afterModPow - beforeModPow);
            //// Java
            if (!nativeOnly) {
                if (mode != 3)
                    jval = jg.modPow(bi, jp);
                else
                    jval = bi.modInverse(jp);
                long afterJavaModPow = System.nanoTime();

                javaTime += (afterJavaModPow - afterModPow);
                if (_nativeOk && !myValue.equals(jval)) {
                    System.err.println("ERROR: [" + runsProcessed + "]\tnative modPow != java modPow");
                    System.err.println("ERROR: native modPow value: " + myValue.toString());
                    System.err.println("ERROR: java modPow value: " + jval.toString());
                    break;
                //} else if (mode == 1) {
                //    System.out.println(String.format("DEBUG: current run time: %7.3f ms (total: %9.3f ms, %7.3f ms each)",
                //                                     (afterModPow - beforeModPow) / 1000000d,
                //                                     totalTime / 1000000d,
                //                                     totalTime / (1000000d * (runsProcessed + 1))));
                }
            }
        }
        double dtotal = totalTime / 1000000f;
        double djava = javaTime / 1000000f;
        if (_nativeOk)
            System.out.println(String.format("INFO: run time: %.3f ms (%.3f ms each)",
                                         dtotal, dtotal / (runsProcessed + 1)));
        if (numRuns == runsProcessed)
            System.out.println("INFO: " + runsProcessed + " runs complete without any errors");
        else
            System.out.println("ERROR: " + runsProcessed + " runs until we got an error");

        if (_nativeOk) {
            System.out.println(String.format("Native run time: \t%9.3f ms (%7.3f ms each)",
                                             dtotal, dtotal / (runsProcessed + 1)));
            if (!nativeOnly) {
                System.out.println(String.format("Java run time:   \t%9.3f ms (%7.3f ms each)",
                                             djava, djava / (runsProcessed + 1)));
                System.out.println(String.format("Native = %.3f%% of pure Java time",
                                             dtotal * 100.0d / djava));
                if (dtotal < djava)
                    System.out.println(String.format("Native is BETTER by a factor of %.3f -- YAY!", djava / dtotal));
                else
                    System.out.println(String.format("Native is WORSE by a factor of %.3f -- BOO!", dtotal / djava));
            }
        } else {
            System.out.println(String.format("java run time: \t%.3f ms (%.3f ms each)",
                                             djava, djava / (runsProcessed + 1)));
            System.out.println("However, we couldn't load the native library, so this doesn't test much");
        }
    }
    
    /**
     * <p>Do whatever we can to load up the native library backing this BigInteger's native methods.
     * If it can find a custom built jbigi.dll / libjbigi.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djbigi.enable=false it'll skip all of this.</p>
     *
     * <pre>
     * Load order (using linux naming with cpu type "xxx")
     * Old order 0.8.6 and earlier:
     *   - filesystem libjbigi.so
     *   - jbigi.jar libjbigi.so
     *   - jbigi.jar libjbigi-linux-xxx.so
     *   - filesystem libjbigi-linux-xxx.so
     *   - jbigi.jar libjbigi-linux-none.so
     *   - filesystem libjbigi-linux-none.so
     *
     * New order as of 0.8.7:
     *   - filesystem libjbigi.so
     *   - jbigi.jar libjbigi-linux-xxx_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-athlon64_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-xxx.so
     *   - jbigi.jar libjbigi-linux-athlon64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-yyy.so 0 or more other alternates
     *   - jbigi.jar libjbigi-linux-none_64.so if it may be 64 bit
     *   - jbigi.jar libjbigi-linux-none.so
     * </pre>
     */
    private static final void loadNative() {
        try{
            String wantedProp = System.getProperty("jbigi.enable", "true");
            boolean wantNative = Boolean.parseBoolean(wantedProp);
            if (wantNative) {
                debug("trying loadGeneric");
                boolean loaded = loadGeneric("jbigi");
                if (loaded) {
                    _nativeOk = true;
                    String s = I2PAppContext.getGlobalContext().getProperty("jbigi.loadedResource");
                    if (s != null)
                        info("Locally optimized library " + s + " loaded from file");
                    else
                        info("Locally optimized native BigInteger library loaded from file");
                } else {
                    List<String> toTry = getResourceList();
                    debug("loadResource list to try is: " + toTry);
                    for (String s : toTry) {
                        debug("Trying to load resource " + s);
                        if (loadFromResource(s)) {
                            _nativeOk = true;
                            _extractedResource = s;
                            info("Native BigInteger library " + s + " loaded from resource");
                            break;
                        }
                    }
                }
            }
            if (!_nativeOk) {
                warn("Native BigInteger library jbigi not loaded - using pure Java - " +
                     "poor performance may result - see http://i2p-projekt.i2p/jbigi for help");
            } else {
                setVersions();
            }
        } catch(Exception e) {
            warn("Native BigInteger library jbigi not loaded, using pure java", e);
        }
    }
    
    /** @since 0.8.7 */
    private static void debug(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).debug(s);
    }

    
    private static void info(String s) {
        if(_doLog)
            System.err.println("INFO: " + s);
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).info(s);
        _loadStatus = s;
    }

    private static void warn(String s) {
        warn(s, null);
    }

    /** @since 0.8.7 */
    private static void warn(String s, Throwable t) {
        if(_doLog) {
            System.err.println("WARNING: " + s);
            if (t != null)
                t.printStackTrace();
        }
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).warn(s, t);
        if (t != null)
            _loadStatus = s + ' ' + t;
        else
            _loadStatus = s;
    }
    
    /** @since 0.9.26 */
    private static void error(String s) {
        error(s, null);
    }
    
    /** @since 0.9.26 */
    private static void error(String s, Throwable t) {
        if(_doLog) {
            System.err.println("ERROR: " + s);
            if (t != null)
                t.printStackTrace();
        }
        I2PAppContext.getGlobalContext().logManager().getLog(NativeBigInteger.class).error(s, t);
        if (t != null)
            _loadStatus = s + ' ' + t;
        else
            _loadStatus = s;
    }
    
    /** 
     * <p>Try loading it from an explictly build jbigi.dll / libjbigi.so first, before 
     * looking into a jbigi.jar for any other libraries.</p>
     *
     * @return true if it was loaded successfully, else false
     *
     */
/****
    private static final boolean loadGeneric(boolean optimized) {
        return loadGeneric(getMiddleName(optimized));
    }
****/

    private static final boolean loadGeneric(String name) {
        try {
            if(name == null)
                return false;
            System.loadLibrary(name);
            return true;
        } catch (UnsatisfiedLinkError ule) {
            if (_isAndroid) {
                // Unfortunately,
                // this is not interesting on Android, it says "file not found"
                // on link errors too.
                warn("jbigi loadLibrary() fail", ule);
            }
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
/****
    private static final boolean loadFromResource(boolean optimized) {
        String resourceName = getResourceName(optimized);
        return loadFromResource(resourceName);
    }
****/

    private static final boolean loadFromResource(String resourceName) {
        if (resourceName == null) return false;
        //URL resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
        URL resource = ClassLoader.getSystemResource(resourceName);
        if (resource == null) {
            info("Resource name [" + resourceName + "] was not found");
            return false;
        }

        InputStream libStream = null;
        File outFile = null;
        FileOutputStream fos = null;
        String filename =  _libPrefix + "jbigi" + _libSuffix;
        try {
            libStream = resource.openStream();
            outFile = new File(I2PAppContext.getGlobalContext().getTempDir(), filename);
            fos = new FileOutputStream(outFile);
            DataHelper.copy(libStream, fos);
            fos.close();
            fos = null;
            System.load(outFile.getAbsolutePath()); //System.load requires an absolute path to the lib
            info("Loaded library: " + resource);
        } catch (UnsatisfiedLinkError ule) {
            // don't include the exception in the message - too much
            warn("Failed to load the resource " + resourceName + " - not a valid library for this platform");
            if (outFile != null)
                outFile.delete();
            return false;
        } catch (IOException ioe) {
            warn("Problem writing out the temporary native library data: " + ioe);
            if (outFile != null)
                outFile.delete();
            return false;
        } finally {
            if (libStream != null) try { libStream.close(); } catch (IOException ioe) {}
            if (fos != null) {
                try { fos.close(); } catch (IOException ioe) {}
            }
        }
        // copy to install dir, ignore failure
        File newFile = new File(I2PAppContext.getGlobalContext().getBaseDir(), filename);
        FileUtil.copy(outFile, newFile, false, true);
        return true;
    }
    
    /**
     *  Generate a list of resources to search for, in-order.
     *  See loadNative() comments for more info.
     *  @return non-null
     *  @since 0.8.7
     */
    private static List<String> getResourceList() {
        if (_isAndroid)
            return Collections.emptyList();
        List<String> rv = new ArrayList<String>(20);
        String primary = getMiddleName2(true);
        // primary may be null
        String[] compatList = JBIGI_COMPAT_MAP.get(primary);

        if (primary != null && compatList == null) {
            error("A bug relating to how jbigi is loaded for \"" + primary + "\" has been spotted");
        }
        
        if (primary != null &&
            compatList != null) {
            // Add all architectural parents of this arch to the resource list
            // Skip architectures that are newer than our target
            int i = 0;
            for (; i < compatList.length; ++i) {
                if (compatList[i].equals(primary)) {
                    break;
                }
            }
            for (; i < compatList.length; ++i) {
                String middle = getMiddleName1();
                if (_is64) {
                    rv.add(_libPrefix + middle + compatList[i] + "_64" + _libSuffix);
                }
                rv.add(_libPrefix + middle + compatList[i] + _libSuffix);
            }
            
            if (rv.isEmpty()) {
                error("Couldn't find the arch \"" + primary + "\" in its compatibility map \"" +
                      primary + ": " + Arrays.toString(compatList) + "\"");
            }
        }
        
        //System.out.println("Primary: " + primary);
        //System.out.println("ResourceList: " + rv.toString());
        return rv;
    }

    /**
     *  Return /proc/cpuinfo as a key-value mapping.
     *  All keys mapped to lower case.
     *  All keys and values trimmed.
     *  For dup keys, first one wins.
     *  Currently used for ARM only.
     *  @return non-null, empty on failure
     *  @since 0.9.1
     */
    private static Map<String, String> getCPUInfo() {
        Map<String, String> rv = new HashMap<String, String>(32);
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/cpuinfo"), "ISO-8859-1"), 4096);
            String line = null;
            while ( (line = in.readLine()) != null) {
                String[] parts = DataHelper.split(line, ":", 2);
                if (parts.length < 2)
                    continue;
                String key = parts[0].trim().toLowerCase(Locale.US);
                if (!rv.containsKey(key))
                    rv.put(key, parts[1].trim());
            }
        } catch (IOException ioe) {
            warn("Unable to read /proc/cpuinfo", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  @return may be null if optimized is true
     */
/****
    private static final String getResourceName(boolean optimized) {
        String middle = getMiddleName(optimized);
        if (middle == null)
            return null;
        return _libPrefix + middle + _libSuffix;
    }
****/
    
    /**
     *  @return may be null if optimized is true; returns jbigi-xxx-none if optimize is false
     */
/****
    private static final String getMiddleName(boolean optimized) {
        String m2 = getMiddleName2(optimized);
        if (m2 == null)
            return null;
        return getMiddleName1() + m2;
    }
****/

    /**
     *  @return may be null if optimized is true; returns "none" if optimize is false
     *  @since 0.8.7
     */
    private static final String getMiddleName2(boolean optimized) {
        String sAppend;
        if (optimized) {
            if (sCPUType == null)
                return null;
            // Add exceptions here if library files are identical,
            // instead of adding duplicates to jbigi.jar
            if (sCPUType.equals(JBIGI_OPTIMIZATION_K6_3) && !_isWin)
                // k62 and k63 identical except on windows
                sAppend = JBIGI_OPTIMIZATION_K6_2;
            // core2 is always a fallback for corei in getResourceList()
            //else if (sCPUType.equals(JBIGI_OPTIMIZATION_COREI) && (!_is64) && ((_isKFreebsd) || (_isNetbsd) || (_isOpenbsd)))
                // corei and core2 are identical on 32bit kfreebsd, openbsd, and netbsd
                //sAppend = JBIGI_OPTIMIZATION_CORE2;
            else if (sCPUType.equals(JBIGI_OPTIMIZATION_PENTIUM2) && _isSunos && _isX86)
                // pentium2 and pentium3 identical on X86 Solaris
                sAppend = JBIGI_OPTIMIZATION_PENTIUM3;
            else if (sCPUType.equals(JBIGI_OPTIMIZATION_VIAC32))
                // viac32 and pentium3 identical
                sAppend = JBIGI_OPTIMIZATION_PENTIUM3;
            //else if (sCPUType.equals(JBIGI_OPTIMIZATION_VIAC3) && _isWin)
                // FIXME no viac3 available for windows, what to use instead?
            else
                sAppend = sCPUType;
        } else {
            sAppend = "none";
        }
        return sAppend;
    }

    /**
     *  @return "jbigi-xxx-"
     *  @since 0.8.7
     */
    private static final String getMiddleName1() {
        if(_isWin)
             return "jbigi-windows-";
        if(_isKFreebsd)
            return "jbigi-kfreebsd-";
        if(_isFreebsd)
            return "jbigi-freebsd-";
        if(_isNetbsd)
            return "jbigi-netbsd-";
        if(_isOpenbsd)
            return "jbigi-openbsd-";
        if(_isMac)
            return "jbigi-osx-";
        if(_isOS2)
            return "jbigi-os2-";
        if(_isSunos)
            return "jbigi-solaris-";
        //throw new RuntimeException("Dont know jbigi library name for os type '"+System.getProperty("os.name")+"'");
        // use linux as the default, don't throw exception
        return "jbigi-linux-";
    }

    @Override
    public boolean equals(Object o) {
        // for findbugs
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        // for findbugs
        return super.hashCode();
    }
}
