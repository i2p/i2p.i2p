/*
 * Created on Jul 16, 2004
 *
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
    
}
