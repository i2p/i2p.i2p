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
    private static int getExtendedEDXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.EDX;
    }
    private static int getExtendedECXCPUFlags()
    {
        CPUIDResult c = doCPUID(0x80000001);
        return c.ECX;
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
        public String getVendor()
        {
            return getCPUVendorID();
        }
        public boolean hasMMX(){
            return (getEDXCPUFlags() & 0x800000) >0; //EDX Bit 23
        }
        public boolean hasSSE(){
            return (getEDXCPUFlags() & 0x2000000) >0; //EDX Bit 25
        }
        public boolean hasSSE2(){
            return (getEDXCPUFlags() & 0x4000000) >0; //EDX Bit 26
        }
        public boolean hasSSE3(){
            return (getEDXCPUFlags() & 0x1) >0; //ECX Bit 0
        }
        public boolean hasSSE41(){
            return (getEDXCPUFlags() & 0x80000) >0; //ECX Bit 19
        }
        public boolean hasSSE42(){
            return (getEDXCPUFlags() & 0x100000) >0; //ECX Bit 20
        }
        public boolean hasSSE4A(){
            return (getExtendedECXCPUFlags() & 0x40) >0; //Extended ECX Bit 6
        }
        public boolean IsC3Compatible() { return false; }
    }

    protected static class VIAC3Impl extends CPUIDCPUInfo implements CPUInfo {
        @Override
        public boolean IsC3Compatible() { return true; }
        public String getCPUModelString() { return "VIA C3"; }
    }

    protected static class AMDInfoImpl extends CPUIDCPUInfo implements AMDCPUInfo
    {
	//AMD-family = getCPUFamily()+getCPUExtendedFamily()
	//AMD-model = getCPUModel()+getCPUExtendedModel()
        public boolean IsK6Compatible(){
            return (getCPUFamily() + getCPUExtendedFamily()) >= 5 && (getCPUModel() + getCPUExtendedModel()) >= 6;
        }
		
        public boolean IsK6_2_Compatible(){
            return (getCPUFamily() + getCPUExtendedFamily()) >= 5 && (getCPUModel() + getCPUExtendedModel()) >= 8;
        }
		
        public boolean IsK6_3_Compatible(){
            return (getCPUFamily() + getCPUExtendedFamily()) >= 5 && (getCPUModel() + getCPUExtendedModel()) >= 9;
        }
		
        public boolean IsAthlonCompatible(){
            return (getCPUFamily() + getCPUExtendedFamily()) >= 6;
        }
		
        public boolean IsAthlon64Compatible(){	
			//AMD64 class
			if ((getCPUFamily() + getCPUExtendedFamily()) == 15){
				return true;
			//Stars (Phenom II/Athlon II/Third-Generation Opteron/Opteron 4100 & 6100/Sempron 1xx) 
			} else if ((getCPUFamily() + getCPUExtendedFamily()) == 16){
				return true;
			//K8 mobile+HT3 (Turion X2/Athlon X2/Sempron)
			} else if ((getCPUFamily() + getCPUExtendedFamily()) == 17){
				return true;
			} else {
				return false;
			}
        }

        public String getCPUModelString() throws UnknownCPUException {

		//i486 class (Am486, 5x86) 
            if(getCPUFamily() + getCPUExtendedFamily() == 4){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 3:
                        return "486 DX/2";
                    case 7:
                        return "486 DX/2-WB";
                    case 8:
                        return "486 DX/4";
                    case 9:
                        return "486 DX/4-WB";
                    case 14:
                        return "Am5x86-WT";
                    case 15:
                        return "Am5x86-WB";
                }
            }
			//i586 class (K5/K6/K6-2/K6-III) 
            if(getCPUFamily() + getCPUExtendedFamily() == 5){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 0:
                        return "K5/SSA5";
                    case 1:
                        return "K5";
                    case 2:
                        return "K5";
                    case 3:
                        return "K5";
                    case 6:
                        return "K6";
                    case 7:
                        return "K6";
                    case 8:
                        return "K6-2";
                    case 9:
                        return "K6-3";
                    case 13:
                        return "K6-2+ or K6-III+";
                }
            }
			//i686 class (Athlon/Athlon XP/Duron/K7 Sempron) 
            if(getCPUFamily() + getCPUExtendedFamily() == 6){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 0:
                        return "Athlon (250 nm)";
                    case 1:
                        return "Athlon (250 nm)";
                    case 2:
                        return "Athlon (180 nm)";
                    case 3:
                        return "Duron";
                    case 4:
                        return "Athlon (Thunderbird)";
                    case 6:
                        return "Athlon (Palamino)";
                    case 7:
                        return "Duron (Morgan)";
                    case 8:
                        return "Athlon (Thoroughbred)";
                    case 10:
                        return "Athlon (Barton)";
				}
            }
			//AMD64 class (A64/Opteron/A64 X2/K8 Sempron/Turion/Second-Generation Opteron/Athlon Neo) 
		   if(getCPUFamily() + getCPUExtendedFamily() == 15){
				switch(getCPUModel() + getCPUExtendedModel()){
					case 4:
						return "Athlon 64/Mobile XP-M";
					case 5:
						return "Athlon 64 FX Opteron";
					case 7:
						return "Athlon 64 FX (Sledgehammer S939, 130 nm)";
					case 8:
						return "Mobile A64/Sempron/XP-M";
					case 11:
						return "Athlon 64 (Clawhammer S939, 130 nm)";
					case 12:
						return "Athlon 64/Sempron (Newcastle S754, 130 nm)";
					case 14:
						return "Athlon 64/Sempron (Newcastle S754, 130 nm)";
					case 15:
						return "Athlon 64/Sempron (Clawhammer S939, 130 nm)";					
					case 18:
						return "Sempron (Palermo, 90 nm)";
					case 20:
						return "Athlon 64 (Winchester S754, 90 nm)";
					case 23:
						return "Athlon 64 (Winchester S939, 90 nm)";
					case 24:
						return "Mobile A64/Sempron/XP-M (Winchester S754, 90 nm)";
					case 26:
						return "Athlon 64 (Winchester S939, 90 nm)";
					case 27:
						return "Athlon 64/Sempron (Winchester/Palermo 90 nm)";
					case 28:
						return "Sempron (Palermo, 90 nm)";
					case 31:
						return "Athlon 64/Sempron (Winchester/Palermo, 90 nm)";
					case 33:
						return "Dual-Core Opteron (Italy-Egypt S940, 90 nm)";
					case 35:
						return "Athlon 64 X2/A64 FX/Opteron (Toledo/Denmark S939, 90 nm)";
					case 36:
						return "Mobile A64/Turion (Lancaster/Richmond/Newark, 90 nm)";
					case 37:
						return "Opteron (Troy/Athens S940, 90 nm)";
					case 39:
						return "Athlon 64 (San Diego, 90 nm)";
					case 43:
						return "Athlon 64 X2 (Manchester, 90 nm)";
					case 44:
						return "Sempron/mobile Sempron (Palermo/Albany/Roma S754, 90 nm)";
					case 47:
						return "Athlon 64/Sempron (Venice/Palermo S939, 90 nm)";
					case 65:
						return "Second-Generaton Opteron (Santa Rosa S1207, 90 nm)";
					case 67:
						return "Athlon 64 X2/2nd-gen Opteron (Windsor/Santa Rosa, 90 nm)";
					case 72:
						return "Athlon 64 X2/Turion 64 X2 (Windsor/Taylor/Trinidad, 90 nm)";
					case 75:
						return "Athlon 64 X2 (Windsor, 90 nm)";
					case 76:
						return "Mobile A64/mobile Sempron/Turion (Keene/Trinidad/Taylor, 90 nm)";
					case 79:
						return "Athlon 64/Sempron (Orleans/Manila AM2, 90 nm)";
					case 93:
						return "Opteron Gen 2 (Santa Rosa, 90 nm)";
					case 95:
						return "A64/Sempron/mobile Sempron (Orleans/Manila/Keene, 90 nm)";
					case 104:
						return "Turion 64 X2 (Tyler S1, 65 nm)";
					case 107:
						return "Athlon 64 X2/Sempron X2/Athlon Neo X2 (Brisbane/Huron, 65 nm)";
					case 108:
						return "A64/Athlon Neo/Sempron/Mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
					case 111:
						return "Neo/Sempron/mobile Sempron (Huron/Sparta/Sherman, 65 nm)";
					case 124:
						return "Athlon/Sempron/mobile Sempron (Lima/Sparta/Sherman, 65 nm)";
					case 127:
						return "A64/Athlon Neo/Sempron/mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
					case 193:
						return "Athlon 64 FX (Windsor S1207 90 nm)";
					default: // is this safe?
						return "Athlon 64 (unknown)";
				}
            }
			//Stars (Phenom II/Athlon II/Third-Generation Opteron/Opteron 4100 & 6100/Sempron 1xx) 
            if(getCPUFamily() + getCPUExtendedFamily() == 16){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 2:
                        return "Phenom / Athlon / Opteron Gen 3 (Barcelona/Agena/Toliman/Kuma, 65 nm)";
                    case 4:
                        return "Phenom II / Opteron Gen 3 (Shanghai/Deneb/Heka/Callisto, 45 nm)";
                    case 5:
                        return "Athlon II X2/X3/X4 (Regor/Rana/Propus AM3, 45 nm)";
                    case 6:
                        return "Mobile Athlon II/Turion II/Phenom II/Sempron/V-series (Regor/Caspian/Champlain, 45 nm)";
                    case 8:
                        return "Six-Core Opteron/Opteron 4100 series (Istanbul/Lisbon, 45 nm)";
                    case 9:
                        return "Opteron 6100 series (Magny-Cours G34, 45 nm)";
                    case 10:
                        return "Phenom II X4/X6 (Zosma/Thuban AM3, 45 nm)";
                }
            }
			//K8 mobile+HT3 (Turion X2/Athlon X2/Sempron) 
            if(getCPUFamily() + getCPUExtendedFamily() == 17){
                switch(getCPUModel() + getCPUExtendedModel()){
                    case 3:
                        return "AMD Turion X2/Athlon X2/Sempron (Lion/Sable, 65 nm)";
                }
            }
			//Bobcat
            if(getCPUFamily() + getCPUExtendedFamily() == 20){
				//No known CPUIDs yet.
            }
            throw new UnknownCPUException("Unknown AMD CPU; Family="+(getCPUFamily() + getCPUExtendedFamily())+", Model="+(getCPUModel() + getCPUExtendedModel()));
		}
    }

    protected static class IntelInfoImpl extends CPUIDCPUInfo implements IntelCPUInfo
    {
        public boolean IsPentiumCompatible()
        {
            return getCPUFamily() >= 5;
        }
        public boolean IsPentiumMMXCompatible()
        {
            return IsPentium2Compatible() || (getCPUFamily() == 5 && (getCPUModel() ==4 || getCPUModel() == 8));
        }
        public boolean IsPentium2Compatible()
        {
            return getCPUFamily() > 6 || (getCPUFamily() == 6 && getCPUModel() >=3);
        }
        public boolean IsPentium3Compatible()
        {
            return getCPUFamily() > 6 || (getCPUFamily() == 6 && getCPUModel() >=7);
        }
        public boolean IsPentium4Compatible()
        {	
			// P4
        	if (getCPUFamily() >= 15){
        		return true;
			// Core i3/i5/i7
			// Remove when implemented isCoreiCompatible in BigInteger
			} else if (getCPUExtendedModel() == 2 && (getCPUFamily() == 6)){				 			
			// Xeon MP (45nm) or Core i7
			// Remove when implemented isCoreiCompatible in BigInteger
        	} else if (getCPUExtendedModel() == 1 && (getCPUFamily() == 6 && (getCPUModel() == 10 || getCPUModel() == 13 || getCPUModel() == 14))){
        		return true;
			// Core 2 Duo
			// Remove when implemented isCore7Compatible in BigInteger
        	} else if (getCPUExtendedModel() == 0 && getCPUFamily() == 6 && getCPUModel() == 15){
        		return true;
        	}
       		return false;
        }
		public boolean IsAtomCompatible()
		{
			if (getCPUExtendedModel() == 0 && getCPUFamily() == 6 && getCPUModel() == 12){
        		return true;
        	}
			return false;

		}
		public boolean IsCore2Compatible()
		{
			if (getCPUExtendedModel() == 0 && getCPUFamily() == 6 && getCPUModel() == 15){
        		return true;
        	}
			return false;
		}
		public boolean IsCoreiCompatible()
		{
			// Core i3/i5/i7
			if (getCPUExtendedModel() == 2 && (getCPUFamily() == 6)){				 			
			// Xeon MP (45nm) or Core i7
        	} else if (getCPUExtendedModel() == 1 && (getCPUFamily() == 6 && (getCPUModel() == 10 || getCPUModel() == 13 || getCPUModel() == 14))){
        		return true;
        	}
			return false;
		}	
        public String getCPUModelString() throws UnknownCPUException {
        	if (getCPUExtendedModel() == 0){
	            if(getCPUFamily() == 4){
	                switch(getCPUModel()){
	                    case 0:
	                        return "486 DX-25/33";
	                    case 1:
	                        return "486 DX-50";
	                    case 2:
	                        return "486 SX";
	                    case 3:
	                        return "486 DX/2";
	                    case 4:
	                        return "486 SL";
	                    case 5:
	                        return "486 SX/2";
	                    case 7:
	                        return "486 DX/2-WB";
	                    case 8:
	                        return "486 DX/4";
	                    case 9:
	                        return "486 DX/4-WB";
	                }
	            }
        	}
            if (getCPUExtendedModel() == 0){
	            if(getCPUFamily() == 5){
	                switch(getCPUModel()){
	                    case 0:
	                        return "Pentium 60/66 A-step";
	                    case 1:
	                        return "Pentium 60/66";
	                    case 2:
	                        return "Pentium 75 - 200";
	                    case 3:
	                        return "OverDrive PODP5V83";
	                    case 4:
	                        return "Pentium MMX";
	                    case 7:
	                        return "Mobile Pentium 75 - 200";
	                    case 8:
	                        return "Mobile Pentium MMX";
	                }
	            }
            }
            if(getCPUFamily() == 6){
            	if (getCPUExtendedModel() == 0){
	                switch(getCPUModel()){
	                    case 0:
	                        return "Pentium Pro A-step";
	                    case 1:
	                        return "Pentium Pro";
	                    case 3:
	                        return "Pentium II (Klamath)";
	                    case 5:
	                        return "Pentium II (Deschutes), Celeron (Covington), Mobile Pentium II (Dixon)";
	                    case 6:
	                        return "Mobile Pentium II, Celeron (Mendocino)";
	                    case 7:
	                        return "Pentium III (Katmai)";
	                    case 8:
	                        return "Pentium III (Coppermine), Celeron w/SSE";
	                    case 9:
	                        return "Mobile Pentium III (Banias)";
	                    case 10:
	                        return "Pentium III Xeon (Cascades)";
	                    case 11:
	                        return "Pentium III (130 nm)";
	                    case 13:
	                        return "Mobile Pentium III (Dothan)";
	                    case 14:
	                        return "Mobile Core (Yonah)";
	                    case 15:
	                        return "Core 2 (Conroe)";
					}
            	} else if (getCPUExtendedModel() == 1){
					switch(getCPUModel()){
		    		 	case 10:
		    		 		return "Core i7 (45nm)";
		    		 	case 12:
		    		 		return "Atom";
		    		 	case 13:
		    		 		return "Xeon MP (45nm)";
						case 14:
							return "Core i5/i7 (45nm)";
		    		}
		    	} else if (getCPUExtendedModel() == 2){
					switch(getCPUModel()){
						case 5:
							return "Core i3 or i5/i7 mobile (32nm)";
						case 10:
							return "Core i7/i5 (32nm)";
						case 12:
							return "Core i7 (32nm)";
						case 14:
							return "Xeon MP (45nm)";
						case 15:
							return "Xeon MP (32nm)";
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
                    switch(getCPUModel()){
                        case 0:
                            return "Pentium IV (180 nm)";
                        case 1:
                            return "Pentium IV (180 nm)";
                        case 2:
                            return "Pentium IV (130 nm)";
                        case 3:
                            return "Pentium IV (90 nm)";
                        case 4:
                            return "Pentium IV (90 nm)";
                        case 6:
                            return "Pentium IV (65 nm)";
                    }
                }
                if(getCPUExtendedFamily() == 1){
                    switch(getCPUModel()){
                        //    Itanium 2.. TODO
                    }    
                }
            }
            throw new UnknownCPUException("Unknown Intel CPU; Family="+getCPUFamily()+", Model="+getCPUModel());
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
