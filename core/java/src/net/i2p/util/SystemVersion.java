package net.i2p.util;

/*
 * public domain
 */

import java.lang.reflect.Field;
import java.util.TimeZone;

import net.i2p.I2PAppContext;

/**
 * Methods to find out what system we are running on
 *
 * @since 0.9.3 consolidated from various places
 */
public abstract class SystemVersion {

    /*
     *  @since 0.9.28
     */
    public static final String DAEMON_USER = "i2psvc";
    /*
     *  @since 0.9.29
     */
    public static final String GENTOO_USER = "i2p";

    private static final boolean _isWin = System.getProperty("os.name").startsWith("Win");
    private static final boolean _isMac = System.getProperty("os.name").startsWith("Mac");
    private static final boolean _isArm = System.getProperty("os.arch").startsWith("arm");
    private static final boolean _isX86 = System.getProperty("os.arch").contains("86") ||
                                          System.getProperty("os.arch").equals("amd64");
    private static final boolean _isGentoo = System.getProperty("os.version").contains("gentoo") ||
                                             System.getProperty("os.version").contains("hardened");  // Funtoo
    private static final boolean _isAndroid;
    private static final boolean _isApache;
    private static final boolean _isGNU;
    private static final boolean _isOpenJDK;
    private static final boolean _is64;
    private static final boolean _hasWrapper = System.getProperty("wrapper.version") != null;
    private static final boolean _isLinuxService;
    private static final boolean _isSlow;

    private static final boolean _oneDotSix;
    private static final boolean _oneDotSeven;
    private static final boolean _oneDotEight;
    private static final boolean _oneDotNine;
    private static final boolean _oneDotTen;
    private static final boolean _oneDotEleven;
    private static final int _androidSDK;

    static {
        boolean is64 = "64".equals(System.getProperty("sun.arch.data.model")) ||
                       System.getProperty("os.arch").contains("64");
        if (_isWin && !is64) {
            // http://stackoverflow.com/questions/4748673/how-can-i-check-the-bitness-of-my-os-using-java-j2se-not-os-arch
            // http://blogs.msdn.com/b/david.wang/archive/2006/03/26/howto-detect-process-bitness.aspx
            String arch = System.getenv("PROCESSOR_ARCHITECTURE");
            String wow64Arch = System.getenv("PROCESSOR_ARCHITEW6432");
            is64 = (arch != null && arch.endsWith("64")) ||
                   (wow64Arch != null && wow64Arch.endsWith("64"));
        }
        _is64 = is64;

        String vendor = System.getProperty("java.vendor");
        _isAndroid = vendor.contains("Android");
        _isApache = vendor.startsWith("Apache");
        _isGNU = vendor.startsWith("GNU Classpath") ||               // JamVM
                 vendor.startsWith("Free Software Foundation");      // gij
        String runtime = System.getProperty("java.runtime.name");
        _isOpenJDK = runtime != null && runtime.contains("OpenJDK");
        _isLinuxService = !_isWin && !_isMac && !_isAndroid &&
                          (DAEMON_USER.equals(System.getProperty("user.name")) ||
                           (_isGentoo && GENTOO_USER.equals(System.getProperty("user.name"))));
        _isSlow = _isAndroid || _isApache || _isArm || _isGNU || getMaxMemory() < 48*1024*1024L;

        int sdk = 0;
        if (_isAndroid) {
            try {
                Class<?> ver = Class.forName("android.os.Build$VERSION", true, ClassLoader.getSystemClassLoader());
                Field field = ver.getField("SDK_INT");
                sdk = field.getInt(null);
            } catch (Exception e) {}
        }
        _androidSDK = sdk;

        if (_isAndroid) {
            _oneDotSix = _androidSDK >= 9;
            _oneDotSeven = _androidSDK >= 19;
            // https://developer.android.com/studio/write/java8-support.html
            // some stuff in 24
            _oneDotEight = false;
            _oneDotNine = false;
            _oneDotTen = false;
            _oneDotEleven = false;
        } else {
            String version = System.getProperty("java.version");
            // handle versions like "8-ea" or "9-internal"
            if (!version.startsWith("1."))
                version = "1." + version;
            _oneDotSix = VersionComparator.comp(version, "1.6") >= 0;
            _oneDotSeven = _oneDotSix && VersionComparator.comp(version, "1.7") >= 0;
            _oneDotEight = _oneDotSeven && VersionComparator.comp(version, "1.8") >= 0;
            _oneDotNine = _oneDotEight && VersionComparator.comp(version, "1.9") >= 0;
            // Starting 2018, versions are YY.M, this works for that also
            _oneDotTen = _oneDotNine && VersionComparator.comp(version, "1.10") >= 0;
            _oneDotEleven = _oneDotTen && VersionComparator.comp(version, "1.11") >= 0;
        }
    }

    public static boolean isWindows() {
        return _isWin;
    }

    public static boolean isMac() {
        return _isMac;
    }

    public static boolean isAndroid() {
        return _isAndroid;
    }

    /**
     *  Apache Harmony JVM, or Android
     */
    public static boolean isApache() {
        return _isApache || _isAndroid;
    }

    /**
     *  gij or JamVM with GNU Classpath
     */
    public static boolean isGNU() {
        return _isGNU;
    }

    /**
     *  @since 0.9.23
     */
    public static boolean isGentoo() {
        return _isGentoo;
    }

    /**
     *  @since 0.9.26
     */
    public static boolean isOpenJDK() {
        return _isOpenJDK;
    }

