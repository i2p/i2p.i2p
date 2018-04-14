package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  Ref: http://en.wikipedia.org/wiki/CPUID
 *  @since 0.8.7
 */
class CPUIDCPUInfo implements CPUInfo
{
    public String getVendor()
    {
        return CPUID.getCPUVendorID();
    }

    public boolean hasMMX()
    {
        return (CPUID.getEDXCPUFlags() & (1 << 23)) != 0; //EDX Bit 23
    }

    public boolean hasSSE(){
        return (CPUID.getEDXCPUFlags() & (1 << 25)) != 0; //EDX Bit 25
    }

    public boolean hasSSE2()
    {
        return (CPUID.getEDXCPUFlags() & (1 << 26)) != 0; //EDX Bit 26
    }

    public boolean hasSSE3()
    {
        return (CPUID.getECXCPUFlags() & (1 << 0)) != 0; //ECX Bit 0
    }

    public boolean hasSSE41()
    {
        return (CPUID.getECXCPUFlags() & (1 << 19)) != 0; //ECX Bit 19
    }

    public boolean hasSSE42()
    {
        return (CPUID.getECXCPUFlags() & (1 << 20)) != 0; //ECX Bit 20
    }

    public boolean hasSSE4A()
    {
        return (CPUID.getExtendedECXCPUFlags() & (1 << 6)) != 0; //Extended ECX Bit 6
    }
    
    /**
     * @return true iff the CPU supports the AVX instruction set.
     * @since 0.9.26
     */
    public boolean hasAVX()
    {
        int ecx = CPUID.getECXCPUFlags();
        return (ecx & (1 << 28)) != 0 && //AVX: ECX Bit 28
               (ecx & (1 << 27)) != 0;   //XSAVE enabled by OS: ECX Bit 27
    }

    /**
     * @return true iff the CPU supports the AVX2 instruction set.
     * @since 0.9.26
     */
    public boolean hasAVX2() {
        return (CPUID.getExtendedEBXFeatureFlags() & (1 << 5)) != 0; //Extended EBX Feature Bit 5
    }
    
    /**
     * Does the CPU supports the AVX-512 Foundation instruction set?
     *
     * Quote wikipedia:
     *
     * AVX-512 consists of multiple extensions not all meant to be supported
     * by all processors implementing them. Only the core extension AVX-512F
     * (AVX-512 Foundation) is required by all implementations.
     *
     * ref: https://en.wikipedia.org/wiki/AVX-512
     *
     * @return true iff the CPU supports the AVX-512 Foundation instruction set.
     * @since 0.9.26
     */
    public boolean hasAVX512()
    {
        return (CPUID.getExtendedEBXFeatureFlags() & (1 << 16)) != 0; //Extended EBX Bit 16
    }
    
    /**
     *
     * Intel Multi-Precision Add-Carry Instruction Extensions
     * Available in Broadwell.
     * Unused until GMP 6.1.
     *
     * @return true iff the CPU supports the ADX instruction set.
     * @since 0.9.26
     */
    public boolean hasADX()
    {
        return (CPUID.getExtendedEBXFeatureFlags() & (1 << 19)) != 0; //Extended EBX Bit 19
    }
    
    /**
     * Trailing Bit Manipulation (AMD feature)
     * @return true iff the CPU supports TBM.
     * @since 0.9.26, broken before 0.9.35, fixed in 0.9.35
     */
    public boolean hasTBM()
    {
        return (CPUID.getExtendedECXCPUFlags() & (1 << 21)) != 0; //Extended ECX Bit 21
    }
    
    /**
     * @return true iff the CPU supports the AES-NI instruction set.
     * @since 0.9.14
     */
    public boolean hasAES() {
        return (CPUID.getECXCPUFlags() & (1 << 25)) != 0; //ECX Bit 25
    }
    
    /**
     * @return true iff the CPU supports the 64-bit support
     * @since 0.9.26
     */
    public boolean hasX64() {
        return (CPUID.getExtendedEDXCPUFlags() & (1 << 29)) != 0; //Extended EDX Bit 29
    }
    
    /**
     * @return true iff the CPU supports the BMI1 instruction set.
     * @since 0.9.26
     */
    public boolean hasBMI1() {
        return (CPUID.getExtendedEBXFeatureFlags() & (1 << 3)) != 0; // Extended EBX Feature Bit 3
    }
    
    /**
     * @return true iff the CPU supports the BMI2 instruction set.
     * @since 0.9.26
     */
    public boolean hasBMI2() {
        return (CPUID.getExtendedEBXFeatureFlags() & (1 << 8)) != 0; // Extended EBX Feature Bit 8
    }

    /**
     * @return true iff the CPU supports the FMA3 instruction set.
     * @since 0.9.26
     */
    public boolean hasFMA3() {
        return (CPUID.getECXCPUFlags() & (1 << 12)) != 0; // ECX Bit 12
    }

    /**
     * @return true iff the CPU supports the MOVBE instruction set.
     * @since 0.9.26
     */
    public boolean hasMOVBE() {
        return (CPUID.getECXCPUFlags() & (1 << 22)) != 0; // ECX Bit 22
    }

    /**
     * Also known as LZCNT
     * @return true iff the CPU supports the ABM instruction set.
     * @since 0.9.26
     */
    public boolean hasABM() {
        return (CPUID.getExtendedECXCPUFlags() & (1 << 5)) != 0; // Extended ECX Bit 5
    }

    @Override
    public String getCPUModelString() throws UnknownCPUException {
        throw new UnknownCPUException("Class CPUIDCPUInfo cannot indentify CPUs"); 
    }
}
