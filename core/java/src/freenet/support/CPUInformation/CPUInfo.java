/*
 * Created on Jul 14, 2004
 * Updated on Jan 8, 2011
 */
package freenet.support.CPUInformation;

/**
 * @author Iakin
 * An interface for classes that provide lowlevel information about CPU's
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */

public interface CPUInfo
{
    /**
     * @return A string indicating the vendor of the CPU.
     */
    public String getVendor();
    /**
     * @return A string detailing what type of CPU that is present in the machine. I.e. 'Pentium IV' etc.
     * @throws UnknownCPUException If for any reson the retrieval of the requested information
     * failed. The message encapsulated in the execption indicates the 
     * cause of the failure.
     */
    public String getCPUModelString() throws UnknownCPUException;
    
    /**
     * @return true iff the CPU support the MMX instruction set.
     */
    public boolean hasMMX();
    /**
     * @return true iff the CPU support the SSE instruction set.
     */
    public boolean hasSSE();
    /**
     * @return true iff the CPU support the SSE2 instruction set.
     */
    public boolean hasSSE2();

    /**
     * @return true iff the CPU support the SSE3 instruction set.
     */
    public boolean hasSSE3();
    
    /**
     * @return true iff the CPU support the SSE4.1 instruction set.
     */
    public boolean hasSSE41();

    /**
     * @return true iff the CPU support the SSE4.2 instruction set.
     */
    public boolean hasSSE42();

    /**
     * @return true iff the CPU support the SSE4A instruction set.
     */
    public boolean hasSSE4A();

    public boolean IsC3Compatible();
}