    /**
     *  @since 0.9.8
     */
    public static boolean isARM() {
        return _isArm;
    }

    /**
     *  @since 0.9.14
     */
    public static boolean isX86() {
        return _isX86;
    }

    /**
     *  Our best guess on whether this is a slow architecture / OS / JVM,
     *  using some simple heuristics.
     *
     *  @since 0.9.30
     */
    public static boolean isSlow() {
        // we don't put the NBI call in the static field,
        // to prevent a circular initialization with NBI.
        return _isSlow || !NativeBigInteger.isNative();
    }

    /**
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") &gt;= 0
     *  as it handles Android also, where java.version = "0".
     *
     *  @return true if Java 1.6 or higher, or Android API 9 or higher
     */
    public static boolean isJava6() {
        return _oneDotSix;
    }

    /**
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.7") &gt;= 0
     *  as it handles Android also, where java.version = "0".
     *
     *  @return true if Java 1.7 or higher, or Android API 19 or higher
     *  @since 0.9.14
     */
    public static boolean isJava7() {
        return _oneDotSeven;
    }

    /**
     *
     *  @return true if Java 1.8 or higher, false for Android.
     *  @since 0.9.15
     */
    public static boolean isJava8() {
        return _oneDotEight;
    }

    /**
     *
     *  @return true if Java 9 or higher, false for Android.
     *  @since 0.9.23
     */
    public static boolean isJava9() {
        return _oneDotNine;
    }

    /**
     *
     *  @return true if Java 10 or higher, false for Android.
     *  @since 0.9.33
     */
    public static boolean isJava10() {
        return _oneDotTen;
    }

    /**
     *
     *  @return true if Java 11 or higher, false for Android.
     *  @since 0.9.35
     */
    public static boolean isJava11() {
        return _oneDotEleven;
    }

    /**
     * This isn't always correct.
     * http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     * http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     * http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     * sun.arch.data.model not on all JVMs
     * sun.arch.data.model == 64 =&gt; 64 bit processor
     * sun.arch.data.model == 32 =&gt; A 32 bit JVM but could be either 32 or 64 bit processor or libs
     * os.arch contains "64" could be 32 or 64 bit libs
     */
    public static boolean is64Bit() {
        return _is64;
    }

    /*
     *  @since 0.9.28
     */
    public static boolean isLinuxService() {
        return _isLinuxService;
    }

    /**
     *  Identical to android.os.Build.VERSION.SDK_INT.
     *  For use outside of Android code.
     *  @return The SDK (API) version, e.g. 8 for Froyo, 0 if unknown
     */
    public static int getAndroidVersion() {
        return _androidSDK;
    }

    /**
     *  Is the wrapper present?
     *  Same as I2PAppContext.hasWrapper()
     */
    public static boolean hasWrapper() {
        return _hasWrapper;
    }

    /**
     *  Runtime.getRuntime().maxMemory() but check for
     *  bogus values
     *  @since 0.9.8
     */
    public static long getMaxMemory() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory >= Long.MAX_VALUE / 2)
            maxMemory = 96*1024*1024l;
        return maxMemory;
    }

    /**
     *  Runtime.getRuntime().availableProcssors()
     *  @return never smaller than 1
     *  @since 0.9.34
     */
    public static int getCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     *  The system's time zone, which is probably different from the
     *  JVM time zone, because Router changes the JVM default to GMT.
     *  It saves the old default in the context properties where we can get it.
     *  Use this to format a time in local time zone with DateFormat.setTimeZone().
     *
     *  @return non-null
     *  @since 0.9.24
     */
    public static TimeZone getSystemTimeZone() {
        return getSystemTimeZone(I2PAppContext.getGlobalContext());
    }

    /**
     *  The system's time zone, which is probably different from the
     *  JVM time zone, because Router changes the JVM default to GMT.
     *  It saves the old default in the context properties where we can get it.
     *  Use this to format a time in local time zone with DateFormat.setTimeZone().
     *
     *  @return non-null
     *  @since 0.9.24
     */
    public static TimeZone getSystemTimeZone(I2PAppContext ctx) {
        String systemTimeZone = ctx.getProperty("i2p.systemTimeZone");
        if (systemTimeZone != null)
            return TimeZone.getTimeZone(systemTimeZone);
        return TimeZone.getDefault();
    }

    /**
     *  @since 0.9.24
     */
    public static void main(String[] args) {
        System.out.println("64 bit   : " + is64Bit());
        System.out.println("Java 6   : " + isJava6());
        System.out.println("Java 7   : " + isJava7());
        System.out.println("Java 8   : " + isJava8());
        System.out.println("Java 9   : " + isJava9());
        System.out.println("Java 10  : " + isJava10());
        System.out.println("Java 11  : " + isJava11());
        System.out.println("Android  : " + isAndroid());
        if (isAndroid())
            System.out.println("  Version: " + getAndroidVersion());
        System.out.println("Apache   : " + isApache());
        System.out.println("ARM      : " + isARM());
        System.out.println("Cores    : " + getCores());
        System.out.println("Gentoo   : " + isGentoo());
        System.out.println("GNU      : " + isGNU());
        System.out.println("Linux Svc: " + isLinuxService());
        System.out.println("Mac      : " + isMac());
        System.out.println("OpenJDK  : " + isOpenJDK());
        System.out.println("Slow     : " + isSlow());
        System.out.println("Windows  : " + isWindows());
        System.out.println("Wrapper  : " + hasWrapper());
        System.out.println("x86      : " + isX86());
        System.out.println("Max mem  : " + getMaxMemory());

    }
}
