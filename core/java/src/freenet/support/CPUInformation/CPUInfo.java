/*
 * Created on Jul 14, 2004
 * Updated on Jan 8, 2011
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */
package freenet.support.CPUInformation;

/**
 * An interface for classes that provide lowlevel information about CPU's
 *
 * @author Iakin
 */

public interface CPUInfo
{
    /**
     * @return A string indicating the vendor of the CPU.
     */
    public String getVendor();

    /**
     * @return A string detailing what type of CPU that is present in the machine. I.e. 'Pentium IV' etc.
     * @throws UnknownCPUException If for any reason the retrieval of the requested information
     * failed. The message encapsulated in the execption indicates the 
     * cause of the failure.
     */
    public String getCPUModelString() throws UnknownCPUException;

    /**
     * @return true iff the CPU supports the MMX instruction set.
     */
    public boolean hasMMX();

    /**
     * @return true iff the CPU supports the SSE instruction set.
     */
    public boolean hasSSE();

    /**
     * @return true iff the CPU supports the SSE2 instruction set.
     */
    public boolean hasSSE2();

    /**
     * @return true iff the CPU supports the SSE3 instruction set.
     */
    public boolean hasSSE3();
    
    /**
     * @return true iff the CPU supports the SSE4.1 instruction set.
     */
    public boolean hasSSE41();

    /**
     * @return true iff the CPU supports the SSE4.2 instruction set.
     */
    public boolean hasSSE42();

    /**
     * AMD K10 only. Not supported on Intel.
     * ref: https://en.wikipedia.org/wiki/SSE4.2#SSE4a
     *
     * @return true iff the CPU supports the SSE4A instruction set.
     */
    public boolean hasSSE4A();
    
    /**
     * @return true iff the CPU supports the AVX instruction set.
     * @since 0.9.26
     */
    public boolean hasAVX();
    
    /**
     * @return true iff the CPU supports the AVX2 instruction set.
     * @since 0.9.26
     */
    public boolean hasAVX2();
    
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
    public boolean hasAVX512();
    
    /**
     *
     * Intel Multi-Precision Add-Carry Instruction Extensions
     * Available in Broadwell.
     * Unused until GMP 6.1.
     *
     * @return true iff the CPU supports the ADX instruction set.
     * @since 0.9.26
     */
    public boolean hasADX();
    
    /**
     * Trailing Bit Manipulation (AMD feature)
     * @return true iff the CPU supports TBM.
     * @since 0.9.26, broken before 0.9.35, fixed in 0.9.35
     */
    public boolean hasTBM();

    /**
     * @return true iff the CPU supports the AES-NI instruction set.
     * @since 0.9.14
     */
    public boolean hasAES();
    
    /**
     * @return true iff the CPU supports the 64-bit support
     * @since 0.9.26
     */
    public boolean hasX64();
    
    /**
     * @return true iff the CPU supports the BMI1 instruction set.
     * @since 0.9.26
     */
    public boolean hasBMI1();
    
    /**
     * @return true iff the CPU supports the BMI2 instruction set.
     * @since 0.9.26
     */
    public boolean hasBMI2();
    
    /**
     * @return true iff the CPU supports the FMA3 instruction set.
     * @since 0.9.26
     */
    public boolean hasFMA3();

    /**
     * @return true iff the CPU supports the MOVBE instruction set.
     * @since 0.9.26
     */
    public boolean hasMOVBE();

    /**
     * Also known as LZCNT
     * @return true iff the CPU supports the ABM instruction set.
     * @since 0.9.26
     */
    public boolean hasABM();
}
