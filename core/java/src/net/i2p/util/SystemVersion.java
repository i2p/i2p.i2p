package net.i2p.util;

/*
 * public domain
 */

import java.lang.reflect.Field;

/**
 * Methods to find out what system we are running on
 *
 * @since 0.9.3 consolidated from various places
 */
public abstract class SystemVersion {

    private static final boolean _isWin = System.getProperty("os.name").startsWith("Win");
    private static final boolean _isMac = System.getProperty("os.name").startsWith("Mac");
    private static final boolean _isArm = System.getProperty("os.arch").startsWith("arm");
    private static final boolean _isX86 = System.getProperty("os.arch").contains("86") ||
                                          System.getProperty("os.arch").equals("amd64");
    private static final boolean _isAndroid;
    private static final boolean _isApache;
    private static final boolean _isGNU;
    private static final boolean _is64;
    private static final boolean _hasWrapper = System.getProperty("wrapper.version") != null;

    private static final boolean _oneDotSix;
    private static final boolean _oneDotSeven;
    private static final boolean _oneDotEight;
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
            _oneDotEight = false;
        } else {
            _oneDotSix = VersionComparator.comp(System.getProperty("java.version"), "1.6") >= 0;
            _oneDotSeven = _oneDotSix && VersionComparator.comp(System.getProperty("java.version"), "1.7") >= 0;
            _oneDotEight = _oneDotSeven && VersionComparator.comp(System.getProperty("java.version"), "1.8") >= 0;
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
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") >= 0
     *  as it handles Android also, where java.version = "0".
     *
     *  @return true if Java 1.6 or higher, or Android API 9 or higher
     */
    public static boolean isJava6() {
        return _oneDotSix;
    }

    /**
     *  Better than (new VersionComparator()).compare(System.getProperty("java.version"), "1.7") >= 0
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
     * This isn't always correct.
     * http://stackoverflow.com/questions/807263/how-do-i-detect-which-kind-of-jre-is-installed-32bit-vs-64bit
     * http://mark.koli.ch/2009/10/javas-osarch-system-property-is-the-bitness-of-the-jre-not-the-operating-system.html
     * http://mark.koli.ch/2009/10/reliably-checking-os-bitness-32-or-64-bit-on-windows-with-a-tiny-c-app.html
     * sun.arch.data.model not on all JVMs
     * sun.arch.data.model == 64 => 64 bit processor
     * sun.arch.data.model == 32 => A 32 bit JVM but could be either 32 or 64 bit processor or libs
     * os.arch contains "64" could be 32 or 64 bit libs
     */
    public static boolean is64Bit() {
        return _is64;
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
}
