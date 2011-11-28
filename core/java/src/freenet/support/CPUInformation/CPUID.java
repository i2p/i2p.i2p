/*
 * Created on Jul 14, 2004
 * Updated on Jan 8, 2011
 */
package freenet.support.CPUInformation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;


/**
 * @author Iakin
 * A class for retrieveing details about the CPU using the CPUID assembly instruction.
 * A good resource for information about the CPUID instruction can be found here:
 * http://www.paradicesoftware.com/specs/cpuid/index.htm
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might. Use at your own risk.
*/

public class CPUID {

    /** did we load the native lib correctly? */
    private static boolean _nativeOk = false;

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
    private static boolean _doLog = System.getProperty("jcpuid.dontLog") == null &&
                                    I2PAppContext.getGlobalContext().isRouterContext();

    private static final boolean isX86 = System.getProperty("os.arch").contains("86") ||
                                         System.getProperty("os.arch").equals("amd64");
    private static final String libPrefix = (System.getProperty("os.name").startsWith("Win") ? "" : "lib");
    private static final String libSuffix = (System.getProperty("os.name").startsWith("Win") ? ".dll" : ".so");
    private static final boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.US).contains("windows");
    private static final boolean isLinux = System.getProperty("os.name").toLowerCase(Locale.US).contains("linux");
    private static final boolean isFreebsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("freebsd");
    private static final boolean isNetbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("netbsd");
    private static final boolean isOpenbsd = System.getProperty("os.name").toLowerCase(Locale.US).contains("openbsd");
    private static final boolean isSunos = System.getProperty("os.name").toLowerCase(Locale.US).contains("sunos");
    private static final boolean isMac = System.getProperty("os.name").startsWith("Mac");


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
    private static final boolean is64 = "64".equals(System.getProperty("sun.arch.data.model")) ||
                                        System.getProperty("os.arch").contains("64");

    static
    {
        loadNative();
    }
    //A class that can (amongst other things I assume) represent the state of the
    //different CPU registers after a call to the CPUID assembly method
    protected static class CPUIDResult {
        final int EAX;
        final int EBX;
        final int ECX;
        final int EDX;
        CPUIDResult(int EAX,int EBX,int ECX, int EDX)
        {
            this.EAX = EAX;
            this.EBX = EBX;
            this.ECX = ECX;
            this.EDX = EDX;
        }
    }

    /**Calls the indicated CPUID function and returns the result of the execution
     *
     * @param iFunction The CPUID function to call, should be 0 or larger
     * @return The contents of the CPU registers after the call to the CPUID function
     */
    private static native CPUIDResult doCPUID(int iFunction);


    static String getCPUVendorID()
    {
        CPUIDResult c = doCPUID(0);
        StringBuilder sb= new StringBuilder(13);
        sb.append((char)( c.EBX        & 0xFF));
        sb.append((char)((c.EBX >> 8)  & 0xFF));
        sb.append((char)((c.EBX >> 16) & 0xFF));
        sb.append((char)((c.EBX >> 24) & 0xFF));

        sb.append((char)( c.EDX        & 0xFF));
        sb.append((char)((c.EDX >> 8)  & 0xFF));
        sb.append((char)((c.EDX >> 16) & 0xFF));
        sb.append((char)((c.EDX >> 24) & 0xFF));

        sb.append((char)( c.ECX        & 0xFF));
        sb.append((char)((c.ECX >> 8)  & 0xFF));
        sb.append((char)((c.ECX >> 16) & 0xFF));
        sb.append((char)((c.ECX >> 24) & 0xFF));

        return sb.toString();
    }
    static int getCPUFamily()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 8) & 0xf;
    }
    static int getCPUModel()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 4) & 0xf;
    }
    static int getCPUExtendedModel()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 16) & 0xf;
    }
    static int getCPUType()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 12) & 0xf;
    }
    static int getCPUExtendedFamily()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 20) & 0xff;
    }
    static int getCPUStepping()
    {
        CPUIDResult c = doCPUID(1);
        return c.EAX & 0xf;
    }
    static int getEDXCPUFlags()
    {
        CPUIDResult c = doCPUID(1);
        return c.EDX;
    }
    static int getECXCPUFlags()
    {
        CPUIDResult c = doCPUID(1);
        return c.ECX;
    }
    static int getExtendedEBXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EBX;
    }
    static int getExtendedECXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.ECX;
    }

    /** @since 0.8.7 */
    static int getExtendedEDXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EDX;
    }

    /**
     * Returns a CPUInfo item for the current type of CPU
     * If I could I would declare this method in a interface named
     * CPUInfoProvider and implement that interface in this class.
     * This would make it easier for other people to understand that there
     * is nothing preventing them from coding up new providers, probably using
     * other detection methods than the x86-only CPUID instruction
     */
    public static CPUInfo getInfo() throws UnknownCPUException
    {
        if(!_nativeOk)
            throw new UnknownCPUException("Failed to read CPU information from the system. Please verify the existence of the jcpuid dll/so.");
        if(getCPUVendorID().equals("CentaurHauls"))
            return new VIAInfoImpl();
        if(!isX86)
            throw new UnknownCPUException("Failed to read CPU information from the system. The CPUID instruction exists on x86 CPU's only");
        if(getCPUVendorID().equals("AuthenticAMD"))
            return new AMDInfoImpl();
        if(getCPUVendorID().equals("GenuineIntel"))
            return new IntelInfoImpl();
        throw new UnknownCPUException("Unknown CPU type: '"+getCPUVendorID()+"'");
    }


    public static void main(String args[])
    {
        _doLog = true;
        if(!_nativeOk){
            System.out.println("**Failed to retrieve CPUInfo. Please verify the existence of jcpuid dll/so**");
        }
        System.out.println(" **CPUInfo**");
        System.out.println("CPU Vendor: " + getCPUVendorID());
        System.out.println("CPU Family: " + getCPUFamily());
        System.out.println("CPU Model: " + getCPUModel());
        System.out.println("CPU Stepping: " + getCPUStepping());
        System.out.println("CPU Flags: 0x" + Integer.toHexString(getEDXCPUFlags()));

        CPUInfo c = getInfo();
        System.out.println("\n **More CPUInfo**");
        System.out.println("CPU model string: " + c.getCPUModelString());
        System.out.println("CPU has MMX: " + c.hasMMX());
        System.out.println("CPU has SSE: " + c.hasSSE());
        System.out.println("CPU has SSE2: " + c.hasSSE2());
        System.out.println("CPU has SSE3: " + c.hasSSE3());
        System.out.println("CPU has SSE4.1: " + c.hasSSE41());
        System.out.println("CPU has SSE4.2: " + c.hasSSE42());
        System.out.println("CPU has SSE4A: " + c.hasSSE4A());
        if(c instanceof IntelCPUInfo){
            System.out.println("\n **Intel-info**");
            System.out.println("Is PII-compatible: "+((IntelCPUInfo)c).IsPentium2Compatible());
            System.out.println("Is PIII-compatible: "+((IntelCPUInfo)c).IsPentium3Compatible());
            System.out.println("Is PIV-compatible: "+((IntelCPUInfo)c).IsPentium4Compatible());
            System.out.println("Is Atom-compatible: "+((IntelCPUInfo)c).IsAtomCompatible());
            System.out.println("Is Pentium M compatible: "+((IntelCPUInfo)c).IsPentiumMCompatible());
            System.out.println("Is Core2-compatible: "+((IntelCPUInfo)c).IsCore2Compatible());
            System.out.println("Is Corei-compatible: "+((IntelCPUInfo)c).IsCoreiCompatible());
        }
        if(c instanceof AMDCPUInfo){
            System.out.println("\n **AMD-info**");
            System.out.println("Is K6-compatible: "+((AMDCPUInfo)c).IsK6Compatible());
            System.out.println("Is K6_2-compatible: "+((AMDCPUInfo)c).IsK6_2_Compatible());
            System.out.println("Is K6_3-compatible: "+((AMDCPUInfo)c).IsK6_3_Compatible());
            System.out.println("Is K6-compatible: "+((AMDCPUInfo)c).IsGeodeCompatible());
            System.out.println("Is Athlon-compatible: "+((AMDCPUInfo)c).IsAthlonCompatible());
            System.out.println("Is Athlon64-compatible: "+((AMDCPUInfo)c).IsAthlon64Compatible());
            System.out.println("Is Bobcat-compatible: "+((AMDCPUInfo)c).IsBobcatCompatible());
        }
    }

    /**
     * <p>Do whatever we can to load up the native library.
     * If it can find a custom built jcpuid.dll / libjcpuid.so, it'll use that.  Otherwise
     * it'll try to look in the classpath for the correct library (see loadFromResource).
     * If the user specifies -Djcpuid.enable=false it'll skip all of this.</p>
     *
     */
    private static final void loadNative() {
        try{
        String wantedProp = System.getProperty("jcpuid.enable", "true");
        boolean wantNative = "true".equalsIgnoreCase(wantedProp);
        if (wantNative) {
            boolean loaded = loadGeneric();
            if (loaded) {
                _nativeOk = true;
                if (_doLog)
                    System.err.println("INFO: Native CPUID library " + getLibraryMiddlePart() + " loaded from file");
            } else {
                loaded = loadFromResource();
                if (loaded) {
                    _nativeOk = true;
                    if (_doLog)
                        System.err.println("INFO: Native CPUID library " + getResourceName() + " loaded from resource");
                } else {
                    _nativeOk = false;
                    if (_doLog)
                        System.err.println("WARNING: Native CPUID library jcpuid not loaded - will not be able to read CPU information using CPUID");
                }
            }
        } else {
            if (_doLog)
                System.err.println("INFO: Native CPUID library jcpuid not loaded - will not be able to read CPU information using CPUID");
        }
        }catch(Exception e){
            if (_doLog)
                System.err.println("INFO: Native CPUID library jcpuid not loaded, reason: '"+e.getMessage()+"' - will not be able to read CPU information using CPUID");
        }
    }

    /**
     * <p>Try loading it from an explictly built jcpuid.dll / libjcpuid.so</p>
     * The file name must be (e.g. on linux) either libjcpuid.so or libjcpuid-x86-linux.so.
     * This method does not search for a filename with "_64" in it.
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadGeneric() {
        try {
            System.loadLibrary("jcpuid");
            return true;
        } catch (UnsatisfiedLinkError ule) {
            // fallthrough, try the OS-specific filename
        }

        // Don't bother trying a 64 bit filename variant.

        // 32 bit variant:
        // Note this is unlikely to succeed on a standard installation, since when we extract the library
        // in loadResource() below, we save it as jcpuid.dll / libcupid.so.
        // However, a distribution may put the file in, e.g., /usr/lib/jni/
        // with the libjcpuid-x86-linux.so name.
        // Ubuntu packages now use libjcpuid.so
        //try {
        //    System.loadLibrary(getLibraryMiddlePart());
        //    return true;
        //} catch (UnsatisfiedLinkError ule) {
            return false;
        //}
    }

    /**
     * <p>Check all of the jars in the classpath for the jcpuid dll/so.
     * This file should be stored in the resource in the same package as this class.
     *
     * <p>This is a pretty ugly hack, using the general technique illustrated by the
     * onion FEC libraries.  It works by pulling the resource, writing out the
     * byte stream to a temporary file, loading the native library from that file.
     * We then attempt to copy the file from the temporary dir to the base install dir,
     * so we don't have to do this next time - but we don't complain if it fails,
     * so we transparently support read-only base dirs.
     * </p>
     *
     * This tries the 64 bit version first if we think we may be 64 bit.
     * Then it tries the 32 bit version.
     *
     * @return true if it was loaded successfully, else false
     *
     */
    private static final boolean loadFromResource() {
        // try 64 bit first, if getResourceName64() returns non-null
        String resourceName = getResourceName64();
        if (resourceName != null) {
            boolean success = extractLoadAndCopy(resourceName);
            if (success)
                return true;
        }

        // now try 32 bit
        resourceName = getResourceName();
        boolean success = extractLoadAndCopy(resourceName);
        if (success)
            return true;

        if (_doLog)
            System.err.println("WARNING: Resource name [" + resourceName + "] was not found");
        return false;
    }

    /**
     * Extract a single resource, copy it to a temp location in the file system,
     * and attempt to load it. If the load succeeds, copy it to the installation
     * directory. Return value reflects only load success - copy will fail silently.
     *
     * @return true if it was loaded successfully, else false.
     * @since 0.8.7
     */
    private static final boolean extractLoadAndCopy(String resourceName) {
        URL resource = CPUID.class.getClassLoader().getResource(resourceName);
        if (resource == null)
            return false;
        File outFile = null;
        FileOutputStream fos = null;
        String filename = libPrefix + "jcpuid" + libSuffix;
        try {
            InputStream libStream = resource.openStream();
            outFile = new File(I2PAppContext.getGlobalContext().getTempDir(), filename);
            fos = new FileOutputStream(outFile);
            byte buf[] = new byte[4096];
            while (true) {
                int read = libStream.read(buf);
                if (read < 0) break;
                fos.write(buf, 0, read);
            }
            fos.close();
            fos = null;
            System.load(outFile.getAbsolutePath());//System.load requires an absolute path to the lib
        } catch (UnsatisfiedLinkError ule) {
            if (_doLog) {
                System.err.println("WARNING: The resource " + resourceName 
                                   + " was not a valid library for this platform " + ule);
                //ule.printStackTrace();
            }
            if (outFile != null)
                outFile.delete();
            return false;
        } catch (IOException ioe) {
            if (_doLog) {
                System.err.println("ERROR: Problem writing out the temporary native library data");
                ioe.printStackTrace();
            }
            if (outFile != null)
                outFile.delete();
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ioe) {}
            }
        }
        // copy to install dir, ignore failure
        File newFile = new File(I2PAppContext.getGlobalContext().getBaseDir(), filename);
        FileUtil.copy(outFile, newFile, false, true);
        return true;
    }

    /** @return non-null */
    private static final String getResourceName()
    {
        return getLibraryPrefix()+getLibraryMiddlePart()+"."+getLibrarySuffix();
    }

    /**
     * @return null if not on a 64 bit platform
     * @since 0.8.7
     */
    private static final String getResourceName64() {
        if (!is64)
            return null;
        return getLibraryPrefix() + get64LibraryMiddlePart() + "." + getLibrarySuffix();
    }

    private static final String getLibraryPrefix()
    {
        if(isWindows)
            return "";
        else
            return "lib";
    }

    private static final String getLibraryMiddlePart(){
        if(isWindows)
             return "jcpuid-x86-windows"; // The convention on Windows
	if(isMac) {
	    if(isX86) {
	        return "jcpuid-x86-osx";  // The convention on Intel Macs
	    }
	}
        if(isFreebsd)
            return "jcpuid-x86-freebsd"; // The convention on freebsd...
        if(isNetbsd)
            return "jcpuid-x86-netbsd"; // The convention on netbsd...
        if(isOpenbsd)
            return "jcpuid-x86-openbsd"; // The convention on openbsd...
        if(isSunos)
            return "jcpuid-x86-solaris"; // The convention on SunOS
        //throw new RuntimeException("Dont know jcpuid library name for os type '"+System.getProperty("os.name")+"'");
        // use linux as the default, don't throw exception
        return "jcpuid-x86-linux";
    }

    /** @since 0.8.7 */
    private static final String get64LibraryMiddlePart() {
        if(isWindows)
             return "jcpuid-x86_64-windows";
        if(isFreebsd)
            return "jcpuid-x86_64-freebsd";
        if(isNetbsd)
            return "jcpuid-x86_64-netbsd";
        if(isOpenbsd)
            return "jcpuid-x86_64-openbsd";
	if(isMac){
	    if(isX86){
	        return "jcpuid-x86_64-osx";
	    }
	}
        if(isSunos)
            return "jcpuid-x86_64-solaris";
        // use linux as the default, don't throw exception
        return "jcpuid-x86_64-linux";
    }

    private static final String getLibrarySuffix()
    {
        if(isWindows)
            return "dll";
	if(isMac)
	    return "jnilib";
	else
            return "so";
    }
}
