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
    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
    private static final boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
    private static final boolean isFreebsd = System.getProperty("os.name").toLowerCase().contains("freebsd");
    private static final boolean isSunos = System.getProperty("os.name").toLowerCase().contains("sunos");


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


    private static String getCPUVendorID()
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
    private static int getCPUFamily()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 8) & 0xf;
    }
    private static int getCPUModel()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 4) & 0xf;
    }
    private static int getCPUExtendedModel()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 16) & 0xf;
    }
    private static int getCPUType()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 12) & 0xf;
    }
    private static int getCPUExtendedFamily()
    {
        CPUIDResult c = doCPUID(1);
        return (c.EAX >> 20) & 0xff;
    }
    private static int getCPUStepping()
    {
        CPUIDResult c = doCPUID(1);
        return c.EAX & 0xf;
    }
    private static int getEDXCPUFlags()
    {
        CPUIDResult c = doCPUID(1);
        return c.EDX;
    }
    private static int getECXCPUFlags()
    {
        CPUIDResult c = doCPUID(1);
        return c.ECX;
    }
    private static int getExtendedEBXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EBX;    
    }
    private static int getExtendedECXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.ECX;
    }
    private static int getExtendedEDXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EDX;
    }
    
    //Returns a CPUInfo item for the current type of CPU
    //If I could I would declare this method in a interface named
    //CPUInfoProvider and implement that interface in this class.
    //This would make it easier for other people to understand that there
    //is nothing preventing them from coding up new providers, probably using
    //other detection methods than the x86-only CPUID instruction
    public static CPUInfo getInfo() throws UnknownCPUException
    {
        if(!_nativeOk)
            throw new UnknownCPUException("Failed to read CPU information from the system. Please verify the existence of the jcpuid dll/so.");
        if(getCPUVendorID().equals("CentaurHauls"))
            return new VIAC3Impl();
        if(!isX86)
            throw new UnknownCPUException("Failed to read CPU information from the system. The CPUID instruction exists on x86 CPU's only");
        if(getCPUVendorID().equals("AuthenticAMD"))
            return new AMDInfoImpl();
        if(getCPUVendorID().equals("GenuineIntel"))
            return new IntelInfoImpl();
        throw new UnknownCPUException("Unknown CPU type: '"+getCPUVendorID()+"'");
    }
    
    protected abstract static class CPUIDCPUInfo
    {
        protected boolean isX64 = false;        
                
        public String getVendor()
        {
            return getCPUVendorID();
        }
        public boolean hasMMX()
        {
            return (getEDXCPUFlags() & 0x800000) >0; //EDX Bit 23
        }
        public boolean hasSSE(){
            return (getEDXCPUFlags() & 0x2000000) >0; //EDX Bit 25
        }
        public boolean hasSSE2()
        {
            return (getEDXCPUFlags() & 0x4000000) >0; //EDX Bit 26
        }
        public boolean hasSSE3()
        {
            return (getEDXCPUFlags() & 0x1) >0; //ECX Bit 0
        }
        public boolean hasSSE41()
        {
            return (getEDXCPUFlags() & 0x80000) >0; //ECX Bit 19
        }
        public boolean hasSSE42()
        {
            return (getEDXCPUFlags() & 0x100000) >0; //ECX Bit 20
        }
        public boolean hasSSE4A()
        {
            return (getExtendedECXCPUFlags() & 0x40) >0; //Extended ECX Bit 6
        }
        public boolean IsC3Compatible() { return false; }
        public boolean hasX64(){ return isX64; } 
    }

    protected static class VIAC3Impl extends CPUIDCPUInfo implements CPUInfo {
        @Override
        public boolean IsC3Compatible() { return true; }
        public String getCPUModelString() { return "VIA C3"; }
    }

    protected static class AMDInfoImpl extends CPUIDCPUInfo implements AMDCPUInfo
    {
        protected static boolean isK6Compatible = false;
        protected static boolean isK6_2_Compatible = false;
        protected static boolean isK6_3_Compatible = false;
        protected static boolean isAthlonCompatible = false;
        protected static boolean isAthlon64Compatible = false;
        protected static boolean isBobcatCompatible = false;

        // If modelString != null, the cpu is considered correctly identified.
        protected static String modelString = null;
        protected static boolean hasBeenIdentified = false;

        public boolean IsK6Compatible(){ identifyCPU(); return isK6Compatible; }
        public boolean IsK6_2_Compatible(){ identifyCPU(); return isK6_2_Compatible; }
        public boolean IsK6_3_Compatible(){ identifyCPU(); return isK6_3_Compatible; }
        public boolean IsAthlonCompatible(){ identifyCPU(); return isAthlonCompatible; }
        public boolean IsAthlon64Compatible(){ identifyCPU(); return isAthlon64Compatible; }
        public boolean IsBobcatCompatible(){ identifyCPU(); return isBobcatCompatible; }

        public String getCPUModelString() throws UnknownCPUException
        {
            identifyCPU();
            if (modelString != null)
                return modelString;
            throw new UnknownCPUException("Unknown AMD CPU; Family="+(getCPUFamily() + getCPUExtendedFamily())+", Model="+(getCPUModel() + getCPUExtendedModel()));
        }
        
        /*
        * Identifies CPU, can't be used in static constructor due to JNI conflicts.
        * Has to be manually run after object initialisation.
        */
        private synchronized void identifyCPU()
        {
            if (hasBeenIdentified)
                return; // Don't identify twice

        //AMD-family = getCPUFamily()+getCPUExtendedFamily()
        //AMD-model = getCPUModel()+getCPUExtendedModel()
        //i486 class (Am486, 5x86) 
            if(getCPUFamily() + getCPUExtendedFamily() == 4){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 3:
                        modelString = "486 DX/2";
                        break;
                    case 7:
                        modelString = "486 DX/2-WB";
                        break;
                    case 8:
                        modelString = "486 DX/4";
                        break;
                    case 9:
                        modelString = "486 DX/4-WB";
                        break;
                    case 14:
                        modelString = "Am5x86-WT";
                        break;
                    case 15:
                        modelString = "Am5x86-WB";
                        break;
                }
            }
            //i586 class (K5/K6/K6-2/K6-III) 
            if(getCPUFamily() + getCPUExtendedFamily() == 5){
                isK6Compatible = true;
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 0:
                        modelString = "K5/SSA5";
                        break;
                    case 1:
                        modelString = "K5";
                        break;
                    case 2:
                        modelString = "K5";
                        break;
                    case 3:
                        modelString = "K5";
                        break;
                    case 6:
                        modelString = "K6";
                        break;
                    case 7:
                        modelString = "K6";
                        break;
                    case 8:
                        isK6_2_Compatible = true;
                        modelString = "K6-2";
                        break;
                    case 9:
                        isK6_2_Compatible = true;
                        isK6_3_Compatible = true;
                        modelString = "K6-3";
                        break;
                    case 13:
                        isK6_2_Compatible = true;
                        modelString = "K6-2+ or K6-III+";
                        break;
                }
            }
            //i686 class (Athlon/Athlon XP/Duron/K7 Sempron) 
            if(getCPUFamily() + getCPUExtendedFamily() == 6){
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 0:
                        modelString = "Athlon (250 nm)";
                        break;
                    case 1:
                        modelString = "Athlon (250 nm)";
                        break;
                    case 2:
                        modelString = "Athlon (180 nm)";
                        break;
                    case 3:
                        modelString = "Duron";
                        break;
                    case 4:
                        modelString = "Athlon (Thunderbird)";
                        break;
                    case 6:
                        modelString = "Athlon (Palamino)";
                        break;
                    case 7:
                        modelString = "Duron (Morgan)";
                        break;
                    case 8:
                        modelString = "Athlon (Thoroughbred)";
                        break;
                    case 10:
                        modelString = "Athlon (Barton)";
                        break;
                }
            }
            //AMD64 class (A64/Opteron/A64 X2/K8 Sempron/Turion/Second-Generation Opteron/Athlon Neo) 
           if(getCPUFamily() + getCPUExtendedFamily() == 15){
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isX64 = true;
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 4:
                        modelString = "Athlon 64/Mobile XP-M";
                    case 5:
                        modelString = "Athlon 64 FX Opteron";
                        break;
                    case 7:
                        modelString = "Athlon 64 FX (Sledgehammer S939, 130 nm)";
                        break;
                    case 8:
                        modelString = "Mobile A64/Sempron/XP-M";
                        break;
                    case 11:
                        modelString = "Athlon 64 (Clawhammer S939, 130 nm)";
                        break;
                    case 12:
                        modelString = "Athlon 64/Sempron (Newcastle S754, 130 nm)";
                        break;
                    case 14:
                        modelString = "Athlon 64/Sempron (Newcastle S754, 130 nm)";
                        break;
                    case 15:
                        modelString = "Athlon 64/Sempron (Clawhammer S939, 130 nm)";
                        break;                    
                    case 18:
                        modelString = "Sempron (Palermo, 90 nm)";
                        break;
                    case 20:
                        modelString = "Athlon 64 (Winchester S754, 90 nm)";
                        break;
                    case 23:
                        modelString = "Athlon 64 (Winchester S939, 90 nm)";
                        break;
                    case 24:
                        modelString = "Mobile A64/Sempron/XP-M (Winchester S754, 90 nm)";
                        break;
                    case 26:
                        modelString = "Athlon 64 (Winchester S939, 90 nm)";
                        break;
                    case 27:
                        modelString = "Athlon 64/Sempron (Winchester/Palermo 90 nm)";
                        break;
                    case 28:
                        modelString = "Sempron (Palermo, 90 nm)";
                        break;
                    case 31:
                        modelString = "Athlon 64/Sempron (Winchester/Palermo, 90 nm)";
                        break;
                    case 33:
                        modelString = "Dual-Core Opteron (Italy-Egypt S940, 90 nm)";
                        break;
                    case 35:
                        modelString = "Athlon 64 X2/A64 FX/Opteron (Toledo/Denmark S939, 90 nm)";
                        break;
                    case 36:
                        modelString = "Mobile A64/Turion (Lancaster/Richmond/Newark, 90 nm)";
                        break;
                    case 37:
                        modelString = "Opteron (Troy/Athens S940, 90 nm)";
                        break;
                    case 39:
                        modelString = "Athlon 64 (San Diego, 90 nm)";
                        break;
                    case 43:
                        modelString = "Athlon 64 X2 (Manchester, 90 nm)";
                        break;
                    case 44:
                        modelString = "Sempron/mobile Sempron (Palermo/Albany/Roma S754, 90 nm)";
                        break;
                    case 47:
                        modelString = "Athlon 64/Sempron (Venice/Palermo S939, 90 nm)";
                        break;
                    case 65:
                        modelString = "Second-Generaton Opteron (Santa Rosa S1207, 90 nm)";
                        break;
                    case 67:
                        modelString = "Athlon 64 X2/2nd-gen Opteron (Windsor/Santa Rosa, 90 nm)";
                        break;
                    case 72:
                        modelString = "Athlon 64 X2/Turion 64 X2 (Windsor/Taylor/Trinidad, 90 nm)";
                        break;
                    case 75:
                        modelString = "Athlon 64 X2 (Windsor, 90 nm)";
                        break;
                    case 76:
                        modelString = "Mobile A64/mobile Sempron/Turion (Keene/Trinidad/Taylor, 90 nm)";
                        break;
                    case 79:
                        modelString = "Athlon 64/Sempron (Orleans/Manila AM2, 90 nm)";
                        break;
                    case 93:
                        modelString = "Opteron Gen 2 (Santa Rosa, 90 nm)";
                        break;
                    case 95:
                        modelString = "A64/Sempron/mobile Sempron (Orleans/Manila/Keene, 90 nm)";
                        break;
                    case 104:
                        modelString = "Turion 64 X2 (Tyler S1, 65 nm)";
                        break;
                    case 107:
                        modelString = "Athlon 64 X2/Sempron X2/Athlon Neo X2 (Brisbane/Huron, 65 nm)";
                        break;
                    case 108:
                        modelString = "A64/Athlon Neo/Sempron/Mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 111:
                        modelString = "Neo/Sempron/mobile Sempron (Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 124:
                        modelString = "Athlon/Sempron/mobile Sempron (Lima/Sparta/Sherman, 65 nm)";
                        break;
                    case 127:
                        modelString = "A64/Athlon Neo/Sempron/mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                        break;
                    case 193:
                        modelString = "Athlon 64 FX (Windsor S1207 90 nm)";
                        break;
                    default: // is this safe?
                        modelString = "Athlon 64 (unknown)";
                        break;
                }
            }
            //Stars (Phenom II/Athlon II/Third-Generation Opteron/Opteron 4100 & 6100/Sempron 1xx) 
            if(getCPUFamily() + getCPUExtendedFamily() == 16){
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isX64 = true;
                switch(getCPUModel() + getCPUExtendedModel()\t\t){
                    case 2:
                        modelString = "Phenom / Athlon / Opteron Gen 3 (Barcelona/Agena/Toliman/Kuma, 65 nm)";
                        break;
                    case 4:
                        modelString = "Phenom II / Opteron Gen 3 (Shanghai/Deneb/Heka/Callisto, 45 nm)";
                        break;
                    case 5:270
                        modelString = "Athlon II X2/X3/X4 (Regor/Rana/Propus AM3, 45 nm)";
                        break;
                    case 6:
                        modelString = "Mobile Athlon II/Turion II/Phenom II/Sempron/V-series (Regor/Caspian/Champlain, 45 nm)";
                        break;
                    case 8:
                        modelString = "Six-Core Opteron/Opteron 4100 series (Istanbul/Lisbon, 45 nm)";
                        break;
                    case 9:
                        modelString = "Opteron 6100 series (Magny-Cours G34, 45 nm)";
                        break;
                    case 10:
                        modelString = "Phenom II X4/X6 (Zosma/Thuban AM3, 45 nm)";
                        break;
                }
            }
            //K8 mobile+HT3 (Turion X2/Athlon X2/Sempron) 
            if(getCPUFamily() + getCPUExtendedFamily() == 17){
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isX64 = true;
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 3:
                        modelString = "AMD Turion X2/Athlon X2/Sempron (Lion/Sable, 65 nm)";
                        break;
                }
            }
            //Bobcat
            if(getCPUFamily() + getCPUExtendedFamily() == 20){
                isK6Compatible = true;
                isK6_2_Compatible = true;
                isK6_3_Compatible = true;
                isAthlonCompatible = true;
                isAthlon64Compatible = true;
                isBobcatCompatible = true;
                isX64 = true;
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 1:                    
                        modelString = "Bobcat APU";
                        break;
                    // Case 3 is uncertain but most likely a Bobcat APU
                    case 3:
                        modelString = "Bobcat APU";
                        break;
                }
            }
        }
    }

    protected static class IntelInfoImpl extends CPUIDCPUInfo implements IntelCPUInfo
    {
        protected static boolean isPentiumCompatible = false;
        protected static boolean isPentiumMMXCompatible = false;
        protected static boolean isPentium2Compatible = false;
        protected static boolean isPentium3Compatible = false;
        protected static boolean isPentium4Compatible = false;
        protected static boolean isAtomCompatible = false;
        protected static boolean isCore2Compatible = false;
        protected static boolean isCoreiCompatible = false;
        
        // If modelString != null, the cpu is considered correctly identified.
        protected static String modelString = null;
        protected static boolean hasBeenIdentified = false;
        
        public boolean IsPentiumCompatible(){ identifyCPU(); return isPentiumCompatible; }
        public boolean IsPentiumMMXCompatible(){ identifyCPU(); return isPentiumMMXCompatible; }
        public boolean IsPentium2Compatible(){ identifyCPU(); return isPentium2Compatible; }
        public boolean IsPentium3Compatible(){ identifyCPU(); return isPentium3Compatible; }
        public boolean IsPentium4Compatible(){ identifyCPU(); return isPentium4Compatible; }
        public boolean IsAtomCompatible(){ identifyCPU(); return isAtomCompatible; }
        public boolean IsCore2Compatible(){ identifyCPU(); return isCore2Compatible; }
        public boolean IsCoreiCompatible(){ identifyCPU(); return isCoreiCompatible; }    

        public String getCPUModelString() throws UnknownCPUException
        {
            identifyCPU();
            if (modelString != null)
                return modelString;
            throw new UnknownCPUException("Unknown Intel CPU; Family="+getCPUFamily()+", Model="+getCPUModel());
        }
        
        /*
        * Identifies CPU, can't be used as static due to JNI conflicts.
        * Has to be manually run after object initialisation
        */
        private synchronized void identifyCPU()
        {
            if (hasBeenIdentified)
                return; // Don't identify twice
            if (getCPUExtendedModel() == 0){
                if(getCPUFamily() == 4){
                    switch(getCPUModel()){
                        case 0:
                            modelString = "486 DX-25/33";
                            break;
                        case 1:
                            modelString = "486 DX-50";
                            break;
                        case 2:
                            modelString = "486 SX";
                            break;
                        case 3:
                            modelString = "486 DX/2";
                            break;
                        case 4:
                            modelString = "486 SL";
                            break;
                        case 5:
                            modelString = "486 SX/2";
                            break;
                        case 7:
                            modelString = "486 DX/2-WB";
                            break;
                        case 8:
                            modelString = "486 DX/4";
                            break;
                        case 9:
                            modelString = "486 DX/4-WB";
                            break;
                    }
                }
            }
            if (getCPUExtendedModel() == 0){
                if(getCPUFamily() == 5){
                    isPentiumCompatible = true;
                    switch(getCPUModel()){
                        case 0:
                            modelString = "Pentium 60/66 A-step";
                            break;
                        case 1:
                            modelString = "Pentium 60/66";
                            break;
                        case 2:
                            modelString = "Pentium 75 - 200";
                            break;
                        case 3:
                            modelString = "OverDrive PODP5V83";
                            break;
                        case 4:
                            isPentiumMMXCompatible = true;
                            modelString = "Pentium MMX";
                            break;
                        case 7:
                            modelString = "Mobile Pentium 75 - 200";
                            break;
                        case 8:
                            isPentiumMMXCompatible = true;
                            modelString = "Mobile Pentium MMX";
                            break;
                    }
                }
            }
            if(getCPUFamily() == 6){
                if (getCPUExtendedModel() == 0){
                    isPentiumCompatible = true;
                    isPentiumMMXCompatible = true;
                    switch(getCPUModel()){
                        case 0:
                            modelString = "Pentium Pro A-step";
                            break;
                        case 1:
                            modelString = "Pentium Pro";
                            break;
                        case 3:
                            isPentium2Compatible = true;
                            modelString = "Pentium II (Klamath)";
                            break;
                        case 5:
                            isPentium2Compatible = true;
                            modelString = "Pentium II (Deschutes), Celeron (Covington), Mobile Pentium II (Dixon)";
                            break;
                        case 6:
                            isPentium2Compatible = true;
                            modelString = "Mobile Pentium II, Celeron (Mendocino)";
                            break;
                        case 7:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            modelString = "Pentium III (Katmai)";
                            break;
                        case 8:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            modelString = "Pentium III (Coppermine), Celeron w/SSE";
                            break;
                        case 9:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            isX64 = true;
                            modelString = "Pentium M (Banias)";
                            break;
                        case 10:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            modelString = "Pentium III Xeon (Cascades)";
                            break;
                        case 11:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            modelString = "Pentium III (130 nm)";
                            break;
                        case 13:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            isX64 = true;
                            modelString = "Core (Yonah)";
                            break;
                        case 14:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            isCore2Compatible = true;
                            isX64 = true;
                            modelString = "Core 2 (Conroe)";
                            break;
                        case 15:
                            isPentium2Compatible = true;
                            isPentium3Compatible = true;
                            isCore2Compatible = true;
                            isX64 = true;
                            modelString = "Core 2 (Conroe)";
                            break;
                    }
                } else if (getCPUExtendedModel() == 1){
                    isPentiumCompatible = true;
                    isPentiumMMXCompatible = true;
                    isPentium2Compatible = true;
                    isPentium3Compatible = true;
                    isPentium4Compatible = true;
                    isCore2Compatible = true;
                    isX64 = true;
                    switch(getCPUModel()){
                        case 6:
                            modelString = "Celeron";
                            break;
                         case 10:
                            isCoreiCompatible = true;
                             modelString = "Core i7 (45nm)";
                            break;
                         case 12:
                            isAtomCompatible = true;
                            isCore2Compatible = false;
                            isPentium4Compatible = false;
                            isX64 = true;
                            modelString = "Atom";
                            break;
                         case 13:
                            isCoreiCompatible = true;
                            modelString = "Xeon MP (45nm)";
                            break;
                        case 14:
                            isCoreiCompatible = true;
                            modelString = "Core i5/i7 (45nm)";
                            break;
                    }
                } else if (getCPUExtendedModel() == 2){
                    isPentiumCompatible = true;
                    isPentiumMMXCompatible = true;
                    isPentium2Compatible = true;
                    isPentium3Compatible = true;
                    isCore2Compatible = true;
                    isCoreiCompatible = true;
                    isX64 = true;
                    switch(getCPUModel()){
                        case 5:
                            modelString = "Core i3 or i5/i7 mobile (32nm)";
                            break;
                        case 10:
                            modelString = "Core i7/i5 (32nm)";
                            break;
                        case 12:
                            modelString = "Core i7 (32nm)";
                            break;
                        case 14:
                            modelString = "Xeon MP (45nm)";
                            break;
                        case 15:
                            modelString = "Xeon MP (32nm)";
                            break;
                    }                
                }
            }
            if(getCPUFamily() == 7){
                switch(getCPUModel()){
                    //Itanium..  TODO
                }
            }
            if(getCPUFamily() == 15){
                if(getCPUExtendedFamily() == 0){
                    isPentiumCompatible = true;
                    isPentiumMMXCompatible = true;
                    isPentium2Compatible = true;
                    isPentium3Compatible = true;
                    isPentium4Compatible = true;
                    switch(getCPUModel()){
                        case 0:
                            modelString = "Pentium IV (180 nm)";
                            break;
                        case 1:
                            modelString = "Pentium IV (180 nm)";
                            break;
                        case 2:
                            modelString = "Pentium IV (130 nm)";
                            break;
                        case 3:
                            modelString = "Pentium IV (90 nm)";
                            break;
                        case 4:
                            isX64 = true;
                            modelString = "Pentium IV (90 nm)";
                            break;
                        case 6:
                            isX64 = true;
                            modelString = "Pentium IV (65 nm)";
                            break;
                    }
                }
                if(getCPUExtendedFamily() == 1){
                    switch(getCPUModel()){
                        //    Itanium 2.. TODO
                    }    
                }
            }
        }
    }

    public static void main(String args[])
    {
        _doLog = true;
        if(!_nativeOk){
            System.out.println("**Failed to retrieve CPUInfo. Please verify the existence of jcpuid dll/so**");
        }
        System.out.println("**CPUInfo**");
        System.out.println("CPU Vendor: " + getCPUVendorID());
        System.out.println("CPU Family: " + getCPUFamily());
        System.out.println("CPU Model: " + getCPUModel());
        System.out.println("CPU Stepping: " + getCPUStepping());
        System.out.println("CPU Flags: " + getEDXCPUFlags());
        
        CPUInfo c = getInfo();
        System.out.println(" **More CPUInfo**");
        System.out.println(" CPU model string: " + c.getCPUModelString());
        System.out.println(" CPU has MMX: " + c.hasMMX());
        System.out.println(" CPU has SSE: " + c.hasSSE());
        System.out.println(" CPU has SSE2: " + c.hasSSE2());
        System.out.println(" CPU has SSE3: " + c.hasSSE3());
        System.out.println(" CPU has SSE4.1: " + c.hasSSE41());
        System.out.println(" CPU has SSE4.2: " + c.hasSSE42());
        System.out.println(" CPU has SSE4A: " + c.hasSSE4A());
        if(c instanceof IntelCPUInfo){
            System.out.println("  **Intel-info**");
            System.out.println("  Is pII-compatible: "+((IntelCPUInfo)c).IsPentium2Compatible());
            System.out.println("  Is pIII-compatible: "+((IntelCPUInfo)c).IsPentium3Compatible());
            System.out.println("  Is pIV-compatible: "+((IntelCPUInfo)c).IsPentium4Compatible());
            System.out.println("  Is atom-compatible: "+((IntelCPUInfo)c).IsAtomCompatible());
            System.out.println("  Is core2-compatible: "+((IntelCPUInfo)c).IsCore2Compatible());
            System.out.println("  Is corei-compatible: "+((IntelCPUInfo)c).IsCoreiCompatible());
        }
        if(c instanceof AMDCPUInfo){
            System.out.println("  **AMD-info**");
            System.out.println("  Is Athlon-compatible: "+((AMDCPUInfo)c).IsAthlonCompatible());
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
                System.err.println("ERROR: The resource " + resourceName 
                                   + " was not a valid library for this platform");
                ule.printStackTrace();
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
        FileUtil.copy(outFile.getAbsolutePath(), newFile.getAbsolutePath(), false, true);
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
        if(isFreebsd)
            return "jcpuid-x86-freebsd"; // The convention on freebsd...
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
        if(isSunos)
            return "jcpuid-x86_64-solaris";
        // use linux as the default, don't throw exception
        return "jcpuid-x86_64-linux";
    }
    
    private static final String getLibrarySuffix()
    {
        if(isWindows)
            return "dll";
        else
            return "so";
    }
}
