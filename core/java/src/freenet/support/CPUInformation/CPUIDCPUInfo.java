package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  Ref: http://en.wikipedia.org/wiki/CPUID
 *  @since 0.8.7
 */
abstract class CPUIDCPUInfo implements CPUInfo
{
    protected static boolean isX64 = false;        
            
    public String getVendor()
    {
        return CPUID.getCPUVendorID();
    }

    public boolean hasMMX()
    {
        return (CPUID.getEDXCPUFlags() & 0x800000) != 0; //EDX Bit 23
    }

    public boolean hasSSE(){
        return (CPUID.getEDXCPUFlags() & 0x2000000) != 0; //EDX Bit 25
    }

    public boolean hasSSE2()
    {
        return (CPUID.getEDXCPUFlags() & 0x4000000) != 0; //EDX Bit 26
    }

    public boolean hasSSE3()
    {
        return (CPUID.getECXCPUFlags() & 0x1) != 0; //ECX Bit 0
    }

    public boolean hasSSE41()
    {
        return (CPUID.getECXCPUFlags() & 0x80000) != 0; //ECX Bit 19
    }

    public boolean hasSSE42()
    {
        return (CPUID.getECXCPUFlags() & 0x100000) != 0; //ECX Bit 20
    }

    public boolean hasSSE4A()
    {
        return (CPUID.getExtendedECXCPUFlags() & 0x40) != 0; //Extended ECX Bit 6
    }

    /**
     * @return true iff the CPU supports the AES-NI instruction set.
     * @since 0.9.14
     */
    public boolean hasAES() {
        return (CPUID.getECXCPUFlags() & 0x2000000) != 0; //ECX Bit 25
    }
    
    public abstract boolean hasX64();
}
