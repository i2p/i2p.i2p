/*
 * Created on Jul 17, 2004
 *
 */
package freenet.support.CPUInformation;

/**
 * @author Iakin
 * An interface for classes that provide lowlevel information about Intel CPU's
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */
public interface IntelCPUInfo extends CPUInfo {
    /**
     * @return true iff the CPU is at least a Pentium CPU.
     */
    public boolean IsPentiumCompatible();
    /**
     * @return true iff the CPU is at least a Pentium which implements the MMX instruction/feature set.
     */
    public boolean IsPentiumMMXCompatible();
    /**
     * @return true iff the CPU implements at least the p6 instruction set (Pentium II or better).
     * Please note that an PentimPro CPU causes/should cause this method to return false (due to that CPU using a
     * very early implementation of the p6 instruction set. No MMX etc.)
     */
    public boolean IsPentium2Compatible();
    /**
     * @return true iff the CPU implements at least a Pentium III level of the p6 instruction/feature set.
     */
    public boolean IsPentium3Compatible();
    /**
     * @return true iff the CPU implements at least a Pentium IV level instruction/feature set.
     */
    public boolean IsPentium4Compatible();
}
